package xyz.desent.presentation.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationShortcutManagerTest {

    @Test
    fun `shortcut id is stable for the same sender`() {
        val a = ConversationShortcutManager.shortcutIdFor("Alice@example.com")
        val b = ConversationShortcutManager.shortcutIdFor("alice@example.com")
        val padded = ConversationShortcutManager.shortcutIdFor("  alice@example.com ")
        assertEquals(a, b)
        assertEquals(a, padded)
    }

    @Test
    fun `shortcut ids differ across senders`() {
        val a = ConversationShortcutManager.shortcutIdFor("alice@example.com")
        val b = ConversationShortcutManager.shortcutIdFor("other@example.com")
        assertNotEquals(a, b)
    }

    @Test
    fun `shortcut id is shortcut-id safe`() {
        // Shortcut ids must not carry envelope characters like '@'; a
        // "sender-" + lowercase-hex digest satisfies every launcher.
        val id = ConversationShortcutManager.shortcutIdFor("weird! sender@@example.com")
        assertTrue(id.matches(Regex("sender-[0-9a-f]{16}")))
        assertFalse(id.contains('@'))
    }

    @Test
    fun `blank display name falls back to Email`() {
        assertEquals("Email", ConversationShortcutManager.shortLabelFor("   "))
        assertEquals("Email", ConversationShortcutManager.longLabelFor(""))
    }

    @Test
    fun `labels are truncated to launcher-recommended lengths`() {
        assertEquals("Alice Robe", ConversationShortcutManager.shortLabelFor("Alice Roberts"))
        assertEquals("A very long sender displa", ConversationShortcutManager.longLabelFor("A very long sender display name indeed"))
    }
}
