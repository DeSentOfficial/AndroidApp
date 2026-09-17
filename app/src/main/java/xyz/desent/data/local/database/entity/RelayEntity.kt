package xyz.desent.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "relays")
data class RelayEntity(
    @PrimaryKey
    val url: String,
    val isActive: Boolean = true,
    val connectionStatus: String, // "CONNECTED", "CONNECTING", "DISCONNECTED", "ERROR"
    val failureCount: Int = 0,
    val lastConnectedAt: Long?,
    val createdAt: Long = System.currentTimeMillis(),
    val isWrite: Boolean = false,
    val isPersistent: Boolean = true,
    val nip11Name: String?,
    val nip11Description: String?,
    val nip11Pubkey: String?,
    val nip11Contact: String?,
    val nip11SupportedNips: String?,  // JSON array
    val nip11Version: String?,
    val nip11Icon: String?,
    val nip11Software: String?,
    val nip11RelayCountries: String?,  // JSON array
    val nip11LanguageTags: String?,   // JSON array
    val nip11PostingPolicy: String?,
    val nip11LimitationsJson: String?,  // JSON object
    val nip11FeesJson: String?,        // JSON object
    val nip11Payments: String?,
    val nip11CachedAt: Long?
)