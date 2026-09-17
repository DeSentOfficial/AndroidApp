package xyz.desent.data.relay

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nostr.event.impl.GenericEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * A relay's `["OK", <event-id>, bool, message]` verdict for a published
 * event. `message` carries the relay's reason on rejection (e.g. the
 * badge pin validator's `"invalid: …"` text).
 */
data class PublishResult(
    val eventId: String,
    val success: Boolean,
    val message: String,
    val relayUrl: String
)

/**
 * Lightweight Nostr relay client using OkHttp WebSocket.
 * Handles subscription management, message routing, and connection lifecycle.
 */
class NostrWebSocketClient(
    private val relayUrl: String,
    private val coroutineScope: CoroutineScope,
    private val signAuthEvent: suspend (challenge: String, relayUrl: String) -> GenericEvent?,
    private val onEvent: (GenericEvent) -> Unit = {},
    private val onError: (String, Exception) -> Unit = { _, _ -> },
    private val onConnected: (String) -> Unit = {},
    /**
     * True for third-party relays: DeSent is a *consumer* of the wider Nostr
     * network, never a poster. A read-only socket may carry REQ/CLOSE frames
     * (kind-0 profile fetches) but [publish] refuses to send any EVENT frame
     * (hard crash by design — see AGENTS.md relay policy).
     */
    val readOnly: Boolean = false
) : WebSocketListener() {

    private lateinit var webSocket: WebSocket
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val subscriptions = mutableMapOf<String, List<Map<String, Any>>>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Bumped on every socket (re)connection. Tracks which REQs have actually
     * gone out on the LIVE connection so [subscribe] can skip duplicates:
     * re-sending an identical REQ makes the relay replace the subscription
     * and replay its stored events — the client then re-downloads (and
     * discards) the whole limit-N backlog on every app resume.
     */
    private var connectionGeneration = 0L

    private class SentReq(val filters: List<Map<String, Any>>, val generation: Long)
    private val sentReqs = mutableMapOf<String, SentReq>()

    private val _publishResults = MutableSharedFlow<PublishResult>(extraBufferCapacity = 64)

    /**
     * Every publish verdict this relay sends (`OK` frames with an event id).
     * Buffered (no replay) so late collectors only see future results.
     */
    val publishResults: SharedFlow<PublishResult> = _publishResults.asSharedFlow()

    private val _eoseEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)

    /**
     * Every `EOSE` frame this relay sends: (subscriptionId, relayUrl). Lets
     * one-shot fetches complete as soon as all queried relays report "no
     * more stored events" instead of idling to a fixed timeout.
     */
    val eoseEvents: SharedFlow<Pair<String, String>> = _eoseEvents.asSharedFlow()

    private val _closedEvents =
        MutableSharedFlow<ClosedNotification>(extraBufferCapacity = 64)

    /**
     * Every `CLOSED` frame this relay sends (subscription terminated
     * server-side before EOSE — e.g. `auth-required`). Read-only relays that
     * demand NIP-42 AUTH never answer our REQs; one-shot fetches watch this
     * to fail fast with the relay's reason instead of idling to a timeout.
     */
    val closedEvents: SharedFlow<ClosedNotification> = _closedEvents.asSharedFlow()

    /** A relay-terminated subscription, with the relay's reason if any. */
    data class ClosedNotification(val subscriptionId: String, val relayUrl: String, val reason: String?)

    private var connectionContinuation: CancellableContinuation<Unit>? = null
    private var authProbeJob: Job? = null

    var isConnected = false
        private set

    /**
     * True once this connection is ready to carry REQ/EVENT traffic — either the
     * relay confirmed NIP-42 auth (`["OK", null, true, "authenticated"]`), or the
     * [AUTH_PROBE_MS] timer expired without an AUTH frame (relay doesn't require
     * auth). Subscriptions are buffered until this flips.
     */
    var isAuthenticated = false
        private set

    /**
     * Connect to the relay WebSocket.
     */
    fun connect() {
        val request = Request.Builder()
            .url(relayUrl)
            .build()

        webSocket = client.newWebSocket(request, this)
    }

    /**
     * Subscribe to events with filters.
     * Format: ["REQ", subscription_id, {filter1}, {filter2}, ...]
     */
    fun subscribe(subscriptionId: String, filters: List<Map<String, Any>>) {
        subscriptions[subscriptionId] = filters

        if (isConnected && isAuthenticated) {
            // Same REQ already sent on this connection → the relay-side
            // subscription is still live; a duplicate would reset it and
            // replay the stored backlog over the network. Changed filters
            // (e.g. a moved `since` cursor) still re-send.
            val sent = sentReqs[subscriptionId]
            if (sent != null && sent.generation == connectionGeneration && sent.filters == filters) {
                android.util.Log.d("Relay", " Skipping duplicate REQ: $subscriptionId on $relayUrl")
                return
            }
            sentReqs[subscriptionId] = SentReq(filters, connectionGeneration)
            val message = buildMessage("REQ", subscriptionId, filters)
            android.util.Log.d("Relay", " Sending subscription: $subscriptionId to $relayUrl")
            android.util.Log.d("Relay", "   Full Message: $message")
            webSocket.send(message)
        } else {
            android.util.Log.d("Relay", " Buffering subscription $subscriptionId for $relayUrl (connected=$isConnected, authed=$isAuthenticated)")
        }
    }

    /**
     * Unsubscribe from events.
     * Format: ["CLOSE", subscription_id]
     */
    fun unsubscribe(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
        sentReqs.remove(subscriptionId)

        if (isConnected) {
            val message = """["CLOSE","$subscriptionId"]"""
            webSocket.send(message)
        }
    }

    /**
     * Publish an event to the relay.
     * Format: ["EVENT", {event}]
     *
     * @throws IllegalStateException when this client is [readOnly] — third-party
     * relays must never receive EVENT frames.
     */
    fun publish(event: GenericEvent) {
        check(!readOnly) {
            "DeSent relay policy: refused to publish kind-${event.kind} event to read-only relay $relayUrl"
        }
        if (!isConnected) {
            onError(relayUrl, Exception("Not connected to relay"))
            return
        }

        try {
            val eventJson = serializeEvent(event)
            val message = """["EVENT",$eventJson]"""

            android.util.Log.d("RelayManager", " Publishing kind-${event.kind} event ${event.id?.take(12)} to $relayUrl")

            webSocket.send(message)
        } catch (e: Exception) {
            onError(relayUrl, e)
        }
    }

    /**
     * Send a signed NIP-42 kind 22242 auth event back to the relay.
     * Format: ["AUTH", {event}]
     */
    fun sendAuth(event: GenericEvent) {
        try {
            val eventJson = serializeEvent(event)
            val message = """["AUTH",$eventJson]"""
            webSocket.send(message)
        } catch (e: Exception) {
            onError(relayUrl, e)
        }
    }

    /**
     * Close the WebSocket connection.
     */
    fun close() {
        authProbeJob?.cancel()
        isAuthenticated = false
        webSocket.close(1000, "Client closing")
        client.dispatcher.executorService.shutdown()
    }

    /**
     * True when the connection can carry REQ/EVENT traffic — both the WebSocket
     * is open AND NIP-42 auth has been resolved (accepted, rejected-but-proceeding,
     * or not required).
     */
    fun isReady(): Boolean = isConnected && isAuthenticated

    /**
     * Suspend until the connection is [isReady] (connected + auth resolved).
     * Times out after [timeoutMs] so callers don't block indefinitely.
     */
    suspend fun waitForReady(timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isReady()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return
            delay(minOf(remaining, 100))
        }
    }

    /**
     * Suspend until WebSocket is connected (onOpen callback fires).
     * Times out after 5 seconds to prevent infinite hangs.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun waitForConnection() {
        try {
            withTimeout(5000L) {
                suspendCancellableCoroutine { continuation: CancellableContinuation<Unit> ->
                    connectionContinuation = continuation
                    
                    // If already connected, resume immediately
                    if (isConnected) {
                        continuation.resume(Unit) {}
                        connectionContinuation = null
                    }
                    
                    continuation.invokeOnCancellation {
                        connectionContinuation = null
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw Exception("WebSocket connection timeout after 5 seconds to $relayUrl")
        }
    }

    // WebSocketListener overrides

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        isConnected = true
        connectionGeneration++
        // The listener is handed this connection's socket; keep it so REQ/
        // CLOSE frames route to the live connection even when onOpen fires
        // without a prior connect() (e.g. a listener registered elsewhere).
        this.webSocket = webSocket

        // Resume any suspended coroutine waiting for connection
        connectionContinuation?.resume(Unit) {}
        connectionContinuation = null

        android.util.Log.d("RelayManager", " WebSocket opened for relay: $relayUrl")

        // NIP-42: give the relay a short window to send ["AUTH", challenge].
        // If none arrives, the relay doesn't require auth — mark authenticated
        // and flush buffered REQs. Subscriptions are NOT sent here; the flush
        // (either after auth-OK or this probe) handles them.
        authProbeJob = coroutineScope.launch {
            delay(AUTH_PROBE_MS)
            if (!isAuthenticated) {
                android.util.Log.d("Relay", " No AUTH frame from $relayUrl after ${AUTH_PROBE_MS}ms - proceeding without auth")
                markAuthenticatedAndFlush()
            }
        }

        onConnected(relayUrl)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val array = jsonElement.jsonArray
            
            if (array.size < 2) return

            val type = array[0].jsonPrimitive.content
            
            when (type) {
                "EVENT" -> {
                    if (array.size >= 3) {
                        val subscriptionId = array[1].jsonPrimitive.content
                        val eventJson = array[2].jsonObject
                        val kind = eventJson["kind"]?.jsonPrimitive?.content?.toInt()
                        android.util.Log.d("Relay", " Event received: kind=$kind, subId=$subscriptionId, relay=$relayUrl")

                        val event = deserializeEvent(eventJson.toString())
                        if (event != null) {
                            onEvent(event)
                        } else {
                            android.util.Log.w("Relay", " Failed to deserialize event kind=$kind")
                        }
                    }
                }
                "EOSE" -> {
                    val subscriptionId = array[1].jsonPrimitive.content
                    android.util.Log.d("Relay", " EOSE for subscription: $subscriptionId")
                    _eoseEvents.tryEmit(subscriptionId to relayUrl)
                }
                "AUTH" -> {
                    // NIP-42 relay-initiated challenge: ["AUTH", "<challenge>"]
                    if (array.size >= 2) {
                        val challenge = array[1].jsonPrimitive.content
                        android.util.Log.d("Relay", " AUTH challenge received from $relayUrl")
                        authProbeJob?.cancel()
                        coroutineScope.launch {
                            try {
                                val signedEvent = signAuthEvent(challenge, relayUrl)
                                if (signedEvent != null) {
                                    sendAuth(signedEvent)
                                    android.util.Log.d("Relay", " Sent AUTH response to $relayUrl")
                                } else {
                                    android.util.Log.w("Relay", " Cannot sign AUTH for $relayUrl - no signing key available")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Relay", " Failed to handle AUTH challenge: ${e.message}", e)
                            }
                        }
                    }
                }
                "OK" -> {
                    // NIP-42 auth result: ["OK", null, true, "authenticated"].
                    // Publish result:    ["OK", "<event-id>", bool, "..."].
                    if (array.size >= 3) {
                        val first = array[1]
                        val success = array[2].jsonPrimitive.content.toBoolean()
                        if (first is JsonNull) {
                            val msg = array.getOrNull(3)?.let { (it as? JsonPrimitive)?.content } ?: ""
                            if (success && msg.contains("authenticated", ignoreCase = true)) {
                                android.util.Log.d("Relay", " NIP-42 authenticated with $relayUrl")
                                markAuthenticatedAndFlush()
                            } else {
                                // Auth rejected — proceed anyway. Many relays accept
                                // REQ/EVENT traffic without strict auth; leaving
                                // isAuthenticated=false would permanently block all
                                // buffered subscriptions (the auth probe was already
                                // cancelled by the AUTH challenge, so nothing else
                                // would ever flush them).
                                android.util.Log.w("Relay", " AUTH rejected by $relayUrl: success=$success msg=$msg - proceeding without auth")
                                markAuthenticatedAndFlush()
                            }
                        } else {
                            val eventId = first.jsonPrimitive.content
                            val msg = array.getOrNull(3)?.let {
                                (it as? JsonPrimitive)?.content
                            } ?: ""
                            android.util.Log.d("Relay", " Event publish ${if (success) "SUCCESS" else "FAILED"}: $eventId")
                            _publishResults.tryEmit(
                                PublishResult(eventId, success, msg, relayUrl)
                            )
                        }
                    }
                }
                "CLOSED" -> {
                    val subId = array.getOrNull(1)?.let { (it as? JsonPrimitive)?.content }
                    val reason = array.getOrNull(2)?.let { (it as? JsonPrimitive)?.content }
                    android.util.Log.w("Relay", " Subscription CLOSED by $relayUrl: sub=$subId reason=$reason")
                    if (subId != null) {
                        _closedEvents.tryEmit(ClosedNotification(subId, relayUrl, reason))
                    }
                }
                "NOTICE" -> {
                    if (array.size >= 2) {
                        val message = array[1].jsonPrimitive.content
                        android.util.Log.w("Relay", " Relay NOTICE: $message")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Relay", " Error processing message: ${e.message}")
            // Do NOT call onError here - the connection is still alive, this is a parse error
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        android.util.Log.d("RelayManager", "WebSocket peer-initiated close for relay: $relayUrl - code: $code, reason: \"$reason\"")
        webSocket.close(1000, null)
        isConnected = false
        isAuthenticated = false
        authProbeJob?.cancel()
        // Route through the same reconnect path as onFailure so persistent
        // relays self-heal on a graceful close (idle timeout, server restart,
        // load-balancer rotation). Without this the socket stays dead.
        onError(relayUrl, Exception("WebSocket closed by peer (code $code)"))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        isConnected = false
        isAuthenticated = false
        authProbeJob?.cancel()
        connectionContinuation?.resume(Unit) {}
        connectionContinuation = null
        android.util.Log.d("RelayManager", "WebSocket closed for relay: $relayUrl - code: $code, reason: \"$reason\"")
        // Same self-heal as onClosing/onFailure.
        onError(relayUrl, Exception("WebSocket closed (code $code)"))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        isConnected = false
        isAuthenticated = false
        authProbeJob?.cancel()
        connectionContinuation?.cancel(Exception(t))
        connectionContinuation = null
        val httpCode = response?.code
        android.util.Log.e("RelayManager", "WebSocket failure for relay: $relayUrl - ${t.javaClass.simpleName}: ${t.message}" + (httpCode?.let { ", httpCode: $it" } ?: ""))
        onError(relayUrl, Exception(t))
    }

    // Private helpers

    /**
     * Mark this connection authenticated and flush all buffered subscriptions.
     * Idempotent — safe to call from both the probe-timer path and the auth-OK path.
     */
    private fun markAuthenticatedAndFlush() {
        if (isAuthenticated) return
        isAuthenticated = true
        android.util.Log.d("Relay", " $relayUrl ready (auth=$isAuthenticated); flushing ${subscriptions.size} subscription(s)")
        flushSubscriptions()
    }

    private fun flushSubscriptions() {
        if (!isConnected || !isAuthenticated) return
        subscriptions.forEach { (subId, filters) ->
            sentReqs[subId] = SentReq(filters, connectionGeneration)
            val message = buildMessage("REQ", subId, filters)
            android.util.Log.d("Relay", " Flushing subscription: $subId to $relayUrl")
            webSocket.send(message)
        }
    }

    private fun buildMessage(
        type: String,
        subscriptionId: String,
        filters: List<Map<String, Any>>
    ): String {
        val filterStrings = filters.map { filter ->
            "{" + filter.entries.joinToString(",") { (k, v) ->
                when (v) {
                    is List<*> -> {
                        val values = (v as List<*>).joinToString(",") { item ->
                            when (item) {
                                is String -> "\"$item\""
                                is Number -> item.toString()
                                else -> "\"$item\""
                            }
                        }
                        "\"$k\":[$values]"
                    }
                    is String -> "\"$k\":\"$v\""
                    is Number -> "\"$k\":$v"
                    else -> "\"$k\":\"$v\""
                }
            } + "}"
        }.joinToString(",")

        return """["$type","$subscriptionId",$filterStrings]"""
    }

    private fun serializeEvent(event: GenericEvent): String {
        // Simplified event serialization
        val tagsJson = event.tags.map { tag ->
            val params = (tag as? nostr.event.tag.GenericTag)?.getParams() 
                ?: listOf<String>()
            "[" + (listOf(tag.getCode()) + params).joinToString(",") { "\"$it\"" } + "]"
        }.joinToString(",")

        return """{
            "id":"${event.id}",
            "pubkey":"${event.pubKey.toHexString()}",
            "created_at":${event.createdAt},
            "kind":${event.kind},
            "tags":[$tagsJson],
            "content":"${event.content?.replace("\"", "\\\"")}",
            "sig":"${event.signature?.toString() ?: ""}"
        }"""
    }

    private fun deserializeEvent(json: String): GenericEvent? {
        return try {
            // Parse JSON manually to avoid complex deserialization
            val jsonObj = this.json.parseToJsonElement(json).jsonObject

            val id = jsonObj["id"]?.jsonPrimitive?.content ?: return null
            val pubkey = jsonObj["pubkey"]?.jsonPrimitive?.content ?: return null
            val createdAt = jsonObj["created_at"]?.jsonPrimitive?.content?.toLong() ?: return null
            val kind = jsonObj["kind"]?.jsonPrimitive?.content?.toInt() ?: return null
            val content = jsonObj["content"]?.jsonPrimitive?.content ?: ""

            // Parse tags array
            val tagsArray = jsonObj["tags"]?.jsonArray ?: return null
            val tags = tagsArray.map { tagElement ->
                tagElement.jsonArray.map { it.jsonPrimitive.content }
            }
            // Convert to GenericTag objects
            val genericTags = tags.map { tagValues ->
                val code = tagValues.getOrElse(0) { "" }
                val params = tagValues.drop(1)
                nostr.event.tag.GenericTag(code, params)
            }

            val event = GenericEvent.builder()
                .id(id)
                .pubKey(nostr.base.PublicKey(pubkey))
                .createdAt(createdAt)
                .kind(kind)
                .content(content)
                .tags(genericTags)
                .build()

            event
        } catch (e: Exception) {
            android.util.Log.e("Relay", " Failed to deserialize event: ${e.message}")
            null
        }
    }

    companion object {
        /**
         * How long to wait after onOpen for a relay to send ["AUTH", challenge]
         * before assuming it doesn't require NIP-42 auth. Per the NIP-42 doc the
         * AUTH frame is sent immediately on connect, so 2s is ample headroom.
         */
        private const val AUTH_PROBE_MS = 2000L
    }
}
