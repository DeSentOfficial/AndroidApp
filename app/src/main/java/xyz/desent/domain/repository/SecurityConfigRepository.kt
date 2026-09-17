package xyz.desent.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.desent.domain.model.SecurityAlertMode
import xyz.desent.domain.model.SecurityConfig

/**
 * The security slice of the DeSent user settings (kind 30079,
 * `d = "desent_user_settings"`; refs/FromServer/USER_SETTINGS_PROTOCOL.md).
 *
 * Deliberately its own configuration — separate from the kind-35050
 * MailboxConfig and the 30078 spam-settings namespace — because publishes
 * are partial payloads touching ONLY `security_alerts`, auto-purge fields
 * are never written from here, and the event is quota-exempt so the toggle
 * works even over storage cap.
 */
interface SecurityConfigRepository {

    /** The cached security configuration for [ownerNpub], or null when none published. */
    fun observe(ownerNpub: String): Flow<SecurityConfig?>

    suspend fun get(ownerNpub: String): SecurityConfig?

    /**
     * Publish the alert mode as a partial kind-30079 payload
     * (`{"security_alerts": "<mode>"}` only). Mirrors locally on success so
     * the UI reflects the change immediately; the relay echo confirms later
     * (LWW by created_at).
     */
    suspend fun setAlertMode(ownerNpub: String, mode: SecurityAlertMode): Result<Unit>

    /**
     * Publish `pgp_auto_encrypt` as its own partial kind-30079 payload
     * (`{"pgp_auto_encrypt": bool}` only — ANDROID_PGP.md §5). Client-only
     * preference; the 30079 event content IS the cross-device sync.
     */
    suspend fun setPgpAutoEncrypt(ownerNpub: String, enabled: Boolean): Result<Unit>

    /**
     * Publish the relay-mirroring opt-in as its own partial kind-30079
     * payload (`{"dm_fanout": bool}` only — ANDROID_DM_FANOUT.md §2).
     * Unlike the other fields this publish AWAITS the relay verdict: an
     * unentitled author is rejected with `OK false "premium required: …"`,
     * surfaced as [xyz.desent.domain.model.PremiumRequiredException] —
     * never auto-retry. The local mirror is written only on success.
     */
    suspend fun setDmFanout(ownerNpub: String, enabled: Boolean): Result<Unit>

    /** Re-subscribe to the settings event (called on login / account switch). */
    suspend fun refresh(): Result<Unit>
}
