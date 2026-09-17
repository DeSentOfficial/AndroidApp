package xyz.desent.presentation.ui.sign.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import xyz.desent.crypto.NostrSigner
import xyz.desent.crypto.SecureKeyManager
import xyz.desent.domain.model.SignRequest
import xyz.desent.domain.model.SignedEventResponse
import xyz.desent.domain.model.UnsignedNostrEvent

class SignRequestViewModel(
    private val secureKeyManager: SecureKeyManager
) : ViewModel() {

    companion object {
        private const val TAG = "SignRequestViewModel"
        private const val MAX_REQUESTS_PER_MINUTE = 10
    }

    private val requestTimestamps = mutableListOf<Long>()

    fun validateRequest(request: SignRequest): ValidationResult {
        val now = System.currentTimeMillis()

        requestTimestamps.add(now)
        requestTimestamps.removeAll { it -> now - it > 60000 }

        if (requestTimestamps.size > MAX_REQUESTS_PER_MINUTE) {
            return ValidationResult.RateLimited
        }

        if (request.callbackScheme.isBlank()) {
            return ValidationResult.InvalidCallback
        }

        return ValidationResult.Valid
    }

    fun signEvent(request: SignRequest, onResult: (SignedEventResponse?) -> Unit) {
        viewModelScope.launch {
            try {
                val identity = secureKeyManager.getIdentityFromStoredNSEC().getOrNull()
                if (identity == null) {
                    Log.e(TAG, "No private key available")
                    onResult(null)
                    return@launch
                }

                val signed = NostrSigner.sign(request.unsignedEvent, identity).getOrNull()
                if (signed == null) {
                    Log.e(TAG, "Failed to sign event")
                    onResult(null)
                    return@launch
                }

                onResult(
                    SignedEventResponse(
                        eventId = signed.id,
                        pubkey = signed.pubkey,
                        signature = signed.signature,
                        serializedEvent = signed.serialized
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sign event", e)
                onResult(null)
            }
        }
    }
}

sealed class ValidationResult {
    object Valid : ValidationResult()
    object RateLimited : ValidationResult()
    object InvalidCallback : ValidationResult()
}

