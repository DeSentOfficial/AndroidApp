package xyz.desent.data.mapper

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.desent.domain.model.AttachmentMeta
import xyz.desent.domain.model.NotePayload
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.PrivateNote

class PrivateStorageMapperTest {

    private val mapper = PrivateStorageMapper()

    private val metaA = AttachmentMeta(
        sha256 = "sha-a", keyHex = "ka", nonceHex = "na",
        mimeType = "image/png", filename = "a.png", size = 1L
    )
    private val metaB = AttachmentMeta(
        sha256 = "sha-b", keyHex = "kb", nonceHex = "nb",
        mimeType = "image/png", filename = "b.png", size = 2L
    )

    @Test
    fun note_roundTripsThroughEntity() {
        val note = PrivateNote(
            id = "550e8400",
            ownerNpub = "npub1abc",
            title = "Verify codes for Instagram",
            body = "847291 expires soon",
            updatedAt = 1738200000L,
            folder = "Work/Accounts",
            attachments = listOf(metaA, metaB),
            dTag = "desent:note:550e8400"
        )
        val entity = mapper.mapNoteToEntity(note, createdAt = 100L)
        val restored = mapper.mapNoteToDomain(entity)
        assertEquals(note, restored)
    }

    @Test
    fun notePayload_carriesAllFields() {
        val note = PrivateNote(
            id = "x", ownerNpub = "npub1", title = "t", body = "b",
            updatedAt = 5L, folder = "Work", attachments = listOf(metaA), dTag = "desent:note:x"
        )
        val payload = mapper.notePayload(note)
        assertEquals("t", payload.title)
        assertEquals("b", payload.body)
        assertEquals(5L, payload.updated_at)
        assertEquals("Work", payload.folder)
        assertEquals(listOf(metaA), payload.attachments)
    }

    @Test
    fun notePayload_serializesToSpecShape_withFolderAndRichAttachments() {
        val note = PrivateNote(
            id = "x", ownerNpub = "npub1", title = "t", body = "b",
            updatedAt = 5L, folder = "Work/Projects",
            attachments = listOf(metaA), dTag = "desent:note:x"
        )
        val json = Json { ignoreUnknownKeys = true }
            .encodeToString(NotePayload.serializer(), mapper.notePayload(note))
        // Spec field names: folder + key_hex/nonce_hex/mime_type.
        assertEquals(true, json.contains("\"folder\":\"Work/Projects\""))
        assertEquals(true, json.contains("\"key_hex\":\"ka\""))
        assertEquals(true, json.contains("\"nonce_hex\":\"na\""))
        assertEquals(true, json.contains("\"mime_type\":\"image/png\""))
    }

    @Test
    fun notePayload_decodesLegacyStringAttachments() {
        val legacy = """{"title":"t","body":"b","updated_at":5,"attachments":["sha-old"]}"""
        val payload = Json { ignoreUnknownKeys = true }
            .decodeFromString(NotePayload.serializer(), legacy)
        assertEquals(1, payload.attachments.size)
        assertEquals("sha-old", payload.attachments[0].sha256)
        // Legacy stub: no key/nonce → not downloadable, but it survives decoding.
        assertEquals("", payload.attachments[0].keyHex)
    }

    @Test
    fun contacts_v2RoundTripPreservesAllFieldsAndUnknownKeys() {
        val json = """
            [
              {"name":"Ann Lee",
               "emails":[{"label":"work","value":"ann@corp.example"},{"label":"home","value":"ann@home.example"}],
               "phones":[{"label":"mobile","value":"+1 555 010 9999"}],
               "wallets":[{"label":"","value":"ann@strike.army","network":"lightning"}],
               "anniversaries":[{"label":"Birthday","date":"1990-05-12"}],
               "pubkey":"39c83a20032ee04e641cf48996988c6c34c8d0bd60f86dc3c63866dabf5d431e",
               "domain":"corp.example",
               "notes":"Met at the Oslo meetup",
               "future_field":{"nested":true}},
              {"name":"NoEmail"}
            ]
        """.trimIndent()
        val restored = mapper.contactsToDomain(
            xyz.desent.data.local.database.entity.PrivateContactsEntity(
                ownerNpub = "npub1", contactsJson = json, updatedAt = 1L, dTag = "desent:contacts", createdAt = 1L
            )
        )

        assertEquals(2, restored.size)
        val ann = restored[0]
        assertEquals("Ann Lee", ann.name)
        assertEquals(2, ann.emails.size)
        assertEquals("work", ann.emails[0].label)
        assertEquals("ann@corp.example", ann.emails[0].value)
        assertEquals("+1 555 010 9999", ann.phones.single().value)
        assertEquals("lightning", ann.wallets.single().network)
        assertEquals(listOf(xyz.desent.domain.model.ContactAnniversary("Birthday", "1990-05-12")), ann.anniversaries)
        assertEquals("39c83a20032ee04e641cf48996988c6c34c8d0bd60f86dc3c63866dabf5d431e", ann.pubkey)
        assertEquals("corp.example", ann.domain)
        assertEquals("Met at the Oslo meetup", ann.notes)
        // Unknown keys survive decode → encode (round-trip rule).
        assertEquals(setOf("future_field"), ann.extras.keys)

        val reEncoded = mapper.contactsToJson(restored)
        assertEquals(true, reEncoded.contains("\"future_field\""))
        assertEquals(true, reEncoded.contains("\"network\":\"lightning\""))
        assertEquals(true, reEncoded.contains("\"anniversaries\":[{\"label\":\"Birthday\",\"date\":\"1990-05-12\"}]"))
        // A name-only entry is save-valid (spec rule 4).
        assertEquals(true, restored[1].isSaveValid)
    }

    @Test
    fun contacts_migratesV1AndDropsThreadToken() {
        // v1 payload: flat email scalar + deprecated thread_token.
        val v1 = """
            [
              {"name":"Amazon Support","email":"support@amazon.com","domain":"amazon.com",
               "thread_token":"NBRIDGE:v1:abc:hmac","notes":"Return #1"}
            ]
        """.trimIndent()
        val restored = mapper.contactsToDomain(
            xyz.desent.data.local.database.entity.PrivateContactsEntity(
                ownerNpub = "npub1", contactsJson = v1, updatedAt = 1L, dTag = "desent:contacts", createdAt = 1L
            )
        )
        val migrated = restored.single()
        assertEquals("support@amazon.com", migrated.primaryEmail)
        assertEquals(listOf(xyz.desent.domain.model.ContactEmailAddress("", "support@amazon.com")), migrated.emails)
        assertEquals("amazon.com", migrated.domain)
        assertEquals("Return #1", migrated.notes)
        // thread_token is gone; re-encode writes v2 shape only.
        val json = mapper.contactsToJson(restored)
        assertEquals(false, json.contains("thread_token"))
        assertEquals(false, json.contains("\"email\""))
        assertEquals(true, json.contains("\"emails\":["))
    }

    @Test
    fun contacts_normalizesIngestAndDerivesDomain() {
        val dirty = """
            [
              {"name":"X","emails":[{"label":"a","value":"a@x.com"},{"label":"","value":""}],
               "phones":"not-an-array",
               "anniversaries":[{"label":"B","date":"2000-01-01"},{"label":"no-date"}],
               "pubkey":"NPUBWRONG"},
              {"name":"Y","emails":[{"label":"","value":"second@derived.example"}]}
            ]
        """.trimIndent()
        val restored = mapper.contactsToDomain(
            xyz.desent.data.local.database.entity.PrivateContactsEntity(
                ownerNpub = "npub1", contactsJson = dirty, updatedAt = 1L, dTag = "desent:contacts", createdAt = 1L
            )
        )
        val x = restored[0]
        // Entries without value dropped; non-array phones treated as empty;
        // anniversaries without date dropped; garbage pubkey dropped.
        assertEquals(1, x.emails.size)
        assertEquals(0, x.phones.size)
        assertEquals(1, x.anniversaries.size)
        assertEquals(null, x.pubkey)
        // Domain derived from the primary email when missing.
        val y = restored[1]
        assertEquals("derived.example", y.domain)
    }
}
