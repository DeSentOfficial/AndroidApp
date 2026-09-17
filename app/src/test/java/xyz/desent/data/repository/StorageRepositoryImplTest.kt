package xyz.desent.data.repository

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.desent.data.storage.StorageClient
import xyz.desent.data.storage.model.StorageBreakdownDto
import xyz.desent.data.storage.model.StorageCategoryDto
import xyz.desent.data.storage.model.StorageError
import xyz.desent.domain.model.STORAGE_CATEGORY_ORDER

class StorageRepositoryImplTest {

    private lateinit var client: StorageClient
    private lateinit var repo: StorageRepositoryImpl

    @Before
    fun setUp() {
        client = mockk()
        repo = StorageRepositoryImpl(client)
    }

    @Test
    fun getStorageBreakdown_mapsFieldsAndSetsRefreshedAt() = runBlocking {
        val before = System.currentTimeMillis()
        coEvery { client.getStorageBreakdown() } returns Result.success(
            StorageBreakdownDto(
                used = 49623552L,
                cap = 262144000L,
                tier = "free",
                source = "override",
                categories = STORAGE_CATEGORY_ORDER.map {
                    StorageCategoryDto(key = it, label = it, bytes = 1L)
                }
            )
        )

        val result = repo.getStorageBreakdown()

        assertTrue(result.isSuccess)
        val breakdown = result.getOrNull()!!
        assertEquals(49623552L, breakdown.used)
        assertEquals(262144000L, breakdown.cap)
        assertEquals("free", breakdown.tier)
        assertEquals("override", breakdown.source)
        assertEquals(STORAGE_CATEGORY_ORDER.size, breakdown.categories.size)
        val after = System.currentTimeMillis()
        assertTrue(breakdown.refreshedAt in before..after)
    }

    @Test
    fun getStorageBreakdown_sortsCategoriesByCanonicalOrder() = runBlocking {
        // Server returns the rows in a scrambled order.
        coEvery { client.getStorageBreakdown() } returns Result.success(
            StorageBreakdownDto(
                used = 100L,
                cap = 1000L,
                categories = listOf(
                    StorageCategoryDto("authored", "Other", 1L),
                    StorageCategoryDto("mail", "Inbox", 2L),
                    StorageCategoryDto("blobs", "Attachments", 3L),
                    StorageCategoryDto("nip46", "NIP-46", 4L),
                    StorageCategoryDto("notes", "Notes", 5L),
                    StorageCategoryDto("contacts", "Contacts", 6L),
                    StorageCategoryDto("calendar", "Calendar", 7L),
                    StorageCategoryDto("settings", "Settings", 8L)
                )
            )
        )

        val result = repo.getStorageBreakdown()

        assertTrue(result.isSuccess)
        val keys = result.getOrNull()!!.categories.map { it.key }
        assertEquals(STORAGE_CATEGORY_ORDER, keys)
    }

    @Test
    fun getStorageBreakdown_appendsUnknownKeysAfterKnownOnes() = runBlocking {
        coEvery { client.getStorageBreakdown() } returns Result.success(
            StorageBreakdownDto(
                used = 0L,
                cap = 0L,
                categories = listOf(
                    StorageCategoryDto("future_cat", "Future", 10L),
                    StorageCategoryDto("blobs", "Attachments", 20L)
                )
            )
        )

        val result = repo.getStorageBreakdown()

        assertTrue(result.isSuccess)
        val keys = result.getOrNull()!!.categories.map { it.key }
        // Known key first, unknown appended.
        assertEquals(listOf("blobs", "future_cat"), keys)
    }

    @Test
    fun getStorageBreakdown_flagsCategorySumMismatch() = runBlocking {
        // used (100) != SUM(categories) (7) — spec §8 anomaly marker.
        coEvery { client.getStorageBreakdown() } returns Result.success(
            StorageBreakdownDto(
                used = 100L,
                cap = 1000L,
                categories = listOf(StorageCategoryDto("blobs", "Attachments", 7L))
            )
        )

        val mismatch = repo.getStorageBreakdown().getOrNull()!!

        assertTrue(mismatch.categorySumMismatch)

        coEvery { client.getStorageBreakdown() } returns Result.success(
            StorageBreakdownDto(
                used = 7L,
                cap = 1000L,
                categories = listOf(StorageCategoryDto("blobs", "Attachments", 7L))
            )
        )
        val matching = repo.getStorageBreakdown().getOrNull()!!
        assertTrue(!matching.categorySumMismatch)
    }

    @Test
    fun getStorageBreakdown_propagatesClientFailureUntouched() = runBlocking {
        coEvery { client.getStorageBreakdown() } returns Result.failure(StorageError.Forbidden)

        val result = repo.getStorageBreakdown()

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is StorageError.Forbidden)
    }

    @Test
    fun getStorageBreakdown_handlesRateLimitError() = runBlocking {
        coEvery { client.getStorageBreakdown() } returns Result.failure(
            StorageError.RateLimited(retryAfterSeconds = 30)
        )

        val result = repo.getStorageBreakdown()

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(err is StorageError.RateLimited)
        assertEquals(30, (err as StorageError.RateLimited).retryAfterSeconds)
    }
}
