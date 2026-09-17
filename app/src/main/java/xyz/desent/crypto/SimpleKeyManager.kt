package xyz.desent.crypto

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LEGACY key storage — read-only migration source.
 *
 * Very early builds kept the nsec in plain SharedPreferences under the
 * `nostr_prefs` name. This class exists ONLY so
 * [SecureKeyManager.migrateFromOldStorage] can find such a key once and move
 * it into the Android Keystore (after which it is deleted from prefs).
 *
 * Nothing may write through this class, and no new code should read from it.
 * New keys always go through [SecureKeyManager].
 */
class SimpleKeyManager(
    private val context: Context
) {

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("nostr_prefs", Context.MODE_PRIVATE)

    suspend fun getNSECKey(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val nsec = sharedPrefs.getString("nsec_key", null)
            if (nsec != null) {
                Result.success(nsec)
            } else {
                Result.failure(Exception("No NSEC key stored"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteNSECKey(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sharedPrefs.edit()
                .remove("nsec_key")
                .apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
