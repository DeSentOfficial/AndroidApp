package xyz.desent.wear

import xyz.desent.data.nip46.Nip46SignPrompt
import xyz.desent.data.nip46.PROMPT_TIMEOUT_SECONDS
import xyz.desent.data.wearsync.WearBunkerRequest

/**
 * Pure mapping of the bunker's pending prompt ([Nip46SignPrompt]) to the
 * watch payload ([WearBunkerRequest]). The watch never sees keys or the full
 * unsigned event — only the label, method, and a short preview it needs to
 * make an accept/deny decision. The phone signs.
 *
 * Kept free of Android dependencies so the caps/expiry logic is
 * unit-testable (see WearBunkerBuilderTest).
 */
object WearBunkerBuilder {

    /** Preview cap for the tiny watch screen (the phone dialog shows 500). */
    const val MAX_PREVIEW_CHARS = 200

    /** Label cap — pairing labels are short, but be defensive. */
    const val MAX_LABEL_CHARS = 60

    /**
     * Map the current prompt (or null = nothing pending) to the push payload.
     * `expiresAt` matches the phone dialog's auto-deny instant so the watch
     * countdown and the phone timeout expire together.
     */
    fun build(
        prompt: Nip46SignPrompt?,
        bunkerEnabled: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): WearBunkerRequest {
        if (prompt == null) {
            return WearBunkerRequest(requestId = null, bunkerEnabled = bunkerEnabled, syncedAt = nowMs)
        }
        return WearBunkerRequest(
            requestId = prompt.requestId,
            label = prompt.label.take(MAX_LABEL_CHARS),
            method = prompt.method,
            eventKind = prompt.eventKind,
            preview = prompt.unsignedEventJson?.take(MAX_PREVIEW_CHARS),
            promptedAt = nowMs,
            expiresAt = nowMs + PROMPT_TIMEOUT_SECONDS * 1000,
            bunkerEnabled = bunkerEnabled,
            syncedAt = nowMs
        )
    }
}
