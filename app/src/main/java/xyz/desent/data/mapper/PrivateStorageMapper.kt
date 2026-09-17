package xyz.desent.data.mapper

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.desent.data.local.database.entity.PrivateContactsEntity
import xyz.desent.data.local.database.entity.PrivateNoteEntity
import xyz.desent.domain.model.LegacyAttachmentsSerializer
import xyz.desent.domain.model.NotePayload
import xyz.desent.domain.model.PrivateContact
import xyz.desent.domain.model.PrivateContactListSerializer
import xyz.desent.domain.model.PrivateNote

class PrivateStorageMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun mapNoteToDomain(entity: PrivateNoteEntity): PrivateNote = PrivateNote(
        id = entity.id,
        ownerNpub = entity.ownerNpub,
        title = entity.title,
        body = entity.body,
        updatedAt = entity.updatedAt,
        folder = entity.folder,
        attachments = json.decodeFromString(LegacyAttachmentsSerializer, entity.attachmentsJson),
        dTag = entity.dTag
    )

    fun mapNoteToEntity(domain: PrivateNote, createdAt: Long): PrivateNoteEntity = PrivateNoteEntity(
        id = domain.id,
        ownerNpub = domain.ownerNpub,
        title = domain.title,
        body = domain.body,
        updatedAt = domain.updatedAt,
        folder = domain.folder,
        attachmentsJson = json.encodeToString(LegacyAttachmentsSerializer, domain.attachments),
        dTag = domain.dTag,
        createdAt = createdAt
    )

    fun notePayload(note: PrivateNote): NotePayload = NotePayload(
        title = note.title,
        body = note.body,
        updated_at = note.updatedAt,
        folder = note.folder,
        attachments = note.attachments
    )

    fun contactsToDomain(entity: PrivateContactsEntity): List<PrivateContact> =
        json.decodeFromString(PrivateContactListSerializer, entity.contactsJson)

    fun contactsToJson(contacts: List<PrivateContact>): String =
        json.encodeToString(PrivateContactListSerializer, contacts)
}
