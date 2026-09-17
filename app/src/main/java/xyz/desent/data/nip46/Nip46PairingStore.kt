package xyz.desent.data.nip46

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.security.MessageDigest

/**
 * Persisted NIP-46 pairing records, held in EncryptedSharedPreferences
 * (co-located with the user's other sensitive material) as an account-scoped
 * JSON list. The QR handshake [Nip46Pairing.pairingSecretHash] is stored only
 * as a sha256 hash for audit — never reused, never the raw secret.
 *
 * Exposes [pairings] (all records, all accounts) reactively; UI filters by the
 * active user pubkey. Each write re-emits the full list.
 */
class Nip46PairingStore(context: Context) {

    private val prefs = openPrefs(context.applicationContext)
    private val serializer = ListSerializer(Nip46Pairing.serializer())

    private val _pairings = MutableStateFlow<List<Nip46Pairing>>(emptyList())
    val pairings: StateFlow<List<Nip46Pairing>> = _pairings.asStateFlow()

    init {
        _pairings.value = readAll()
    }

    fun snapshotFor(userPubkey: String): List<Nip46Pairing> =
        _pairings.value.filter { it.userPubkey == userPubkey }

    fun findBySession(sessionPubkey: String): Nip46Pairing? =
        _pairings.value.firstOrNull { it.sessionPubkey == sessionPubkey }

    fun upsert(pairing: Nip46Pairing) {
        val updated = (_pairings.value.filterNot { it.sessionPubkey == pairing.sessionPubkey } + pairing)
        writeAll(updated)
    }

    fun remove(sessionPubkey: String) {
        writeAll(_pairings.value.filterNot { it.sessionPubkey == sessionPubkey })
    }

    /** Apply [transform] to a record and persist the result (for counters/revoked/etc.). */
    fun update(sessionPubkey: String, transform: (Nip46Pairing) -> Nip46Pairing) {
        val current = _pairings.value
        val idx = current.indexOfFirst { it.sessionPubkey == sessionPubkey }
        if (idx < 0) return
        val updated = current.toMutableList()
        updated[idx] = transform(updated[idx])
        writeAll(updated)
    }

    fun clearForUser(userPubkey: String) {
        writeAll(_pairings.value.filterNot { it.userPubkey == userPubkey })
    }

    // -----------------------------------------------------------------------

    private fun readAll(): List<Nip46Pairing> = try {
        val raw = prefs.getString(KEY_PAIRINGS, null) ?: return emptyList()
        nip46Json.decodeFromString(serializer, raw)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read pairings: ${e.message}", e)
        emptyList()
    }

    private fun writeAll(list: List<Nip46Pairing>) {
        try {
            val raw = nip46Json.encodeToString(serializer, list)
            prefs.edit().putString(KEY_PAIRINGS, raw).apply()
            _pairings.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write pairings: ${e.message}", e)
        }
    }

    private fun openPrefs(appContext: Context) = try {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "nip46_pairings",
            masterKey,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: KeyPermanentlyInvalidatedException) {
        Log.w(TAG, "Keystore key invalidated; falling back to plain prefs for pairings")
        appContext.getSharedPreferences("nip46_pairings_unencrypted", Context.MODE_PRIVATE)
    } catch (e: Exception) {
        Log.w(TAG, "Encrypted prefs unavailable; falling back to plain prefs: ${e.message}")
        appContext.getSharedPreferences("nip46_pairings_unencrypted", Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "Nip46PairingStore"
        private const val KEY_PAIRINGS = "pairings_json"

        fun secretHash(secret: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
