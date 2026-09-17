package xyz.desent.data.repository

import android.content.Context
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.local.database.dao.AccountDao
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.nostr.NostrEventProcessor
import xyz.desent.domain.model.AccountCreationRequest
import xyz.desent.domain.model.AccountCreationResult
import xyz.desent.domain.model.CustodialAccountCreationRequest
import xyz.desent.domain.model.CustodialLoginResult
import xyz.desent.domain.model.User
import xyz.desent.domain.repository.AccountRepository
import xyz.desent.domain.repository.AuthRepository
import xyz.desent.domain.repository.CustodialAccountRepository
import xyz.desent.domain.repository.LinkedNostrProbe
import xyz.desent.domain.repository.LogoutResult
import xyz.desent.domain.repository.PreparedSignupKey
import xyz.desent.domain.repository.RelayRepository
import xyz.desent.domain.repository.UserRepository
import xyz.desent.data.registration.NostrLinkClient
import xyz.desent.domain.usecase.MediaUploadUseCase
import xyz.desent.domain.usecase.RegistrationUseCase
import xyz.desent.domain.usecase.SwitchAccountUseCase
import xyz.desent.util.ImageCompressor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Multi-account-aware auth repository.
 *
 * - [login] is **additive**: a new key is stored per-npub, an account row is
 *   inserted, and the new account is set as active. Other saved accounts are
 *   preserved.
 * - [custodialLogin] fetches + decrypts the password-protected key blob once
 *   and then feeds the decrypted nsec through the ordinary [login] pipeline.
 * - [loginWithNcryptsec] decrypts a pasted NIP-49 `ncryptsec1…` string with
 *   its password and provisions it through the same [login] pipeline.
 * - [logout] removes the active account; if other accounts remain, the most
 *   recently active one is auto-activated (returns [LogoutResult.Switched]),
 *   otherwise the device is fully logged out ([LogoutResult.FullyLoggedOut]).
 */
class AuthRepositoryImpl(
    private val secureKeyManager: SecureKeyManager,
    private val preferencesManager: PreferencesManager,
    private val relayRepository: RelayRepository,
    private val userRepository: UserRepository,
    private val nostrRepository: NostrRepository,
    private val eventProcessor: NostrEventProcessor,
    private val mediaUploadUseCase: MediaUploadUseCase,
    private val registrationUseCase: RegistrationUseCase,
    private val custodialAccountRepository: CustodialAccountRepository,
    private val nostrLinkClient: NostrLinkClient,
    private val accountRepository: AccountRepository,
    private val accountDao: AccountDao,
    private val switchAccountUseCase: SwitchAccountUseCase
) : AuthRepository {

    // Prevent multiple simultaneous reconnection attempts
    private val reconnectMutex = kotlinx.coroutines.sync.Mutex()
    private var isReconnecting = false

    override suspend fun login(nsec: String, rememberMe: Boolean, enableBiometrics: Boolean): Result<String> {
        return try {
            val validationResult = secureKeyManager.validateNSEC(nsec)
            if (validationResult.isFailure) {
                return Result.failure(
                    IllegalArgumentException("Invalid NSEC format: ${validationResult.exceptionOrNull()?.message}")
                )
            }

            val npub = validationResult.getOrThrow()

            // Add to multi-account store (also mirrors key into the legacy
            // single-slot so existing single-slot consumers see this account
            // as active). Preserves any pre-existing account row for this npub.
            val biometric = enableBiometrics && rememberMe
            val addResult = accountRepository.addAccount(npub, nsec, biometric)
            if (addResult.isFailure) {
                return Result.failure(
                    Exception("Failed to store account: ${addResult.exceptionOrNull()?.message}")
                )
            }

            // Set as active.
            preferencesManager.saveNpubKey(npub)
            accountDao.setLastActiveAt(npub, System.currentTimeMillis())
            preferencesManager.setRememberMe(rememberMe)
            preferencesManager.setBiometricEnabled(enableBiometrics)
            // The login "Require biometric auth" checkbox drives the app-open
            // biometric gate (consumed by AppLockController). The legacy
            // enable_biometrics flag is kept for backward compatibility.
            preferencesManager.setRequireBiometricOnOpen(enableBiometrics)

            try {
                // Only seed a placeholder for genuinely-new users. A non-null row here
                // means we already have cached (real) metadata for this npub, and
                // inserting a placeholder would clobber it via REPLACE. The placeholder
                // uses createdAt = 0L so any real kind-0 event from relays (whose
                // createdAt is the original publish time) always wins the staleness
                // check in NostrEventProcessor.processMetadataEvent.
                if (userRepository.getUserByNpub(npub) == null) {
                    val userRecord = User(
                        npub = npub,
                        name = npub.substring(0, 20),
                        displayName = null,
                        about = null,
                        picture = null,
                        nip05 = null,
                        createdAt = 0L
                    )
                    userRepository.upsertUser(userRecord)
                }
            } catch (e: Exception) {
                android.util.Log.w("MetadataProcessor", " Failed to save user record: ${e.message}")
            }

            activateSession()

            Result.success(npub)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * NIP-49 key login — same client-custodied shape as [custodialLogin]:
     * the pasted `ncryptsec1…` string is decrypted locally with the password
     * (scrypt is CPU/memory heavy, hence the background dispatcher) and the
     * resulting key feeds the ordinary [login] pipeline. The password is
     * never persisted. Nip49's typed exceptions propagate through the
     * Result so the ViewModel can distinguish a wrong password from a
     * malformed paste.
     */
    override suspend fun loginWithNcryptsec(
        ncryptsec: String,
        password: String,
        rememberMe: Boolean,
        enableBiometrics: Boolean
    ): Result<String> {
        return try {
            val rawKey = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                xyz.desent.crypto.Nip49.decrypt(ncryptsec, password)
            }
            val nsec = xyz.desent.crypto.Bech32Utils.hexToNsec(
                xyz.desent.crypto.Nip44Encryption.bytesToHex(rawKey)
            )
            login(nsec, rememberMe, enableBiometrics)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Bring the freshly-activated account's runtime session online: a fresh,
     * NIP-42-authenticated relay connection, the event processor, the
     * in-memory Nostr identity, and the identity-scoped subscriptions (gift
     * wraps here; own private storage / calendar / mailbox config / user
     * settings are opened inside [NostrRepository.restoreIdentity]).
     *
     * Shared by [login] and both account-creation flows. Without this the
     * in-memory identity stays null after activation, which means:
     *   - gift-wrap subscriptions never open (no inbound email)
     *   - every publish is refused with "Not logged in" (kind-0 profile,
     *     outbound mail)
     *   - the event processor's ingest guard is bypassed (null check)
     * until an account switch or a cold start re-runs the same sequence.
     * restoreIdentity() reads the key from the legacy single-slot, which
     * storeNSECForAccount already mirrored, so it picks up the right key.
     *
     * The relay socket is torn down first rather than reused. DeSentApplication
     * opens the DeSent socket before any key exists; the relay's NIP-42 AUTH
     * challenge arrives exactly once per connection and cannot be answered
     * without a key, and the relay refuses every identity-scoped REQ on an
     * unauthenticated connection. Reusing that socket would leave the account
     * silently dark until a cold start. A fresh connection is challenged with
     * the key already in place — the same hard boundary [SwitchAccountUseCase]
     * relies on.
     */
    private suspend fun activateSession() {
        try {
            relayRepository.disconnectFromAllRelays()
            // Connect ONLY to persistent relays.
            relayRepository.connectToPersistentRelays()

            // Let NIP-42 resolve before the first frames go out. REQs are
            // buffered client-side until then anyway; this also makes the
            // create flows' immediate kind-0 publish (which is not buffered)
            // land on an authenticated socket.
            val ready = relayRepository.waitForRelayReady(
                xyz.desent.data.RelayConfig.EMAIL_RELAY_URL,
                RELAY_READY_TIMEOUT_MS
            )
            if (ready) {
                android.util.Log.i("RelayManager", " DeSent relay connected and authenticated")
            } else {
                android.util.Log.w("RelayManager", " DeSent relay not ready after ${RELAY_READY_TIMEOUT_MS}ms, continuing anyway")
            }
        } catch (e: Exception) {
            android.util.Log.e("RelayManager", " Failed to connect to persistent relays: ${e.message}")
        }

        eventProcessor.startProcessing()
        android.util.Log.i("EventProcessor", " Started event processor")

        try {
            nostrRepository.restoreIdentity()
            nostrRepository.subscribeToGiftWraps()
            android.util.Log.i("AuthRepository", " Identity restored + gift-wrap subscriptions opened")
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", " Failed to restore identity: ${e.message}", e)
        }
    }

    /**
     * Client-custodied login — a one-time-per-device provisioning step. The
     * password-protected key blob is fetched and decrypted inside
     * [CustodialAccountRepository]; the resulting nsec then flows through the
     * ordinary [login] pipeline so relay connections, identity restore, and
     * account rows behave identically to a key import. The password itself is
     * never persisted.
     */
    override suspend fun custodialLogin(
        username: String,
        password: String,
        rememberMe: Boolean,
        enableBiometrics: Boolean
    ): Result<CustodialLoginResult> {
        val key = custodialAccountRepository.fetchKeyWithPassword(username, password)
            .getOrElse { return Result.failure(it) }

        val result = login(key.nsec, rememberMe, enableBiometrics)
        return if (result.isSuccess) {
            try {
                accountRepository.setCustodialUsername(key.npub, key.username)
            } catch (e: Exception) {
                android.util.Log.w("AuthRepository", " Failed to record custodial username: ${e.message}")
            }
            Result.success(CustodialLoginResult(npub = key.npub, legacyEnvelope = key.legacyEnvelope))
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Login failed"))
        }
    }

    override suspend fun prepareSignupKey(): Result<PreparedSignupKey> {
        return try {
            val (nsec, npub) = secureKeyManager.generateKeyPair().getOrThrow()
            val identity = nostr.id.Identity.create(xyz.desent.crypto.Bech32Utils.nsecToHex(nsec))
            Result.success(PreparedSignupKey(nsec = nsec, npub = npub, identity = identity))
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Signup key generation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun createAccount(
        request: AccountCreationRequest,
        context: Context,
        existingNsec: String?
    ): Result<AccountCreationResult> {
        return try {
            android.util.Log.d("AuthRepository", "Creating new account")

            val (nsec, npub) = existingNsec?.let { reuse ->
                // A paid vanity approval is bound to this key's pubkey —
                // validate rather than regenerate (CUSTODIAL_ACCOUNTS §4.1).
                val reusedNpub = secureKeyManager.validateNSEC(reuse).getOrThrow()
                reuse to reusedNpub
            } ?: secureKeyManager.generateKeyPair().getOrThrow()
            android.util.Log.d("AuthRepository", "Generated keypair: npub=${npub.take(20)}...")

            // Remember the previously-active account so a failed registration
            // can restore it (additive sign-up) instead of clearing the session.
            val previousActiveNpub = preferencesManager.getActiveNpub()

            // Store per-account BEFORE the registration HTTP call so NostrHttpAuth
            // (which reads from the legacy single-slot mirror) can sign.
            val biometric = false
            val addResult = accountRepository.addAccount(npub, nsec, biometric)
            if (addResult.isFailure) {
                return Result.failure(Exception("Failed to store key: ${addResult.exceptionOrNull()?.message}"))
            }
            preferencesManager.saveNpubKey(npub)
            accountDao.setLastActiveAt(npub, System.currentTimeMillis())

            val account = registrationUseCase.register(
                local = request.local,
                displayName = request.displayName,
                picture = null,
                about = request.about,
                referralCode = request.referralCode
            ).getOrElse { registrationFailure ->
                // The address was not claimed (bad invite code, mode gate, rate
                // limit, collision, …). Remove the just-stored key so a retry
                // doesn't pile up orphan accounts, and surface the typed error
                // so the UI can route back to the invite gate when relevant.
                accountRepository.removeAccount(npub)
                if (previousActiveNpub != null) {
                    preferencesManager.saveNpubKey(previousActiveNpub)
                } else {
                    preferencesManager.clearActiveNpub()
                }
                return Result.failure(registrationFailure)
            }

            // The address is claimed and the key is the active slot: bring the
            // session online now, before the first publish below needs the
            // in-memory identity.
            activateSession()

            var pictureUrl: String? = null
            if (request.pictureUri != null) {
                try {
                    pictureUrl = mediaUploadUseCase.uploadProfilePicture(request.pictureUri, context).getOrNull()
                } catch (e: Exception) {
                    android.util.Log.e("AuthRepository", "Failed to upload profile picture, continuing without it", e)
                }
            }

            val nip05 = account?.nip05
            val user = User(
                npub = npub,
                name = request.local.lowercase(),
                displayName = request.displayName,
                about = request.about,
                picture = pictureUrl ?: account?.picture,
                nip05 = nip05,
                createdAt = System.currentTimeMillis() / 1000
            )

            userRepository.upsertUser(user)
            accountRepository.updateAccountProfile(
                npub = npub,
                displayName = user.displayName ?: user.name,
                picture = user.picture,
                nip05 = user.nip05
            )

            try {
                nostrRepository.publishUserProfile(
                    name = user.name,
                    displayName = user.displayName,
                    about = user.about,
                    picture = user.picture,
                    nip05 = nip05
                )
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Failed to publish metadata event", e)
            }

            preferencesManager.setRememberMe(false)

            android.util.Log.i("AuthRepository", "Account creation successful for ${user.displayName}")

            Result.success(AccountCreationResult(nsec = nsec, npub = npub, user = user))
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Account creation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Username & password signup. The keypair is generated locally, the nsec
     * is encrypted under the password (Argon2id → AES-GCM, all on-device),
     * and only the verifier + opaque blob are uploaded. On success the
     * account is provisioned exactly like [createAccount]; the returned
     * [AccountCreationResult.nsec] drives the mandatory key-backup dialog
     * (a lost password AND no exported key = permanently lost account).
     */
    override suspend fun createCustodialAccount(
        request: CustodialAccountCreationRequest,
        context: Context,
        existingNsec: String?
    ): Result<AccountCreationResult> {
        return try {
            android.util.Log.d("AuthRepository", "Creating new custodial account")

            val (nsec, npub) = existingNsec?.let { reuse ->
                // Same reuse rule as createAccount: the vanity approval (if
                // any) is bound to this key's pubkey.
                val reusedNpub = secureKeyManager.validateNSEC(reuse).getOrThrow()
                reuse to reusedNpub
            } ?: secureKeyManager.generateKeyPair().getOrThrow()

            val previousActiveNpub = preferencesManager.getActiveNpub()

            // Store per-account BEFORE the registration HTTP call so NostrHttpAuth
            // (which reads the legacy single-slot mirror) can sign with the
            // fresh key — identical ordering constraint to createAccount.
            val addResult = accountRepository.addAccount(npub, nsec, false)
            if (addResult.isFailure) {
                return Result.failure(Exception("Failed to store key: ${addResult.exceptionOrNull()?.message}"))
            }
            preferencesManager.saveNpubKey(npub)
            accountDao.setLastActiveAt(npub, System.currentTimeMillis())

            val signup = custodialAccountRepository.signup(
                username = request.username,
                password = request.password,
                nsec = nsec,
                displayName = request.displayName,
                referralCode = request.referralCode
            ).getOrElse { signupFailure ->
                // Roll the freshly generated key back out so a retry doesn't
                // pile up orphan accounts, and surface the typed error.
                accountRepository.removeAccount(npub)
                if (previousActiveNpub != null) {
                    preferencesManager.saveNpubKey(previousActiveNpub)
                } else {
                    preferencesManager.clearActiveNpub()
                }
                return Result.failure(signupFailure)
            }

            // Same as createAccount: the session must be live before the
            // profile publish below.
            activateSession()

            val user = User(
                npub = npub,
                name = signup.username.lowercase(),
                displayName = request.displayName,
                about = null,
                picture = null,
                nip05 = signup.nip05,
                createdAt = System.currentTimeMillis() / 1000
            )

            userRepository.upsertUser(user)
            accountRepository.updateAccountProfile(
                npub = npub,
                displayName = user.displayName ?: user.name,
                picture = null,
                nip05 = user.nip05
            )
            accountRepository.setCustodialUsername(npub, signup.username)

            try {
                nostrRepository.publishUserProfile(
                    name = user.name,
                    displayName = user.displayName,
                    about = null,
                    picture = null,
                    nip05 = signup.nip05
                )
            } catch (e: Exception) {
                android.util.Log.e("AuthRepository", "Failed to publish metadata event", e)
            }

            preferencesManager.setRememberMe(false)

            android.util.Log.i("AuthRepository", "Custodial account creation successful for ${signup.username}")

            Result.success(AccountCreationResult(nsec = nsec, npub = npub, user = user))
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Custodial account creation failed", e)
            Result.failure(e)
        }
    }

    override suspend fun changeCustodialPassword(
        oldPassword: String,
        newPassword: String
    ): Result<Unit> {
        return try {
            val npub = preferencesManager.getActiveNpub()
                ?: return Result.failure(IllegalStateException("No active account"))
            val username = accountRepository.getCustodialUsername(npub)
                ?: return Result.failure(
                    xyz.desent.data.registration.model.RegistrationError.NotCustodial
                )
            val nsec = secureKeyManager.getNSECForAccount(npub).getOrNull()
                ?: secureKeyManager.getNSECKey().getOrNull()
                ?: return Result.failure(IllegalStateException("No stored key for the active account"))

            custodialAccountRepository.changePassword(username, oldPassword, newPassword, nsec)
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Custodial password change failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Linked Nostr-identity login, probe phase (NOSTR_CUSTODIAL.md §3).
     *
     * The pasted key never becomes an account key here — it only signs the
     * kind-22242 identity proof inside [NostrLinkClient.login]. A 404
     * `not_linked` maps to [LinkedNostrProbe.NotLinked] so the caller falls
     * back to the ordinary nsec import.
     */
    override suspend fun probeLinkedNostrAccount(nsec: String): Result<LinkedNostrProbe> {
        return try {
            val linkedNpub = secureKeyManager.validateNSEC(nsec).getOrThrow()
            val priv = xyz.desent.crypto.Bech32Utils.nsecToHex(nsec).let { hex ->
                ByteArray(hex.length / 2) { i ->
                    ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
                }
            }

            nostrLinkClient.login(priv).fold(
                onSuccess = { response ->
                    Result.success(
                        LinkedNostrProbe.Linked(
                            linkedNpub = linkedNpub,
                            handoff = response.handoff,
                            kdf = response.kdf,
                            kdfParams = response.kdfParams,
                            saltB64 = response.salt,
                            v = response.v
                        )
                    )
                },
                onFailure = { e ->
                    if (e is xyz.desent.data.registration.model.NostrLinkError.NotLinked) {
                        Result.success(LinkedNostrProbe.NotLinked)
                    } else {
                        Result.failure(e)
                    }
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "Linked-key probe failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Linked Nostr-identity login, password phase: derive the verifier over
     * the probe's salt, exchange the handoff for the linked account's blob,
     * decrypt on-device, run the swapped-blob guard, and provision through
     * the ordinary [login] pipeline. The linked ACCOUNT key (not the pasted
     * old key) is what gets stored.
     */
    override suspend fun completeLinkedNostrLogin(
        probe: LinkedNostrProbe.Linked,
        password: String,
        rememberMe: Boolean,
        enableBiometrics: Boolean
    ): Result<CustodialLoginResult> {
        if (probe.kdf != xyz.desent.crypto.CustodialCrypto.KDF_ARGON2ID) {
            return Result.failure(
                xyz.desent.data.registration.model.RegistrationError.Unknown(
                    "Unsupported key derivation: ${probe.kdf}"
                )
            )
        }
        val salt = try {
            java.util.Base64.getDecoder().decode(probe.saltB64)
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                xyz.desent.data.registration.model.RegistrationError.Unknown("Malformed challenge salt")
            )
        }

        val verifier = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            xyz.desent.crypto.CustodialCrypto.deriveVerifier(password, salt, probe.kdfParams)
        }

        val response = nostrLinkClient.verify(probe.handoff, verifier)
            .getOrElse { return Result.failure(it) }

        val nsec = try {
            xyz.desent.crypto.CustodialCrypto.decryptBlob(response.blob, password)
        } catch (e: xyz.desent.crypto.CustodialCrypto.WrongPasswordException) {
            return Result.failure(xyz.desent.data.registration.model.RegistrationError.InvalidCredentials)
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                xyz.desent.data.registration.model.RegistrationError.Unknown("Malformed key blob")
            )
        }

        // Swapped-blob guard: the decrypted key must BE the linked account key.
        val derivedNpub = secureKeyManager.validateNSEC(nsec).getOrNull()
            ?: return Result.failure(
                xyz.desent.data.registration.model.RegistrationError.Unknown("Key blob contains an invalid key")
            )
        if (derivedNpub != response.npub) {
            android.util.Log.w("AuthRepository", "Swapped-blob guard failed for linked login")
            return Result.failure(
                xyz.desent.data.registration.model.RegistrationError.Unknown("Key blob integrity check failed")
            )
        }

        val result = login(nsec, rememberMe, enableBiometrics)
        return if (result.isSuccess) {
            response.username?.let { username ->
                try {
                    accountRepository.setCustodialUsername(derivedNpub, username)
                } catch (e: Exception) {
                    android.util.Log.w("AuthRepository", " Failed to record custodial username: ${e.message}")
                }
            }
            Result.success(CustodialLoginResult(npub = derivedNpub))
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Login failed"))
        }
    }

    override suspend fun logout(): LogoutResult {
        val activeNpub = preferencesManager.getActiveNpub()
        return try {
            if (activeNpub == null) {
                // Defensive: no active account to remove.
                relayRepository.disconnectFromAllRelays()
                nostrRepository.logout()
                preferencesManager.clearAll()
                return LogoutResult.FullyLoggedOut
            }

            // Remove the active account's key, account row, and scoped data.
            accountRepository.removeAccount(activeNpub).getOrThrow()

            // Decide what's next.
            val remaining = accountDao.getAccounts() // sorted by lastActiveAt DESC
            if (remaining.isNotEmpty()) {
                val next = remaining.first()
                switchAccountUseCase.switchTo(next.npub).getOrThrow()
                LogoutResult.Switched(switchedTo = next.npub)
            } else {
                // Last account removed → fully logged out.
                relayRepository.disconnectFromAllRelays()
                nostrRepository.logout()
                secureKeyManager.deleteNSECKey() // clear legacy active slot
                preferencesManager.clearAll()
                LogoutResult.FullyLoggedOut
            }
        } catch (e: Exception) {
            android.util.Log.e("RelayManager", "Logout error: ${e.message}")
            // Best-effort: ensure device ends in a logged-out-looking state.
            try {
                relayRepository.disconnectFromAllRelays()
            } catch (_: Throwable) {}
            LogoutResult.FullyLoggedOut
        }
    }

    override suspend fun validateNsec(nsec: String): Result<String> {
        return secureKeyManager.validateNSEC(nsec)
    }

    override suspend fun getNpubFromNsec(nsec: String): String? {
        return try {
            secureKeyManager.validateNSEC(nsec).getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun isNsecStored(): Boolean {
        // The legacy single-slot reflects "is there an active account's key
        // loaded". Equivalent to "accountDao.count() > 0 && activeNpub != null"
        // but cheaper and matches what every existing caller has always meant.
        return secureKeyManager.hasNSECKey()
    }

    override suspend fun getStoredNpub(): String? {
        return try {
            preferencesManager.npubKey.first()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun reconnectRelays() {
        if (reconnectMutex.withLock { isReconnecting }) {
            android.util.Log.i("RelayManager", " Reconnection already in progress, skipping")
            return
        }

        reconnectMutex.withLock { isReconnecting = true }

        try {
            android.util.Log.i("RelayManager", " Starting relay reconnection process")

            val connectedRelays = relayRepository.getConnectedRelays()

            if (connectedRelays.isNotEmpty()) {
                android.util.Log.i("RelayManager", " Already connected to ${connectedRelays.size} relay(s), skipping reconnection")
                return
            }

            android.util.Log.i("RelayManager", " No relays connected, initiating connection...")

            relayRepository.connectToPersistentRelays()
            android.util.Log.i("RelayManager", " Started connecting to persistent relays")

            kotlinx.coroutines.delay(2000L)

            val relaysAfterConnection = relayRepository.getConnectedRelays()
            if (relaysAfterConnection.isNotEmpty()) {
                android.util.Log.i("RelayManager", " Connected to ${relaysAfterConnection.size} relay(s): ${relaysAfterConnection.joinToString()}")
            } else {
                android.util.Log.w("RelayManager", " No relays connected yet, but continuing anyway")
            }

            eventProcessor.startProcessing()
            android.util.Log.i("EventProcessor", " Started event processor on reconnect")
        } catch (e: Exception) {
            android.util.Log.e("RelayManager", " Failed to reconnect to relays: ${e.message}")
        } finally {
            reconnectMutex.withLock { isReconnecting = false }
        }
    }

    private companion object {
        /** Upper bound on waiting for the DeSent relay to connect and resolve NIP-42. */
        const val RELAY_READY_TIMEOUT_MS = 5000L
    }
}
