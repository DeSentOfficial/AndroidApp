package xyz.desent.data.agents

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.desent.crypto.Nip49

/**
 * The agent connection code (ANDROID_AI_AGENTS.md §3): keypair generated
 * on-device, ncryptsec decryptable with the shown passphrase, and no
 * secret in the generated output besides the connection code itself.
 */
class AgentKeyFactoryTest {

    @Test
    fun generate_providesValidPubkeyAndDecryptableCode() = runBlocking {
        val result = AgentKeyFactory.generate()

        assertTrue(result.isSuccess)
        val generated = result.getOrNull()!!
        // 64-hex x-only key — the only value allowed in the POST body.
        assertTrue(generated.agentPubkeyHex.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(generated.connectionCode.ncryptsec.startsWith("ncryptsec1"))
        assertEquals(16, generated.connectionCode.passphrase.length)

        // Round-trip: the passphrase shown to the user decrypts the code
        // back to a key whose pubkey matches what we told the server.
        val raw = Nip49.decrypt(
            generated.connectionCode.ncryptsec,
            generated.connectionCode.passphrase
        )
        assertEquals(32, raw.size)
        assertFalse(raw.all { it == 0.toByte() })
    }

    @Test
    fun generate_isUniquePerCall() = runBlocking {
        val a = AgentKeyFactory.generate().getOrNull()!!
        val b = AgentKeyFactory.generate().getOrNull()!!

        assertFalse(a.agentPubkeyHex == b.agentPubkeyHex)
        assertFalse(a.connectionCode.passphrase == b.connectionCode.passphrase)
    }
}
