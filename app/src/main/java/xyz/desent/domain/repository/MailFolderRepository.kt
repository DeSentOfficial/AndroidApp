package xyz.desent.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.MailFolder
import xyz.desent.domain.model.MailStateEntry

/**
 * Mail folders + synced read state (refs/FROM_email.desent.xyz/
 * ANDROID_MAIL_FOLDERS.md). A client-owned overlay over kind-1010 mail,
 * published as NIP-44-to-self kind 30078 namespaces (`desent:mail-folders`
 * manifest, `desent:mail-state:<i>` shards) — the relay stores only
 * ciphertext. Labels model: filed mail still shows in All mail; a folder is
 * an additional saved view; one folder per message.
 */
interface MailFolderRepository {

    /** The folder tree for [ownerNpub], ordered for display (roots first, then by name). */
    fun observeFolders(ownerNpub: String): Flow<List<MailFolder>>

    /** The full per-message overlay map (pinned key → entry) for [ownerNpub]. */
    fun observeState(ownerNpub: String): Flow<Map<String, MailStateEntry>>

    /**
     * Create a folder under [parent] (null = root). Republishes the whole
     * manifest. Fails on a blank/slashy name or an unknown parent.
     */
    suspend fun createFolder(ownerNpub: String, name: String, parent: String? = null): Result<MailFolder>

    /** Rename in place; assignments (by id) are untouched. Republishes the manifest. */
    suspend fun renameFolder(ownerNpub: String, id: String, newName: String): Result<Unit>

    /**
     * Delete a folder. Only the assignments go: filed mail stays in All mail
     * (each assignment is cleared by an unfile tombstone so other devices
     * converge). Republishes the manifest.
     */
    suspend fun deleteFolder(ownerNpub: String, id: String): Result<Unit>

    /**
     * File one message into [folderId], or unfile it (null). Local write
     * stamps `ts = max(now, prev + 1)` so it always wins the LWW merge; the
     * shard flushes debounced.
     */
    suspend fun moveToFolder(ownerNpub: String, messageKey: String, folderId: String?): Result<Unit>

    /**
     * Record a read/unread change in the overlay (cross-device source of
     * truth; the local `emails.isRead` column is the cache). No failure
     * path — a relay outage just leaves the shard dirty for the next flush.
     */
    suspend fun setRead(ownerNpub: String, messageKey: String, read: Boolean)

    /** Overlay-stamp every message of a thread (inbox mark-thread-read path). */
    suspend fun markThreadRead(ownerNpub: String, threadKey: String)

    /**
     * Publish dirty shards now (also drains the debounce). No-op when
     * nothing is dirty and [force] is false. Safe to call on app-background
     * and "Sync now".
     */
    suspend fun flush(force: Boolean = false): Result<Unit>
}
