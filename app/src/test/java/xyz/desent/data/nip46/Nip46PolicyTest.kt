package xyz.desent.data.nip46

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip46PolicyTest {

    // ------------------------------------------------------------------
    // Grants ("remember my choice" checkbox)
    // ------------------------------------------------------------------

    @Test
    fun `grant for sign_event kind overrides prompt`() {
        // kind 13 (seal) is not auto-approved in general-nostr…
        assertEquals(Nip46Decision.PROMPT, Nip46Policy.decide(Nip46PermissionProfile.GENERAL_NOSTR, Nip46Method.SIGN_EVENT, 13))
        // …but an explicit grant short-circuits to ALLOW.
        assertEquals(
            Nip46Decision.ALLOW,
            Nip46Policy.decide(Nip46PermissionProfile.GENERAL_NOSTR, Nip46Method.SIGN_EVENT, 13, mapOf("sign_event:13" to true))
        )
    }

    @Test
    fun `grant does not leak across kinds`() {
        val grants = mapOf("sign_event:13" to true)
        assertEquals(Nip46Decision.PROMPT, Nip46Policy.decide(Nip46PermissionProfile.GENERAL_NOSTR, Nip46Method.SIGN_EVENT, 14, grants))
    }

    @Test
    fun `grant cannot override identity-only deny`() {
        // Even a (hypothetical) grant never upgrades a profile-level DENY —
        // the UI never offers the checkbox for DENY decisions in the first
        // place; this guards against grants leaking in from elsewhere.
        assertEquals(
            Nip46Decision.DENY,
            Nip46Policy.decide(Nip46PermissionProfile.IDENTITY_ONLY, Nip46Method.SIGN_EVENT, 1, mapOf("sign_event:1" to true))
        )
    }

    @Test
    fun `method grants override prompt`() {
        assertEquals(Nip46Decision.PROMPT, Nip46Policy.decide(Nip46PermissionProfile.GENERAL_NOSTR, Nip46Method.NIP44_ENCRYPT))
        assertEquals(
            Nip46Decision.ALLOW,
            Nip46Policy.decide(Nip46PermissionProfile.GENERAL_NOSTR, Nip46Method.NIP44_ENCRYPT, grants = mapOf("nip44_encrypt" to true))
        )
    }

    @Test
    fun `grant key format`() {
        assertEquals("sign_event:13", Nip46Grant.key(Nip46Method.SIGN_EVENT, 13))
        assertEquals("sign_event", Nip46Grant.key(Nip46Method.SIGN_EVENT, null))
        assertEquals("nip44_encrypt", Nip46Grant.key(Nip46Method.NIP44_ENCRYPT, null))
        // kind refinement only applies to sign_event
        assertEquals("nip44_decrypt", Nip46Grant.key(Nip46Method.NIP44_DECRYPT, 13))
    }

    // ------------------------------------------------------------------
    // New standard methods
    // ------------------------------------------------------------------

    @Test
    fun `ping get_relays switch_relays logout are allowed everywhere`() {
        for (profile in Nip46PermissionProfile.entries) {
            for (method in listOf(Nip46Method.PING, Nip46Method.GET_RELAYS, Nip46Method.SWITCH_RELAYS, Nip46Method.LOGOUT)) {
                assertEquals("$profile/$method", Nip46Decision.ALLOW, Nip46Policy.decide(profile, method))
            }
        }
    }

    // ------------------------------------------------------------------
    // Matrix regressions (original behavior preserved)
    // ------------------------------------------------------------------

    @Test
    fun `get_public_key always allowed`() {
        for (profile in Nip46PermissionProfile.entries) {
            assertEquals(Nip46Decision.ALLOW, Nip46Policy.decide(profile, Nip46Method.GET_PUBLIC_KEY))
        }
    }

    @Test
    fun `inbox profile auto-approves nip98 kinds`() {
        for (kind in listOf(0L, 5L, 22242L, 27235L)) {
            assertTrue(Nip46Policy.isAutoApproveKind(Nip46PermissionProfile.DESENT_INBOX, kind))
            assertEquals(Nip46Decision.ALLOW, Nip46Policy.decide(Nip46PermissionProfile.DESENT_INBOX, Nip46Method.SIGN_EVENT, kind))
        }
        assertEquals(Nip46Decision.PROMPT, Nip46Policy.decide(Nip46PermissionProfile.DESENT_INBOX, Nip46Method.SIGN_EVENT, 1))
    }

    @Test
    fun `general-nostr auto-approves common social kinds`() {
        for (kind in listOf(0L, 1L, 3L, 5L, 6L, 7L, 9735L, 10002L, 10050L, 30078L)) {
            assertTrue("kind $kind", Nip46Policy.isAutoApproveKind(Nip46PermissionProfile.GENERAL_NOSTR, kind))
        }
        // DM-adjacent kinds still prompt
        for (kind in listOf(4L, 13L, 14L)) {
            assertEquals("kind $kind", Nip46Decision.PROMPT, Nip46Policy.decide(Nip46PermissionProfile.GENERAL_NOSTR, Nip46Method.SIGN_EVENT, kind))
        }
    }

    @Test
    fun `identity-only denies all signing`() {
        assertEquals(Nip46Decision.DENY, Nip46Policy.decide(Nip46PermissionProfile.IDENTITY_ONLY, Nip46Method.SIGN_EVENT, 1))
        assertEquals(Nip46Decision.DENY, Nip46Policy.decide(Nip46PermissionProfile.IDENTITY_ONLY, Nip46Method.NIP44_ENCRYPT))
        assertEquals(Nip46Decision.DENY, Nip46Policy.decide(Nip46PermissionProfile.IDENTITY_ONLY, Nip46Method.NIP04_DECRYPT))
        assertEquals(Nip46Decision.DENY, Nip46Policy.decide(Nip46PermissionProfile.IDENTITY_ONLY, Nip46Method.NIP59_UNWRAP))
    }

    @Test
    fun `delegate prompts only for general-nostr`() {
        assertEquals(Nip46Decision.PROMPT, Nip46Policy.decide(Nip46PermissionProfile.GENERAL_NOSTR, Nip46Method.DELEGATE))
        assertEquals(Nip46Decision.DENY, Nip46Policy.decide(Nip46PermissionProfile.DESENT_INBOX, Nip46Method.DELEGATE))
        assertEquals(Nip46Decision.DENY, Nip46Policy.decide(Nip46PermissionProfile.IDENTITY_ONLY, Nip46Method.DELEGATE))
    }

    @Test
    fun `nip59_unwrap matrix unchanged`() {
        assertEquals(Nip46Decision.ALLOW, Nip46Policy.decide(Nip46PermissionProfile.DESENT_INBOX, Nip46Method.NIP59_UNWRAP))
        assertEquals(Nip46Decision.ALLOW, Nip46Policy.decide(Nip46PermissionProfile.DESENT_MANAGE, Nip46Method.NIP59_UNWRAP))
        assertEquals(Nip46Decision.PROMPT, Nip46Policy.decide(Nip46PermissionProfile.GENERAL_NOSTR, Nip46Method.NIP59_UNWRAP))
        assertEquals(Nip46Decision.DENY, Nip46Policy.decide(Nip46PermissionProfile.IDENTITY_ONLY, Nip46Method.NIP59_UNWRAP))
    }

    @Test
    fun `unknown method denied`() {
        assertEquals(Nip46Decision.DENY, Nip46Policy.decide(Nip46PermissionProfile.GENERAL_NOSTR, "make_coffee"))
    }

    // ------------------------------------------------------------------
    // Suggested profile for scanned URIs
    // ------------------------------------------------------------------

    @Test
    fun `desent permissions param wins`() {
        assertEquals(
            Nip46PermissionProfile.DESENT_MANAGE,
            Nip46PermissionProfile.suggested("desent-manage", emptyList(), Nip46Transport.GIFT_WRAP)
        )
    }

    @Test
    fun `external perms with social kinds suggest general-nostr`() {
        val perms = parsePerms("nip44_encrypt,nip44_decrypt,sign_event:13,sign_event:14,sign_event:1059")
        assertEquals(Nip46PermissionProfile.GENERAL_NOSTR, Nip46PermissionProfile.suggested(null, perms, Nip46Transport.RAW))
    }

    @Test
    fun `desent-only perms suggest inbox`() {
        val perms = parsePerms("sign_event:22242,sign_event:27235")
        assertEquals(Nip46PermissionProfile.DESENT_INBOX, Nip46PermissionProfile.suggested(null, perms, Nip46Transport.GIFT_WRAP))
    }

    @Test
    fun `no perms defaults by transport`() {
        assertEquals(Nip46PermissionProfile.GENERAL_NOSTR, Nip46PermissionProfile.suggested(null, emptyList(), Nip46Transport.RAW))
        assertEquals(Nip46PermissionProfile.DESENT_INBOX, Nip46PermissionProfile.suggested(null, emptyList(), Nip46Transport.GIFT_WRAP))
    }

    @Test
    fun `unknown permissions param falls through to transport default`() {
        assertEquals(
            Nip46PermissionProfile.DESENT_INBOX,
            Nip46PermissionProfile.suggested("weird-profile", emptyList(), Nip46Transport.GIFT_WRAP)
        )
        assertNull(Nip46PermissionProfile.fromQr("weird-profile"))
        assertNull(Nip46PermissionProfile.fromQr(null))
        assertTrue(Nip46Transport.RAW.isRaw)
        assertFalse(Nip46Transport.GIFT_WRAP.isRaw)
    }
}
