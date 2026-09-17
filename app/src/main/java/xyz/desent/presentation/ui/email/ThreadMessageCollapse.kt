package xyz.desent.presentation.ui.email

import xyz.desent.domain.model.Email
import xyz.desent.domain.model.EmailDirection
import xyz.desent.domain.model.EmailType

/**
 * Gmail-style thread collapse rule: everything strictly before the thread's
 * last real (non-SYSTEM) message starts collapsed to a one-line summary; the
 * newest message — and any delivery receipts after it, which report the state
 * of the latest send — stays expanded. A receipts-only thread expands all.
 */
fun defaultExpandedIds(messages: List<Email>): Set<String> {
    val lastReal = messages.indexOfLast { it.emailType != EmailType.SYSTEM }
    if (lastReal < 0) return messages.map { it.id }.toSet()
    return messages.drop(lastReal).map { it.id }.toSet()
}

/**
 * Reading mode vs conversation mode: broadcast mail (Dominos, Chase …) gets
 * the full screen width — the per-message avatar column only appears once
 * the user has joined the thread by sending a reply. Per-thread, not
 * per-message, so later inbound mail keeps the avatars after a response.
 */
fun hasUserResponse(messages: List<Email>): Boolean =
    messages.any { it.direction == EmailDirection.OUTBOUND }
