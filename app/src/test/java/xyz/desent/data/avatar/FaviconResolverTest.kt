package xyz.desent.data.avatar

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import xyz.desent.data.local.database.dao.DomainFaviconDao
import xyz.desent.data.local.database.entity.DomainFaviconEntity

class FaviconResolverTest {

    private lateinit var dao: DomainFaviconDao

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        coEvery { dao.getByDomain(any()) } returns null
    }

    private fun resolverWith(probe: (suspend (String) -> Boolean)? = null) = FaviconResolver(
        okHttpClient = OkHttpClient(),
        dao = dao,
        probe = probe
    )

    private fun serverResolverWith(
        serverEnabled: () -> Boolean = { true },
        probe: (suspend (String) -> Boolean)? = null,
        serverCall: (suspend (String) -> ServerFaviconOutcome)? = null
    ): FaviconResolver = FaviconResolver(
        okHttpClient = OkHttpClient(),
        dao = dao,
        nostrHttpAuth = null,
        serverCacheEnabled = { serverEnabled() },
        probe = probe,
        serverCall = serverCall
    )

    @Test
    fun `domain extraction validates sender addresses`() {
        assertEquals("example.com", FaviconResolver.domainOf("User@Example.com"))
        assertEquals("example.co.uk", FaviconResolver.domainOf("a.b@example.co.uk"))
        assertNull(FaviconResolver.domainOf("no-at-sign"))
        assertNull(FaviconResolver.domainOf("localhost"))
        assertNull(FaviconResolver.domainOf("a@dotless"))
        assertNull(FaviconResolver.domainOf("a@.com"))
        assertNull(FaviconResolver.domainOf("a@com."))
        assertNull(FaviconResolver.domainOf("a@exa mple.com"))
        assertNull(FaviconResolver.domainOf("a@example.com/path"))
    }

    @Test
    fun `fresh available row returns URL without probing`() = runBlocking {
        coEvery { dao.getByDomain("example.com") } returns DomainFaviconEntity(
            domain = "example.com", available = true, checkedAt = System.currentTimeMillis()
        )

        val url = resolverWith(probe = { throw AssertionError("probe must not run") }).faviconUrlFor("a@EXAMPLE.com")

        assertEquals("https://example.com/favicon.ico", url)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `fresh miss row returns null without probing`() = runBlocking {
        coEvery { dao.getByDomain("example.com") } returns DomainFaviconEntity(
            domain = "example.com", available = false, checkedAt = System.currentTimeMillis()
        )

        assertNull(resolverWith(probe = { throw AssertionError("probe must not run") }).faviconUrlFor("a@example.com"))
    }

    @Test
    fun `missing row probes and records availability`() = runBlocking {
        val probed = mutableListOf<String>()
        val resolver = resolverWith(probe = { url -> probed += url; true })

        val url = resolver.faviconUrlFor("a@example.com")

        assertEquals("https://example.com/favicon.ico", url)
        assertEquals(listOf("https://example.com/favicon.ico"), probed)
        val stored = slot<DomainFaviconEntity>()
        coVerify { dao.upsert(capture(stored)) }
        assertEquals("example.com", stored.captured.domain)
        assertEquals(true, stored.captured.available)
    }

    @Test
    fun `failed probe records a miss`() = runBlocking {
        val resolver = resolverWith(probe = { false })

        assertNull(resolver.faviconUrlFor("a@example.com"))

        coVerify { dao.upsert(match { it.domain == "example.com" && !it.available }) }
    }

    @Test
    fun `probe exception is treated as a miss`() = runBlocking {
        val resolver = resolverWith(probe = { throw java.io.IOException("boom") })

        assertNull(resolver.faviconUrlFor("a@example.com"))

        coVerify { dao.upsert(match { !it.available }) }
    }

    @Test
    fun `stale row is re-probed and refreshed`() = runBlocking {
        coEvery { dao.getByDomain("example.com") } returns DomainFaviconEntity(
            domain = "example.com",
            available = false,
            checkedAt = System.currentTimeMillis() - 25 * 3600_000L
        )
        val resolver = resolverWith(probe = { true })

        assertEquals("https://example.com/favicon.ico", resolver.faviconUrlFor("a@example.com"))
        coVerify { dao.upsert(match { it.available }) }
    }

    @Test
    fun `concurrent requests for one domain share a single probe`() = runBlocking {
        val probeCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val resolver = resolverWith(probe = { url ->
            probeCalls.incrementAndGet()
            release.await()
            true
        })

        coroutineScope {
            val first = async { resolver.faviconUrlFor("a@example.com") }
            val second = async { resolver.faviconUrlFor("b@example.com") }
            // Both callers are in flight; release the shared probe once.
            while (probeCalls.get() < 1) kotlinx.coroutines.delay(5)
            release.complete(Unit)
            assertEquals("https://example.com/favicon.ico", first.await())
            assertEquals("https://example.com/favicon.ico", second.await())
        }

        assertEquals(1, probeCalls.get())
    }

    @Test
    fun `invalid address returns null without touching dao or network`() = runBlocking {
        val resolver = resolverWith(probe = { throw AssertionError("probe must not run") })

        assertNull(resolver.faviconUrlFor("not-an-email"))
        coVerify(exactly = 0) { dao.getByDomain(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    // ==================== cachedUrlFor (Room-only, latency-sensitive) ====================

    @Test
    fun `cachedUrlFor returns fresh available row without probing or writing`() = runBlocking {
        coEvery { dao.getByDomain("example.com") } returns DomainFaviconEntity(
            domain = "example.com", available = true, checkedAt = System.currentTimeMillis()
        )

        assertEquals(
            "https://example.com/favicon.ico",
            resolverWith(probe = { throw AssertionError("probe must not run") }).cachedUrlFor("a@example.com")
        )
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `cachedUrlFor returns null for fresh miss, stale row, missing row, bad address`() = runBlocking {
        val resolver = resolverWith(probe = { throw AssertionError("probe must not run") })

        coEvery { dao.getByDomain("miss.com") } returns DomainFaviconEntity(
            "miss.com", available = false, checkedAt = System.currentTimeMillis()
        )
        assertNull(resolver.cachedUrlFor("a@miss.com"))

        coEvery { dao.getByDomain("stale.com") } returns DomainFaviconEntity(
            "stale.com", available = true, checkedAt = System.currentTimeMillis() - 25 * 3600_000L
        )
        assertNull(resolver.cachedUrlFor("a@stale.com"))

        coEvery { dao.getByDomain("unknown.com") } returns null
        assertNull(resolver.cachedUrlFor("a@unknown.com"))

        assertNull(resolver.cachedUrlFor("not-an-email"))
    }

    // ==================== server favicon cache (desent.xyz/api/favicon) ====================

    @Test
    fun `server mode hit returns cache URL and records availability`() = runBlocking {
        val called = mutableListOf<String>()
        val resolver = serverResolverWith(
            probe = { throw AssertionError("direct probe must not run") },
            serverCall = { url -> called += url; ServerFaviconOutcome.HIT }
        )

        val url = resolver.faviconUrlFor("a@example.com")

        assertEquals("https://desent.xyz/api/favicon/example.com", url)
        assertEquals(listOf("https://desent.xyz/api/favicon/example.com"), called)
        coVerify { dao.upsert(match { it.domain == "example.com" && it.available }) }
    }

    @Test
    fun `server mode 404 miss is recorded without a direct probe`() = runBlocking {
        val resolver = serverResolverWith(
            probe = { throw AssertionError("direct probe must not run") },
            serverCall = { ServerFaviconOutcome.MISS }
        )

        assertNull(resolver.faviconUrlFor("a@example.com"))

        coVerify { dao.upsert(match { it.domain == "example.com" && !it.available }) }
    }

    @Test
    fun `endpoint failure falls back to a direct probe and sticks for the session`() = runBlocking {
        val serverCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val probed = mutableListOf<String>()
        val resolver = serverResolverWith(
            probe = { url -> probed += url; true },
            serverCall = { serverCalls.incrementAndGet(); ServerFaviconOutcome.UNAVAILABLE }
        )

        assertEquals("https://example.com/favicon.ico", resolver.faviconUrlFor("a@example.com"))
        assertEquals(listOf("https://example.com/favicon.ico"), probed)
        coVerify { dao.upsert(match { it.available }) }

        // Second domain: the session flag must keep the server out of the loop.
        assertEquals("https://other.com/favicon.ico", resolver.faviconUrlFor("b@other.com"))
        assertEquals(1, serverCalls.get())
        assertEquals(listOf("https://example.com/favicon.ico", "https://other.com/favicon.ico"), probed)
    }

    @Test
    fun `server call exception is treated as endpoint failure`() = runBlocking {
        val resolver = serverResolverWith(
            probe = { url -> url == "https://example.com/favicon.ico" },
            serverCall = { throw java.io.IOException("timeout") }
        )

        assertEquals("https://example.com/favicon.ico", resolver.faviconUrlFor("a@example.com"))
        coVerify { dao.upsert(match { it.available }) }
    }

    @Test
    fun `preference is consulted per call so toggling applies mid-session`() = runBlocking {
        var serverEnabled = true
        val serverCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val resolver = serverResolverWith(
            serverEnabled = { serverEnabled },
            probe = { _ -> true },
            serverCall = { serverCalls.incrementAndGet(); ServerFaviconOutcome.HIT }
        )

        assertEquals("https://desent.xyz/api/favicon/example.com", resolver.faviconUrlFor("a@example.com"))

        serverEnabled = false
        assertEquals("https://other.com/favicon.ico", resolver.faviconUrlFor("b@other.com"))
        assertEquals(1, serverCalls.get())
    }

    @Test
    fun `concurrent server-mode requests for one domain share a single call`() = runBlocking {
        val serverCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val resolver = serverResolverWith(
            probe = { throw AssertionError("direct probe must not run") },
            serverCall = { _ ->
                serverCalls.incrementAndGet()
                release.await()
                ServerFaviconOutcome.HIT
            }
        )

        coroutineScope {
            val first = async { resolver.faviconUrlFor("a@example.com") }
            val second = async { resolver.faviconUrlFor("b@example.com") }
            while (serverCalls.get() < 1) kotlinx.coroutines.delay(5)
            release.complete(Unit)
            assertEquals("https://desent.xyz/api/favicon/example.com", first.await())
            assertEquals("https://desent.xyz/api/favicon/example.com", second.await())
        }

        assertEquals(1, serverCalls.get())
    }

    @Test
    fun `cachedUrlFor follows the selected source`() = runBlocking {
        coEvery { dao.getByDomain("example.com") } returns DomainFaviconEntity(
            domain = "example.com", available = true, checkedAt = System.currentTimeMillis()
        )

        assertEquals(
            "https://desent.xyz/api/favicon/example.com",
            serverResolverWith(
                probe = { throw AssertionError("probe must not run") },
                serverCall = { throw AssertionError("server call must not run") }
            ).cachedUrlFor("a@example.com")
        )

        assertEquals(
            "https://example.com/favicon.ico",
            serverResolverWith(
                serverEnabled = { false },
                probe = { throw AssertionError("probe must not run") },
                serverCall = { throw AssertionError("server call must not run") }
            ).cachedUrlFor("a@example.com")
        )
    }
}
