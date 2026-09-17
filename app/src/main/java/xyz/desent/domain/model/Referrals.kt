package xyz.desent.domain.model

import java.time.OffsetDateTime

/** Server-side registration gating mode (GET /api/register/mode). */
enum class RegistrationMode {
    /** Anyone may claim an address; an invite code is optional (attribution only). */
    OPEN,
    /** A valid, unused invite code is required to register. */
    REFERRAL,
    /** Registration closed. */
    DISABLED;

    companion object {
        /** Parse the wire string; null/unknown falls back to [OPEN] (the server
         *  enforces the real mode on POST /register regardless). */
        fun fromWire(value: String?): RegistrationMode = when (value?.trim()?.lowercase()) {
            "referral" -> REFERRAL
            "disabled" -> DISABLED
            else -> OPEN
        }
    }
}

/** A single invite code (DS-XXXXXX-XXXXXX, single-use). */
data class InviteCode(
    val code: String,
    val used: Boolean,
    /** Redeeming pubkey as raw hex; render as npub, never the full hex. */
    val usedBy: String? = null,
    val createdAt: Long? = null,
    val usedAt: Long? = null
)

/** The caller's invite codes plus the per-user cap (GET /api/referrals). */
data class InviteCodes(
    val codes: List<InviteCode>,
    val cap: Int
) {
    val availableCount: Int get() = codes.count { !it.used }
}

/** ISO-8601 with offset (e.g. 2026-08-03T20:28:31.075+00:00) → epoch millis. */
internal fun parseIsoEpochMillis(value: String?): Long? =
    value?.let { s -> runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull() }
