package xyz.desent.crypto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.desent.domain.model.BackupAccount
import xyz.desent.domain.model.BackupContents
import xyz.desent.domain.model.BackupRelay

/**
 * Validates the full pure-JVM path that [xyz.desent.data.repository.KeyBackupRepositoryImpl]
 * relies on: BackupContents → JSON → DSBK1 envelope → decrypt → JSON → BackupContents.
 */
class BackupSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fastParams = ScryptKdf.Params(log2N = 4, r = 8, p = 1)
    private val passphrase = "correct horse battery staple"

    @Test
    fun backupContentsRoundTripsThroughEnvelope() {
        val original = BackupContents(
            version = 1,
            createdAt = 1_700_000_000_000L,
            accounts = listOf(
                BackupAccount(
                    npub = "npub1aaaa",
                    nsec = "nsec1aaaa",
                    displayName = "Alice",
                    picture = "https://example.com/a.png",
                    nip05 = "alice@example.com",
                    requiresBiometrics = false,
                ),
                BackupAccount(npub = "npub1bbbb", nsec = "nsec1bbbb"),
            ),
            relays = listOf(
                BackupRelay(url = "wss://relay.example.com", isWrite = true),
                BackupRelay(url = "wss://inbox.example.com", isWrite = false),
            ),
        )

        val plaintext = json.encodeToString(original).toByteArray(Charsets.UTF_8)
        val blob = BackupEnvelope.pack(plaintext, passphrase, fastParams)
        val decrypted = BackupEnvelope.unpack(blob, passphrase)
        val restored = json.decodeFromString<BackupContents>(String(decrypted, Charsets.UTF_8))

        assertEquals(original, restored)
    }

    @Test
    fun emptyAccountsAndRelaysRoundTrip() {
        val original = BackupContents(createdAt = 0L, accounts = emptyList(), relays = emptyList())
        val plaintext = json.encodeToString(original).toByteArray(Charsets.UTF_8)
        val blob = BackupEnvelope.pack(plaintext, passphrase, fastParams)
        val restored = json.decodeFromString<BackupContents>(
            String(BackupEnvelope.unpack(blob, passphrase), Charsets.UTF_8)
        )
        assertEquals(original, restored)
    }
}
