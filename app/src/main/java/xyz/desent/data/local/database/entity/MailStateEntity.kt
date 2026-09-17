package xyz.desent.data.local.database.entity

import androidx.room.Entity

/**
 * One per-message overlay entry of the synced mail-state map (NIP-78 kind
 * 30078, `d = "desent:mail-state:<i>"`). Merged per-entry LWW by [ts]
 * (strictly greater wins); a local user action stamps
 * `ts = max(now, prev.ts + 1)` so it always wins.
 *
 * Entries may exist for mail not yet synced locally — they apply on arrival
 * (ingest consults the overlay), which is why this is a standalone table
 * rather than columns on `emails`.
 */
@Entity(tableName = "mail_state", primaryKeys = ["ownerNpub", "folderKey"])
data class MailStateEntity(
    val ownerNpub: String,
    /** Pinned message key: rumor `message_id` tag, else `"ev:" + wrap event id`. */
    val folderKey: String,
    /** Folder id, or null = unfiled (the tombstone clears assignments). */
    val folderId: String?,
    /** Synced cross-device read flag; the local `emails.isRead` is the cache. */
    val isRead: Boolean,
    /** Unix seconds — the LWW clock. */
    val ts: Long
)
