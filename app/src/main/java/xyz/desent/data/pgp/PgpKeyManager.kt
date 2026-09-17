package xyz.desent.data.pgp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.OpenPgpCrypto
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.domain.model.PgpDecryptFailure
import xyz.desent.domain.model.PgpKeyInfo
import xyz.desent.domain.model.PgpKeyPayload
import xyz.desent.domain.model.PgpKeyState
import xyz.desent.domain.repository.PrivateStorageRepository

/**
 * Implements [xyz.desent.domain.repository.PgpKeyRepository]: the single
 * owner of the account's OpenPGP identity.
 *
 * Custody (ANDROID_PGP.md §2.1, do not deviate):
 *  - private key is persisted ONLY as the kind-30078 `desent:pgp` event
 *    (NIP-44-to-self, the roaming source of truth) plus a Keystore-backed
 *    encrypted-preferences mirror for cold-start availability;
 *  - the private half is never sent to any HTTP endpoint;
 *  - removal = 30078 empty-content tombstone AND `DELETE /api/pgp/key`.
 *
 * The in-memory state also feeds send/read pipelines (encrypt-at-send,
 * decrypt-at-read, spam scoring of decrypted bodies).
 */
class PgpKeyManager(
    private val privateStorageRepository: PrivateStorageRepository,
    private val secureKeyManager: SecureKeyManager,
    private val pgpClient: PgpClient
) : xyz.desent.domain.repository.PgpKeyRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** Guards generate/import/remove against concurrent key operations. */
    private val mutationMutex = Mutex()

    /** Decrypted private armor for the active key; null until loaded. */
    @Volatile
    private var cachedSecretArmored: String? = null

    private val _keyState = MutableStateFlow<PgpKeyState>(PgpKeyState.NoKey)
    override val keyState: StateFlow<PgpKeyState> = _keyState.asStateFlow()

    init {
        // Cold start: device mirror first (instant), then keep in sync with
        // the 30078 subscription (own echo + other-device writes).
        scope.launch { loadFromMirror() }
        scope.launch {
            privateStorageRepository.pgpKeyFlow.collect { payload ->
                if (payload == null) {
                    cachedSecretArmored = null
                    _keyState.value = PgpKeyState.NoKey
                    clearMirror()
                } else {
                    applyPayload(payload)
                    writeMirror(payload)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Key lifecycle
    // ------------------------------------------------------------------

    override suspend fun generateKey(displayName: String, email: String): Result<PgpKeyInfo> =
        mutationMutex.withLock {
            withContext(Dispatchers.Default) {
                runCatching {
                    val uid = "${displayName.ifBlank { "DeSent user" }} <$email>"
                    val material = OpenPgpCrypto.generate(uid)
                    persist(material, source = SOURCE_GENERATED)
                }
            }
        }

    override suspend fun importKey(secretArmored: String, passphrase: String?): Result<PgpKeyInfo> =
        mutationMutex.withLock {
            withContext(Dispatchers.Default) {
                OpenPgpCrypto.importAndReArmor(
                    secretArmored,
                    passphrase?.toCharArray()
                ).mapCatching { persist(it, source = SOURCE_IMPORTED) }
            }
        }

    override suspend fun removeKey(): Result<Unit> = mutationMutex.withLock {
        val owner = activeNpub() ?: return Result.failure(Exception("No active identity"))
        val result = privateStorageRepository.removePgpKey()
        // Unpublish WKD even if the tombstone publish failed halfway — the
        // custody contract requires both sides attempted.
        val delete = pgpClient.deleteKey()
        if (delete.isFailure && delete.exceptionOrNull() !is xyz.desent.data.pgp.model.PgpError.NoPgpKey) {
            Log.w(TAG, "DELETE /api/pgp/key failed during removal: ${delete.exceptionOrNull()?.message}")
        }
        secureKeyManager.clearPgpKeyBlob(owner)
        cachedSecretArmored = null
        _keyState.value = PgpKeyState.NoKey
        result
    }

    // ------------------------------------------------------------------
    // Discovery + crypto operations
    // ------------------------------------------------------------------

    override suspend fun hasRecipientKey(email: String): Boolean =
        pgpClient.lookupRecipientKey(email).getOrNull() != null

    override suspend fun encryptForRecipient(email: String, plaintext: String): Result<String> =
        withContext(Dispatchers.Default) {
            val armoredKey = pgpClient.lookupRecipientKey(email).getOrNull()
                ?: return@withContext Result.failure(
                    Exception("No PGP key published for this recipient")
                )
            OpenPgpCrypto.encrypt(plaintext, armoredKey)
        }

    override suspend fun decryptMessage(armor: String): Result<ByteArray> {
        val secret = cachedSecretArmored
            ?: return Result.failure(PgpDecryptFailure.NO_KEY.toException())
        return withContext(Dispatchers.Default) {
            OpenPgpCrypto.decrypt(armor, secret).recoverCatching {
                throw PgpDecryptFailure.WRONG_KEY.toException()
            }
        }
    }

    override suspend fun fetchPublishedKey(): Result<PgpKeyInfo?> =
        pgpClient.getKey().map { response ->
            PgpKeyInfo(
                fingerprint = response.fingerprint ?: "",
                source = "",
                publicArmored = response.publicKey
            )
        }.recoverCatching {
            if (it is xyz.desent.data.pgp.model.PgpError.NoPgpKey) null else throw it
        }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Persist a fresh key: 30078 publish (source of truth) → WKD PUT →
     * local state + encrypted mirror. A WKD failure does NOT roll the key
     * back — the settings UI surfaces the unpublished state for a retry.
     */
    private suspend fun persist(material: OpenPgpCrypto.KeyMaterial, source: String): PgpKeyInfo {
        val payload = PgpKeyPayload(
            privateKeyArmored = material.secretArmored,
            publicKeyArmored = material.publicArmored,
            fingerprint = material.fingerprint,
            source = source
        )
        privateStorageRepository.savePgpKey(payload).getOrThrow()

        val putResult = pgpClient.putKey(material.publicArmored)
        if (putResult.isFailure) {
            Log.w(TAG, "PUT /api/pgp/key failed (key stored, WKD unpublished): ${putResult.exceptionOrNull()?.message}")
        }

        applyPayload(payload)
        writeMirror(payload)

        // Sanity: the fingerprint the relay reports should match ours.
        putResult.getOrNull()?.fingerprint?.takeIf { it.isNotBlank() && it != material.fingerprint }?.let {
            Log.w(TAG, "Relay fingerprint mismatch: local=${material.fingerprint} relay=$it")
        }

        return _keyState.value.let { state ->
            (state as? PgpKeyState.Available)?.info
                ?: PgpKeyInfo(material.fingerprint, source, material.publicArmored)
        }
    }

    private fun applyPayload(payload: PgpKeyPayload) {
        cachedSecretArmored = payload.privateKeyArmored
        _keyState.value = PgpKeyState.Available(
            PgpKeyInfo(
                fingerprint = payload.fingerprint,
                source = payload.source,
                publicArmored = payload.publicKeyArmored
            )
        )
    }

    private suspend fun activeNpub(): String? {
        val hex = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()?.publicKey?.toHexString()
        return hex?.let { runCatching { Bech32Utils.hexToNpub(it) }.getOrNull() }
    }

    private suspend fun loadFromMirror() {
        try {
            val owner = activeNpub() ?: return
            val blob = secureKeyManager.getPgpKeyBlob(owner) ?: return
            val payload = json.decodeFromString(PgpKeyPayload.serializer(), blob)
            // The 30078 flow (LWW) will correct any drift; the mirror only
            // primes availability at cold start.
            if (_keyState.value is PgpKeyState.NoKey) {
                applyPayload(payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PGP mirror load failed: ${e.message}")
        }
    }

    private suspend fun writeMirror(payload: PgpKeyPayload) {
        try {
            val owner = activeNpub() ?: return
            secureKeyManager.putPgpKeyBlob(owner, json.encodeToString(PgpKeyPayload.serializer(), payload))
        } catch (e: Exception) {
            Log.w(TAG, "PGP mirror write failed: ${e.message}")
        }
    }

    private suspend fun clearMirror() {
        try {
            val owner = activeNpub() ?: return
            secureKeyManager.clearPgpKeyBlob(owner)
        } catch (e: Exception) {
            Log.w(TAG, "PGP mirror clear failed: ${e.message}")
        }
    }

    private fun PgpDecryptFailure.toException(): Exception =
        Exception(
            when (this) {
                PgpDecryptFailure.NO_KEY -> DECRYPT_NO_KEY_MESSAGE
                PgpDecryptFailure.WRONG_KEY -> DECRYPT_WRONG_KEY_MESSAGE
                PgpDecryptFailure.FEATURE_DISABLED -> "PGP is disabled on this relay"
            }
        )

    companion object {
        private const val TAG = "PgpKeyManager"
        private const val SOURCE_GENERATED = "generated"
        private const val SOURCE_IMPORTED = "imported"

        /** Distinguishable markers for decrypt-failure classification. */
        const val DECRYPT_NO_KEY_MESSAGE = "pgp: no key on this account"
        const val DECRYPT_WRONG_KEY_MESSAGE = "pgp: could not decrypt with this account's key"
    }
}
