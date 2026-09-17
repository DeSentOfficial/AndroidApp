package xyz.desent.data.agents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.desent.crypto.Nip49
import java.security.SecureRandom
import nostr.id.Identity

/**
 * Generates an agent's own Nostr keypair ON-DEVICE and builds the one-time
 * connection code for handoff to the agent (ANDROID_AI_AGENTS.md §3).
 *
 * The private key is NEVER stored and NEVER sent over the network — only
 * the 64-hex npub goes into `POST /api/agents`. The connection code
 * (NIP-49 ncryptsec + random passphrase) lives only in memory between
 * generation and the one-time handoff screen; after dismissal the secret
 * is unrecoverable.
 */
object AgentKeyFactory {

    class Generated(
        /** 64-hex x-only public key — the only value that goes to the server. */
        val agentPubkeyHex: String,
        val connectionCode: xyz.desent.domain.model.AgentConnectionCode
    )

    /**
     * Generate the keypair and encrypt it under a fresh random passphrase
     * (base36, 16 chars). Do this BEFORE the network call so a create
     * failure discards the secret cleanly.
     */
    suspend fun generate(): Result<Generated> = withContext(Dispatchers.Default) {
        try {
            val random = SecureRandom()
            val secretBytes = ByteArray(Nip49.KEY_BYTES)
            random.nextBytes(secretBytes)
            val secretHex = secretBytes.joinToString("") { "%02x".format(it) }

            val identity = Identity.create(secretHex)
            val pubkeyHex = identity.publicKey.toHexString()

            val passphrase = randomBase36(random, 16)
            val ncryptsec = Nip49.encrypt(secretBytes, passphrase, logN = Nip49.DEFAULT_LOG_N)

            // Wipe the plaintext secret; only the encrypted form survives.
            secretBytes.fill(0)

            Result.success(
                Generated(
                    agentPubkeyHex = pubkeyHex,
                    connectionCode = xyz.desent.domain.model.AgentConnectionCode(
                        ncryptsec = ncryptsec,
                        passphrase = passphrase
                    )
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun randomBase36(random: SecureRandom, length: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}
