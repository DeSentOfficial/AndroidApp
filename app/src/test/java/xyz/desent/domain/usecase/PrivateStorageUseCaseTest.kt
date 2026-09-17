package xyz.desent.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.crypto.AesGcm
import xyz.desent.data.attachments.NoteAttachmentClient
import xyz.desent.data.local.preferences.PreferencesManager
import xyz.desent.domain.model.UserFile
import xyz.desent.domain.repository.PrivateStorageRepository

class PrivateStorageUseCaseTest {

    private lateinit var repository: PrivateStorageRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var attachmentClient: NoteAttachmentClient
    private lateinit var useCase: PrivateStorageUseCase

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        preferencesManager = mockk()
        attachmentClient = mockk()
        every { preferencesManager.npubKey } returns flowOf("npub1test")
        useCase = PrivateStorageUseCase(repository, preferencesManager, attachmentClient)
    }

    @Test
    fun uploadUserFile_encryptsUploadsAndPublishesEntryWithPreview() = runBlocking {
        val data = ByteArray(2048) { (it % 251).toByte() }
        val wireSlot = slot<ByteArray>()
        coEvery {
            attachmentClient.uploadCiphertext(capture(wireSlot), "image/png")
        } returns Result.success(NoteAttachmentClient.UploadResponse("shaabc", 2076L))
        val savedSlot = slot<UserFile>()
        coEvery { repository.saveUserFile(capture(savedSlot)) } returns Result.success(Unit)

        val result = useCase.uploadUserFile(
            data, "image/png", "pic.png",
            blurhash = "R0AAAAfQfQ", width = 640, height = 480
        )

        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertEquals("shaabc", file.sha256)
        assertEquals("pic.png", file.filename)
        assertEquals("desent:file:shaabc", file.dTag)
        assertEquals(data.size.toLong(), file.size)
        assertEquals("R0AAAAfQfQ", file.blurhash)
        assertEquals(640, file.width)
        assertEquals(480, file.height)

        // The uploaded wire bytes decrypt back to the plaintext with the key
        // that travelled only inside the self-encrypted 30078 entry.
        assertArrayEquals(data, AesGcm.decrypt(wireSlot.captured, file.keyHex, file.nonceHex))
        assertEquals(file, savedSlot.captured)
    }

    @Test
    fun uploadUserFile_rejectsOversizedFile_beforeAnyUpload() = runBlocking {
        val tooBig = ByteArray((PrivateStorageUseCase.MAX_USER_FILE_BYTES + 1).toInt()) { 0 }

        val result = useCase.uploadUserFile(tooBig, "application/pdf", "big.pdf")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { attachmentClient.uploadCiphertext(any(), any()) }
        coVerify(exactly = 0) { repository.saveUserFile(any()) }
    }

    @Test
    fun uploadUserFile_keepsBlobAsOrphan_whenEntryPublishFails() = runBlocking {
        // END-23 §2 step 6: no rollback — the blob stays as a deletable
        // orphan and the caller surfaces the failure to the user.
        coEvery { attachmentClient.uploadCiphertext(any(), any()) } returns Result.success(
            NoteAttachmentClient.UploadResponse("shaabc", 100)
        )
        coEvery { repository.saveUserFile(any()) } returns Result.failure(Exception("relay down"))

        val result = useCase.uploadUserFile(ByteArray(64) { 1 }, "text/plain", "a.txt")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { attachmentClient.deleteCiphertextBlob(any()) }
        coVerify(exactly = 0) { attachmentClient.deleteCiphertextBlobStrict(any()) }
    }

    @Test
    fun deleteUserFile_deletesBlobFirst_thenTombstones() = runBlocking {
        // END-23 §4 ordering: DELETE the blob (frees quota immediately),
        // then publish the tombstone so the key material leaves storage.
        coEvery { attachmentClient.deleteCiphertextBlobStrict("shaabc") } returns Result.success(Unit)
        coEvery { repository.deleteUserFile("npub1test", "shaabc") } returns Result.success(Unit)

        val result = useCase.deleteUserFile("npub1test", "shaabc")

        assertTrue(result.isSuccess)
        coVerifyOrder {
            attachmentClient.deleteCiphertextBlobStrict("shaabc")
            repository.deleteUserFile("npub1test", "shaabc")
        }
    }

    @Test
    fun deleteUserFile_skipsTombstone_whenBlobDeleteRejected() = runBlocking {
        // Blob delete rejected (429/5xx) → the entry keeps its key so the
        // delete can be retried; no tombstone is published.
        coEvery { attachmentClient.deleteCiphertextBlobStrict("shaabc") } returns Result.failure(Exception("429"))

        val result = useCase.deleteUserFile("npub1test", "shaabc")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { repository.deleteUserFile(any(), any()) }
    }
}
