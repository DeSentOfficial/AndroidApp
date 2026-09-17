package xyz.desent.presentation.ui.profile

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import xyz.desent.crypto.Bech32Utils
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.data.RelayConfig
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.data.registration.model.RegistrationError
import xyz.desent.data.repository.NostrRepository
import xyz.desent.domain.model.AliasTierInfo
import xyz.desent.domain.model.User
import xyz.desent.domain.repository.AccountRepository
import xyz.desent.domain.repository.LogoutResult
import xyz.desent.domain.usecase.AliasUseCase
import xyz.desent.domain.usecase.AuthUseCase
import xyz.desent.domain.usecase.RegistrationUseCase
import xyz.desent.domain.usecase.UserUseCase
import xyz.desent.util.ImageCompressor

data class ProfileEditUiState(
    val displayName: String = "",
    val pictureUrl: String? = null,
    val newPictureUri: Uri? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    // Account details (key display + sign-out)
    val hexKey: String? = null,
    val npubKey: String? = null,
    val nsecKey: String? = null,
    val showLogoutDialog: Boolean = false,
    /**
     * Set after [logout] resolves. The screen reads this to decide where to
     * navigate: null until logout completes.
     */
    val logoutResult: LogoutResult? = null,
    // Tier hint for the "Roll Your Signing Key" card title.
    val tierInfo: AliasTierInfo? = null,
    /** Non-null when the active account was provisioned by a password login. */
    val custodialUsername: String? = null,
    val showPasswordChangeDialog: Boolean = false,
    val isChangingPassword: Boolean = false
)

class ProfileEditViewModel(
    private val userUseCase: UserUseCase,
    private val registrationUseCase: RegistrationUseCase,
    private val preferencesManager: PreferencesManager,
    private val nostrRepository: NostrRepository,
    private val secureKeyManager: SecureKeyManager,
    private val authUseCase: AuthUseCase,
    private val aliasUseCase: AliasUseCase,
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileEditUiState())
    val uiState: StateFlow<ProfileEditUiState> = _uiState.asStateFlow()

    private var originalUser: User? = null

    init {
        loadProfile()
        loadKeys()
        loadTierInfo()
        viewModelScope.launch { loadCustodialUsername() }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            val npub = preferencesManager.npubKey.firstOrNull()
            if (npub != null) {
                val user = userUseCase.getUserByNpub(npub)
                originalUser = user
                if (user != null) {
                    _uiState.value = ProfileEditUiState(
                        displayName = user.displayName ?: user.name ?: "",
                        pictureUrl = user.picture,
                        isLoading = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }

                // Refresh from the public profile relays (newest kind-0 wins) and
                // re-populate the form only if the user hasn't started editing.
                nostrRepository.fetchOwnProfileFromRelays(npub, RelayConfig.PUBLIC_PROFILE_RELAYS)
                val refreshed = userUseCase.getUserByNpub(npub)
                if (refreshed != null) {
                    val prev = originalUser
                    val prevDisplayName = prev?.displayName ?: prev?.name ?: ""
                    val state = _uiState.value
                    val pristine = state.displayName == prevDisplayName
                    // Always adopt non-editable baseline (nip05/banner) from the refresh.
                    originalUser = refreshed
                    if (pristine) {
                        _uiState.value = state.copy(
                            displayName = refreshed.displayName ?: refreshed.name ?: "",
                            pictureUrl = refreshed.picture
                        )
                    }
                }
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Not logged in")
            }
        }
    }

    fun onDisplayNameChange(v: String) { _uiState.value = _uiState.value.copy(displayName = v) }
    fun onPictureSelected(uri: Uri) { _uiState.value = _uiState.value.copy(newPictureUri = uri) }

    // ---------------- Account details (keys + sign-out) ----------------

    private fun loadKeys() {
        viewModelScope.launch {
            try {
                val identityResult = secureKeyManager.getIdentityFromStoredNSEC()
                identityResult.fold(
                    onSuccess = { identity ->
                        val hex = identity.publicKey.toHexString()
                        val npub = getNpubFromIdentity(identity)
                        val nsecResult = secureKeyManager.getNSECKey()

                        _uiState.value = _uiState.value.copy(
                            hexKey = hex,
                            npubKey = npub,
                            nsecKey = nsecResult.getOrNull()
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(error = "Failed to load keys: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Failed to load keys: ${e.message}")
            }
        }
    }

    private fun getNpubFromIdentity(identity: nostr.id.Identity): String? {
        return try {
            val hex = identity.publicKey.toHexString()
            Bech32Utils.hexToNpub(hex)
        } catch (e: Exception) {
            Log.e("ProfileEditViewModel", "Failed to convert to npub", e)
            null
        }
    }

    fun showLogoutDialog() {
        _uiState.value = _uiState.value.copy(showLogoutDialog = true)
    }

    fun dismissLogoutDialog() {
        _uiState.value = _uiState.value.copy(showLogoutDialog = false)
    }

    fun logout() {
        viewModelScope.launch {
            try {
                // authUseCase.logout() does everything: removes the active
                // account's key/data, and either auto-switches to the next
                // saved account (LogoutResult.Switched) or fully wipes state
                // (LogoutResult.FullyLoggedOut).
                val result = authUseCase.logout()

                _uiState.value = _uiState.value.copy(
                    showLogoutDialog = false,
                    logoutResult = result
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    showLogoutDialog = false,
                    error = "Failed to logout: ${e.message}"
                )
            }
        }
    }

    /** Called by the screen once it has acted on [ProfileEditUiState.logoutResult]. */
    fun consumeLogoutResult() {
        _uiState.value = _uiState.value.copy(logoutResult = null)
    }

    // ---------------- Tier hint + custodial password ----------------

    /** Tier for the "Roll Your Signing Key" card title (wizard self-gates). */
    private fun loadTierInfo() {
        viewModelScope.launch {
            val tier = aliasUseCase.getTierInfo().getOrNull()
            if (tier != null) {
                _uiState.value = _uiState.value.copy(tierInfo = tier)
            }
        }
    }

    /** Which username (if any) provisioned the active account via password login. */
    private suspend fun loadCustodialUsername() {
        try {
            val npub = preferencesManager.getActiveNpub() ?: return
            val username = accountRepository.getCustodialUsername(npub) ?: return
            _uiState.value = _uiState.value.copy(custodialUsername = username)
        } catch (e: Exception) {
            Log.d("ProfileEditViewModel", "No custodial username for active account")
        }
    }

    fun showPasswordChangeDialog() {
        _uiState.value = _uiState.value.copy(showPasswordChangeDialog = true, error = null)
    }

    fun dismissPasswordChangeDialog() {
        _uiState.value = _uiState.value.copy(
            showPasswordChangeDialog = false,
            isChangingPassword = false
        )
    }

    /**
     * Change the custodial password for the active account. The key itself
     * never changes — this device's stored copy stays valid; other devices
     * must re-login with the new password (their cached blob is stale).
     */
    fun changeCustodialPassword(oldPassword: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isChangingPassword = true, error = null)
            authUseCase.changeCustodialPassword(oldPassword, newPassword).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isChangingPassword = false,
                        showPasswordChangeDialog = false
                    )
                },
                onFailure = { e ->
                    val message = when (e) {
                        is RegistrationError.InvalidCredentials ->
                            "Current password is incorrect"
                        is RegistrationError.AccountLocked ->
                            "Too many failed attempts — account temporarily locked"
                        is RegistrationError.RateLimited ->
                            "Too many attempts — try again later"
                        is RegistrationError.NotCustodial ->
                            "This account has no password"
                        else -> "Password change failed: ${e.message}"
                    }
                    _uiState.value = _uiState.value.copy(
                        isChangingPassword = false,
                        error = message
                    )
                }
            )
        }
    }

    fun save(context: Context) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, error = null)
            try {
                // 1. Upload picture if changed
                var pictureUrl = state.pictureUrl
                val newPicUri = state.newPictureUri
                if (newPicUri != null) {
                    val compressed = compressImage(context, newPicUri)
                    if (compressed != null) {
                        val uploadResult = registrationUseCase.uploadProfilePicture(
                            compressed.data, compressed.mimeType, compressed.fileName
                        )
                        uploadResult.onSuccess { url -> pictureUrl = url }
                    }
                }

                // 2. Update server profile (PUT /api/profile). About/website/
                // lud16 are no longer edited here — pass the stored originals
                // through so nothing is blanked.
                registrationUseCase.updateProfile(
                    displayName = state.displayName.ifBlank { null },
                    about = originalUser?.about,
                    picture = pictureUrl,
                    website = originalUser?.website
                )

                // 3. Publish kind-0 (Nostr profile) with all fields
                nostrRepository.publishUserProfile(
                    name = state.displayName.lowercase().replace(" ", "_").ifBlank { null },
                    displayName = state.displayName.ifBlank { null },
                    about = originalUser?.about,
                    picture = pictureUrl,
                    nip05 = originalUser?.nip05,
                    website = originalUser?.website,
                    lud16 = originalUser?.lud16,
                    banner = originalUser?.banner
                )

                // 4. Update local DB
                val npub = preferencesManager.npubKey.firstOrNull()
                if (npub != null) {
                    val updated = originalUser?.copy(
                        displayName = state.displayName.ifBlank { null },
                        picture = pictureUrl
                    )
                    if (updated != null) {
                        userUseCase.saveUser(updated)
                    }
                }

                _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = "Save failed: ${e.message}")
            }
        }
    }

    private data class CompressedImage(val data: ByteArray, val mimeType: String, val fileName: String)

    private fun compressImage(context: Context, uri: Uri): CompressedImage? {
        return try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            val compressed = ImageCompressor.compressImage(bitmap, maxDimension = 1024, quality = 85)
            bitmap.recycle()
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val fileName = uri.lastPathSegment ?: "profile.jpg"
            CompressedImage(compressed, mimeType, fileName)
        } catch (e: Exception) { null }
    }

}
