package xyz.desent.data.relay

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import nostr.event.impl.GenericEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Socket-level enforcement of the "consumer, not poster" relay policy plus
 * the duplicate-REQ guard: a read-only client (any relay other than
 * `wss://desent.xyz`) must refuse to send EVENT frames outright, and an
 * identical REQ must never re-reset the relay-side subscription (which would
 * replay the stored backlog over the network on every app resume).
 */
class NostrWebSocketClientTest {

    private fun client(
        readOnly: Boolean,
        onError: (String, Exception) -> Unit = { _, _ -> }
    ) = NostrWebSocketClient(
        relayUrl = "wss://relay.damus.io",
        coroutineScope = CoroutineScope(Dispatchers.Unconfined),
        signAuthEvent = { _, _ -> null },
        onError = onError,
        readOnly = readOnly
    )

    @Test
    fun `publish on a read-only client throws without touching the socket`() {
        val client = client(readOnly = true)

        val error = runCatching { client.publish(mockk<GenericEvent>(relaxed = true)) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("read-only"))
    }

    @Test
    fun `publish on a disconnected writable client reports the error instead of throwing`() {
        val errors = mutableListOf<Exception>()
        val client = client(readOnly = false) { _, e -> errors += e }

        client.publish(mockk(relaxed = true))

        assertEquals(1, errors.size)
        assertTrue(errors.single().message!!.contains("Not connected"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NostrWebSocketClientSubscriptionTest {

    private val filters = listOf(mapOf("kinds" to listOf(1059), "limit" to 100))
    private val otherFilters = listOf(mapOf("kinds" to listOf(1059), "limit" to 50))

    /**
     * A client past the auth-probe window on a live socket: onOpen starts
     * the 2s AUTH probe; with no AUTH frame it marks authenticated, exactly
     * like a real DeSent-relay connection that doesn't challenge.
     */
    private fun kotlinx.coroutines.test.TestScope.connectedClient(webSocket: WebSocket) =
        NostrWebSocketClient(
            relayUrl = "wss://desent.xyz",
            coroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            signAuthEvent = { _, _ -> null },
            onError = { _, _ -> }
        ).apply {
            onOpen(webSocket, mockk(relaxed = true))
            advanceTimeBy(3_000)
            runCurrent()
        }

    @Test
    fun `an identical REQ is sent only once per connection`() = runTest {
        val webSocket = mockk<WebSocket>(relaxed = true)
        val client = connectedClient(webSocket)

        client.subscribe("giftwrap_test", filters)
        client.subscribe("giftwrap_test", filters)
        client.subscribe("giftwrap_test", filters)

        verify(exactly = 1) { webSocket.send(match<String> { it.contains("giftwrap_test") }) }
    }

    @Test
    fun `changed filters still re-send the REQ`() = runTest {
        val webSocket = mockk<WebSocket>(relaxed = true)
        val client = connectedClient(webSocket)

        client.subscribe("giftwrap_test", filters)
        client.subscribe("giftwrap_test", otherFilters)

        verify(exactly = 2) { webSocket.send(match<String> { it.contains("giftwrap_test") }) }
    }

    @Test
    fun `a reconnect re-sends the REQ even with identical filters`() = runTest {
        val webSocket = mockk<WebSocket>(relaxed = true)
        val client = connectedClient(webSocket)

        client.subscribe("giftwrap_test", filters)
        // Socket drop + re-open: the new connection has not seen the REQ.
        client.onOpen(webSocket, mockk(relaxed = true))
        client.subscribe("giftwrap_test", filters)

        verify(exactly = 2) { webSocket.send(match<String> { it.contains("giftwrap_test") }) }
    }

    @Test
    fun `unsubscribe forgets the sent-REQ memory`() = runTest {
        val webSocket = mockk<WebSocket>(relaxed = true)
        val client = connectedClient(webSocket)

        client.subscribe("giftwrap_test", filters)
        client.unsubscribe("giftwrap_test")
        client.subscribe("giftwrap_test", filters)

        verify(exactly = 2) { webSocket.send(match<String> { it.contains("\"REQ\"") && it.contains("giftwrap_test") }) }
        verify(exactly = 1) { webSocket.send(match<String> { it.contains("\"CLOSE\"") && it.contains("giftwrap_test") }) }
    }
}
