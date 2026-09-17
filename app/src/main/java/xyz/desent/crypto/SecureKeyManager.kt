package xyz.desent.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nostr.id.Identity
import nostr.crypto.bech32.Bech32

class SecureKeyManager(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "SecureKeyManager"
    }
    
    private var isEncryptedMode = true
    
    private val masterKeyAlias: String? = try {
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Failed to create master key", e)
        null
    }
    
    private val encryptedPrefs: SharedPreferences = try {
        if (masterKeyAlias != null) {
            EncryptedSharedPreferences.create(
                "nostr_secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } else {
            throw Exception("No master key available")
        }
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Failed to create encrypted prefs: ${e.message}", e)
        
        try {
            android.util.Log.w(TAG, "Clearing corrupted encrypted preferences...")
            context.deleteSharedPreferences("nostr_secure_prefs")
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            android.util.Log.d(TAG, "Cleared corrupted encrypted preferences successfully")
        } catch (clearError: Exception) {
            android.util.Log.e(TAG, "Failed to clear corrupted preferences", clearError)
        }
        
        isEncryptedMode = false
        android.util.Log.w(TAG, "Falling back to unencrypted preferences")
        context.getSharedPreferences("nostr_secure_prefs_unencrypted", Context.MODE_PRIVATE)
    }
    
    fun isUsingEncryptedMode(): Boolean = isEncryptedMode
    
    suspend fun attemptReEncryption(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d(TAG, "Attempting to re-enable encrypted mode")
                
                val existingNsec = getNSECKey().getOrNull()

                context.deleteSharedPreferences("nostr_secure_prefs_unencrypted")

                isEncryptedMode = true
                android.util.Log.d(TAG, "Re-enabled encrypted mode successfully")

                existingNsec?.let { storeNSECKey(it, false) }
                
                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to re-enable encrypted mode", e)
                Result.failure(e)
            }
        }
    }
    
    suspend fun storeNSECKey(nsec: String, requireBiometrics: Boolean): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Derive npub so this key is also indexed per-account.
                val npub = deriveNpub(nsec)
                encryptedPrefs.edit()
                    .putString("nsec_key", nsec)
                    .putBoolean("require_biometrics", requireBiometrics)
                    .apply()
                if (npub != null) {
                    encryptedPrefs.edit()
                        .putString("nsec::$npub", nsec)
                        .putBoolean("biometric::$npub", requireBiometrics)
                        .apply()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getNSECKey(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val nsec = encryptedPrefs.getString("nsec_key", null)
                ?: return@withContext Result.failure(Exception("No NSEC key stored"))
            Result.success(nsec)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requiresBiometrics(): Boolean = withContext(Dispatchers.IO) {
        encryptedPrefs.getBoolean("require_biometrics", false)
    }

    suspend fun hasNSECKey(): Boolean = withContext(Dispatchers.IO) {
        encryptedPrefs.contains("nsec_key")
    }

    suspend fun deleteNSECKey(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Remove only the legacy active-account slots; per-account keys under
            // "nsec::<npub>" are owned by deleteAccountKey(npub). Clearing the
            // entire file here (the previous behaviour) would have wiped every
            // saved account on logout.
            encryptedPrefs.edit()
                .remove("nsec_key")
                .remove("require_biometrics")
                .apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== MULTI-ACCOUNT API ====================

    /**
     * Store (or replace) the nsec for a specific account, keyed by its npub.
     * Also mirrors into the legacy single-slot keys so that existing callers
     * using [getNSECKey] / [getIdentityFromStoredNSEC] see the most recently
     * stored account as the "active" one — preserving backwards compatibility
     * during the multi-account rollout.
     */
    suspend fun storeNSECForAccount(
        npub: String,
        nsec: String,
        requireBiometrics: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            encryptedPrefs.edit()
                .putString("nsec::$npub", nsec)
                .putBoolean("biometric::$npub", requireBiometrics)
                .putString("nsec_key", nsec)
                .putBoolean("require_biometrics", requireBiometrics)
                .apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getNSECForAccount(npub: String): Result<String> = withContext(Dispatchers.IO) {
        val nsec = encryptedPrefs.getString("nsec::$npub", null)
            ?: return@withContext Result.failure(Exception("No NSEC stored for account $npub"))
        Result.success(nsec)
    }

    suspend fun getIdentityForAccount(npub: String): Result<Identity> = withContext(Dispatchers.IO) {
        try {
            val nsec = encryptedPrefs.getString("nsec::$npub", null)
                ?: return@withContext Result.failure(Exception("No NSEC stored for account $npub"))
            val hex = Bech32Utils.nsecToHex(nsec)
            Result.success(Identity.create(hex))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun hasAccount(npub: String): Boolean = withContext(Dispatchers.IO) {
        encryptedPrefs.contains("nsec::$npub")
    }

    suspend fun requiresBiometricsForAccount(npub: String): Boolean = withContext(Dispatchers.IO) {
        encryptedPrefs.getBoolean("biometric::$npub", false)
    }

    /**
     * Remove one account's key material. Does not disturb the legacy active
     * slot unless this account was the active one — in which case callers
     * should follow up with [deleteNSECKey].
     */
    suspend fun deleteAccountKey(npub: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            encryptedPrefs.edit()
                .remove("nsec::$npub")
                .remove("biometric::$npub")
                .apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Enumerate saved account npubs (in insertion order of the prefs file,
     * which is not guaranteed to be stable — callers must sort as needed).
     */
    suspend fun listAccountNpubs(): List<String> = withContext(Dispatchers.IO) {
        encryptedPrefs.all.keys
            .filter { it.startsWith("nsec::") }
            .map { it.removePrefix("nsec::") }
    }

    /**
     * Promote a saved account to the active slot. Mirrors the per-account nsec
     * into the legacy `"nsec_key"` slot so all existing single-slot consumers
     * (NIP-42 auth, NIP-44 encryption, NIP-98 HTTP auth, gift-wrap, etc.) see
     * the newly-active identity without each one needing to learn about
     * multiple accounts.
     */
    suspend fun setActiveAccount(npub: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val nsec = encryptedPrefs.getString("nsec::$npub", null)
                ?: return@withContext Result.failure(Exception("No stored account for $npub"))
            val biometric = encryptedPrefs.getBoolean("biometric::$npub", false)
            encryptedPrefs.edit()
                .putString("nsec_key", nsec)
                .putBoolean("require_biometrics", biometric)
                .apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One-shot migration helper: if the legacy single-slot nsec is present but
     * has not yet been mirrored into the per-account store, mirror it. Returns
     * the npub that was seeded (or null if nothing to seed).
     */
    suspend fun seedPerAccountStoreIfMissing(): String? = withContext(Dispatchers.IO) {
        val legacyNsec = encryptedPrefs.getString("nsec_key", null) ?: return@withContext null
        val npub = deriveNpub(legacyNsec) ?: return@withContext null
        if (!encryptedPrefs.contains("nsec::$npub")) {
            val biometric = encryptedPrefs.getBoolean("require_biometrics", false)
            encryptedPrefs.edit()
                .putString("nsec::$npub", legacyNsec)
                .putBoolean("biometric::$npub", biometric)
                .apply()
        }
        npub
    }

    // ==================== PGP KEY MIRROR ====================

    /**
     * Keystore-backed device mirror of the account's PGP key payload
     * (kind-30078 `desent:pgp` content JSON). The 30078 event remains the
     * roaming source of truth; this copy makes the key available at cold
     * start before the relay subscription replays (ANDROID_PGP.md §2.1).
     */
    suspend fun putPgpKeyBlob(npub: String, payloadJson: String) = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().putString("pgp::$npub", payloadJson).apply()
    }

    suspend fun getPgpKeyBlob(npub: String): String? = withContext(Dispatchers.IO) {
        encryptedPrefs.getString("pgp::$npub", null)
    }

    suspend fun clearPgpKeyBlob(npub: String) = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().remove("pgp::$npub").apply()
    }

    private suspend fun deriveNpub(nsec: String): String? = withContext(Dispatchers.Default) {
        try {
            val hex = Bech32Utils.nsecToHex(nsec)
            val identity = Identity.create(hex)
            Bech32Utils.hexToNpub(identity.publicKey.toHexString())
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun validateNSEC(nsec: String): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val hex = Bech32Utils.nsecToHex(nsec)

                val identity = Identity.create(hex)

                val hexString = identity.publicKey.toHexString()

                val npub = Bech32Utils.hexToNpub(hexString)
                Result.success(npub)
            } catch (e: Exception) {
                android.util.Log.e("SecureKeyManager", "NSEC validation failed: ${e.message}", e)
                Result.failure(Exception("Invalid NSEC format: ${e.message}"))
            }
        }
    }

    suspend fun generateKeyPair(): Result<Pair<String, String>> = withContext(Dispatchers.Default) {
        try {
            android.util.Log.d("SecureKeyManager", "Generating new keypair")

            val random = java.security.SecureRandom()
            val privateKeyBytes = ByteArray(32)
            random.nextBytes(privateKeyBytes)
            val privateKeyHex = privateKeyBytes.joinToString("") { "%02x".format(it) }
            privateKeyBytes.fill(0)

            val identity = Identity.create(privateKeyHex)
            val publicKeyHex = identity.publicKey.toHexString()

            val nsec = Bech32Utils.hexToNsec(privateKeyHex)
            val npub = Bech32Utils.hexToNpub(publicKeyHex)

            Result.success(Pair(nsec, npub))
        } catch (e: Exception) {
            android.util.Log.e("SecureKeyManager", "Failed to generate keypair: ${e.message}", e)
            Result.failure(Exception("Failed to generate keypair: ${e.message}"))
        }
    }

    suspend fun getIdentityFromStoredNSEC(): Result<Identity> = withContext(Dispatchers.IO) {
        try {
            val nsec = encryptedPrefs.getString("nsec_key", null)
                ?: return@withContext Result.failure(Exception("No NSEC key stored"))

            // Decode NSEC (Bech32) to hex first
            val hex = Bech32Utils.nsecToHex(nsec)

            val identity = Identity.create(hex)
            Result.success(identity)
        } catch (e: Exception) {
            android.util.Log.e("SecureKeyManager", "Failed to get identity from stored NSEC: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    suspend fun migrateFromOldStorage(oldKeyManager: SimpleKeyManager): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val oldNsec = oldKeyManager.getNSECKey().getOrNull() ?: return@withContext false
                storeNSECKey(oldNsec, requireBiometrics = false)
                oldKeyManager.deleteNSECKey()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
