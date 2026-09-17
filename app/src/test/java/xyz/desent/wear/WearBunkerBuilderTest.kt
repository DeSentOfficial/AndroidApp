package xyz.desent.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.data.nip46.Nip46SignPrompt
import xyz.desent.data.nip46.PROMPT_TIMEOUT_SECONDS

class WearBunkerBuilderTest {

    private fun prompt(
        requestId: String = "req-1",
        unsignedEventJson: String? = """{"kind":1,"content":"hello"}""",
        label: String = "Corptelegraph"
    ) = Nip46SignPrompt(
        requestId = requestId,
        sessionPubkey = "aa".repeat(32),
        label = label,
        userPubkey = "bb".repeat(32),
        method = "sign_event",
        unsignedEventJson = unsignedEventJson,
        eventKind = 1L
    )

    @Test
    fun nullPromptBuildsClearedPayload() {
        val payload = WearBunkerBuilder.build(prompt = null, bunkerEnabled = true, nowMs = 5_000L)
        assertNull(payload.requestId)
        assertEquals("", payload.method)
        assertEquals(0L, payload.promptedAt)
        assertEquals(0L, payload.expiresAt)
        assertTrue(payload.bunkerEnabled)
        assertEquals(5_000L, payload.syncedAt)
    }

    @Test
    fun mapsPromptFieldsAndCapsPreview() {
        val longJson = "x".repeat(500)
        val payload = WearBunkerBuilder.build(
            prompt = prompt(unsignedEventJson = longJson),
            bunkerEnabled = false,
            nowMs = 100_000L
        )
        assertNotNull(payload.requestId)
        assertEquals("req-1", payload.requestId)
        assertEquals("Corptelegraph", payload.label)
        assertEquals("sign_event", payload.method)
        assertEquals(1L, payload.eventKind)
        assertEquals(WearBunkerBuilder.MAX_PREVIEW_CHARS, payload.preview!!.length)
        assertEquals(false, payload.bunkerEnabled)
    }

    @Test
    fun expiresAtMatchesDialogAutoDeny() {
        val nowMs = 1_000_000L
        val payload = WearBunkerBuilder.build(prompt = prompt(), bunkerEnabled = true, nowMs = nowMs)
        assertEquals(nowMs, payload.promptedAt)
        assertEquals(nowMs + PROMPT_TIMEOUT_SECONDS * 1000, payload.expiresAt)
    }

    @Test
    fun longLabelCapped() {
        val payload = WearBunkerBuilder.build(
            prompt = prompt(label = "L".repeat(200)),
            bunkerEnabled = true,
            nowMs = 0L
        )
        assertEquals(WearBunkerBuilder.MAX_LABEL_CHARS, payload.label.length)
    }

    @Test
    fun nullPreviewStaysNull() {
        val payload = WearBunkerBuilder.build(
            prompt = prompt(unsignedEventJson = null),
            bunkerEnabled = true,
            nowMs = 0L
        )
        assertNull(payload.preview)
    }
}
