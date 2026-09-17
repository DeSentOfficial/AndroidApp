package xyz.desent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mail-folder wire primitives (refs/FROM_email.desent.xyz/
 * ANDROID_MAIL_FOLDERS.md): the shard hash must match the web client
 * byte-for-byte (UTF-16 code units, 32-bit multiply-add, mod 16).
 */
class MailFoldersTest {

    // ------------------------------------------------------------------
    // shardOf — vectors computed with JS semantics
    // (`h = (Math.imul(h, 31) + key.charCodeAt(i)) >>> 0`)
    // ------------------------------------------------------------------

    @Test
    fun shardOf_matchesWebVectors() {
        assertEquals(1, MailStateKeys.shardOf("a"))
        assertEquals(2, MailStateKeys.shardOf("b"))
        assertEquals(1, MailStateKeys.shardOf("ab"))
        assertEquals(2, MailStateKeys.shardOf("abc"))
        assertEquals(2, MailStateKeys.shardOf("hello"))
        // Exercises 32-bit wraparound (h overflows 2^32 on step 7;
        // JS-computed value: 312017024 % 16 = 0).
        assertEquals(0, MailStateKeys.shardOf("aaaaaaaa"))
        // Degenerate empty key hashes to 0 (never occurs in practice).
        assertEquals(0, MailStateKeys.shardOf(""))
    }

    @Test
    fun shardOf_isStableAndBounded() {
        val key = "<abc-123@example.com>"
        repeat(100) {
            val shard = MailStateKeys.shardOf(key)
            assertTrue(shard in 0 until MailStateKeys.SHARD_COUNT)
            assertEquals(shard, MailStateKeys.shardOf(key))
        }
    }

    // ------------------------------------------------------------------
    // Pinned keys
    // ------------------------------------------------------------------

    @Test
    fun pinnedKey_prefersMessageId() {
        assertEquals("mid-1@example.com", MailStateKeys.pinnedKey("mid-1@example.com", "wrapid"))
        assertEquals("ev:wrapid", MailStateKeys.pinnedKey(null, "wrapid"))
        // Blank message ids are treated as absent (defensive).
        assertEquals("ev:wrapid", MailStateKeys.pinnedKey("", "wrapid"))
        assertEquals("ev:wrapid", MailStateKeys.pinnedKey("  ", "wrapid"))
    }

    // ------------------------------------------------------------------
    // d-tag routing
    // ------------------------------------------------------------------

    @Test
    fun dTagRouting_exactManifestBeforeShardPrefix() {
        assertEquals("desent:mail-folders", MailStateKeys.FOLDERS_D_TAG)
        assertTrue(MailStateKeys.isStateShardDTag("desent:mail-state:0"))
        assertTrue(MailStateKeys.isStateShardDTag("desent:mail-state:15"))
        // The informational manifest must NOT be treated as a shard.
        assertFalse(MailStateKeys.isStateShardDTag("desent:mail-state:manifest"))
        assertFalse(MailStateKeys.isStateShardDTag("desent:mail-folders"))
        assertEquals("desent:mail-state:7", MailStateKeys.shardDTag(7))
    }

    // ------------------------------------------------------------------
    // Folder ids
    // ------------------------------------------------------------------

    @Test
    fun newFolderId_isOpaqueAndWellFormed() {
        repeat(50) {
            val id = MailStateKeys.newFolderId()
            assertTrue(id.matches(Regex("f_[0-9a-f]{10}")))
            assertFalse(MailStateKeys.isReservedFolderId(id))
        }
    }

    @Test
    fun reservedRoleWords_areRejected() {
        for (reserved in listOf("inbox", "sent", "drafts", "junk", "trash", "archive")) {
            assertTrue(MailStateKeys.isReservedFolderId(reserved))
            assertTrue(MailStateKeys.isReservedFolderId(reserved.uppercase()))
        }
        assertFalse(MailStateKeys.isReservedFolderId("f_1a2b3c4d5e"))
        assertFalse(MailStateKeys.isReservedFolderId("invoices"))
    }

    // ------------------------------------------------------------------
    // Manifest codec — round-trip rule for unknown keys
    // ------------------------------------------------------------------

    @Test
    fun codec_roundTripsUnknownKeysVerbatim() {
        val manifest = """
            {
              "folders": [
                { "id": "f_1a2b3c4d5e", "name": "Invoices", "parent": null,
                  "color": null, "sort": 0, "sync_rev": 3, "web_meta": {"pinned": true} }
              ],
              "updated_at": 1738200000
            }
        """.trimIndent()

        val folders = MailFoldersCodec.decode(manifest)
        assertEquals(1, folders.size)
        val folder = folders[0]
        assertEquals("f_1a2b3c4d5e", folder.id)
        assertEquals("Invoices", folder.name)
        assertEquals(null, folder.parent)
        assertEquals(0, folder.sort)

        val reencoded = MailFoldersCodec.encode(folders, 1L)
        assertTrue(reencoded.contains("\"sync_rev\":3"))
        assertTrue(reencoded.contains("\"web_meta\":{\"pinned\":true}"))
        // Re-decode is stable.
        assertEquals(folders, MailFoldersCodec.decode(reencoded))
    }

    @Test
    fun codec_decodeMalformedOrEmpty_isSafe() {
        assertEquals(emptyList<MailFolder>(), MailFoldersCodec.decode("{"))
        assertEquals(emptyList<MailFolder>(), MailFoldersCodec.decode("""{"updated_at":1}"""))
        assertEquals(emptyList<MailFolder>(), MailFoldersCodec.decode("""{"folders":[]}"""))
    }

    @Test
    fun codec_newEntry_encodesExpectedShape() {
        val entry = MailFoldersCodec.newEntry("f_0011223344", "Receipts", null)
        val manifest = MailFoldersCodec.encode(
            listOf(MailFolder("f_0011223344", "Receipts", null, null, 0, entry)),
            42L
        )
        assertTrue(manifest.contains("\"id\":\"f_0011223344\""))
        assertTrue(manifest.contains("\"name\":\"Receipts\""))
        assertTrue(manifest.contains("\"updated_at\":42"))
    }

    @Test
    fun displayPath_joinsParentChain_andFlattensCycles() {
        val root = MailFoldersCodec.decode(
            """{"folders":[{"id":"f_aaa","name":"Work","parent":null}]}"""
        ).single()
        val child = MailFoldersCodec.decode(
            """{"folders":[{"id":"f_bbb","name":"Projects","parent":"f_aaa"}]}"""
        ).single()
        val all = listOf(
            root,
            child,
            // Cycle: c -> d -> c (must flatten, not loop).
            MailFolder("f_c", "C", "f_d", null, 0, MailFoldersCodec.newEntry("f_c", "C", "f_d")),
            MailFolder("f_d", "D", "f_c", null, 0, MailFoldersCodec.newEntry("f_d", "D", "f_c"))
        )
        assertEquals("Work", root.displayPath(all))
        assertEquals("Work/Projects", child.displayPath(all))
        // Cycle renders without hanging; each folder appears once.
        val cPath = all.first { it.id == "f_c" }.displayPath(all)
        assertTrue(cPath == "C/D" || cPath == "D/C")
    }
}
