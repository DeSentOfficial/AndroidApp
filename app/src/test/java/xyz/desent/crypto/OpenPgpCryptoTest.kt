package xyz.desent.crypto

import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip guarantees for the OpenPGP armor contract
 * (refs/FROM_email.desent.xyz/ANDROID_PGP.md §6): everything on the wire is
 * standard armor, so correctness = generate → encrypt → decrypt → identical
 * plaintext, plus the passphraseless re-arm on import.
 */
class OpenPgpCryptoTest {

    private val uid = "Alice <alice@desent.xyz>"

    @Test
    fun generate_producesValidArmorAndFingerprint() {
        val material = OpenPgpCrypto.generate(uid)

        assertTrue(material.secretArmored.startsWith(OpenPgpCrypto.SECRET_ARMOR_HEADER))
        assertTrue(material.publicArmored.startsWith(OpenPgpCrypto.PUBLIC_ARMOR_HEADER))
        // v4 fingerprint: 40 hex, uppercase.
        assertEquals(40, material.fingerprint.length)
        assertTrue(material.fingerprint.all { it in "0123456789ABCDEF" })

        // The fingerprint matches the ring's master key (armor-decoded).
        val ringBytes = java.io.ByteArrayInputStream(material.secretArmored.toByteArray()).use { ins ->
            org.bouncycastle.bcpg.ArmoredInputStream(ins).use { it.readBytes() }
        }
        val ring = PGPSecretKeyRing(ringBytes, BcKeyFingerprintCalculator())
        assertEquals(
            material.fingerprint,
            ring.publicKey.fingerprint.joinToString("") { "%02x".format(it) }.uppercase()
        )

        // The public half carries an encryption-capable key (X25519 subkey).
        assertTrue(OpenPgpCrypto.hasEncryptionKey(material.publicArmored))
    }

    @Test
    fun encryptDecrypt_roundTripsUtf8Plaintext() {
        val material = OpenPgpCrypto.generate(uid)
        val plaintext = "Héllo wörld — こんにちは 🌍\nsecond line"

        val armor = OpenPgpCrypto.encrypt(plaintext, material.publicArmored).getOrThrow()

        assertTrue(armor.startsWith(OpenPgpCrypto.MESSAGE_ARMOR_HEADER))

        val decrypted = OpenPgpCrypto.decryptText(armor, material.secretArmored).getOrThrow()
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun decrypt_withWrongKey_fails() {
        val sender = OpenPgpCrypto.generate("Sender <s@desent.xyz>")
        val other = OpenPgpCrypto.generate("Other <o@desent.xyz>")

        val armor = OpenPgpCrypto.encrypt("secret", sender.publicArmored).getOrThrow()

        // Decrypting with an unrelated secret ring must fail loudly.
        assertTrue(OpenPgpCrypto.decrypt(armor, other.secretArmored).isFailure)
    }

    @Test
    fun decrypt_tamperedCiphertext_failsIntegrityCheck() {
        val material = OpenPgpCrypto.generate(uid)
        val armor = OpenPgpCrypto.encrypt("secret", material.publicArmored).getOrThrow()

        // Flip a base64 char in the last data line (not the armor headers).
        val lines = armor.lines().toMutableList()
        val idx = lines.indexOfLast { it.startsWith("hQ") || (it.length > 20 && it.first() in 'A'..'Z' && it.last() != '=') }
        val target = if (idx >= 0) idx else lines.size - 3
        val chars = lines[target].toMutableList()
        chars[0] = if (chars[0] == 'A') 'B' else 'A'
        lines[target] = chars.joinToString("")
        val tampered = lines.joinToString("\n")

        assertTrue(OpenPgpCrypto.decrypt(tampered, material.secretArmored).isFailure)
    }

    @Test
    fun importAndReArmor_passphraselessRing_roundTrips() {
        val original = OpenPgpCrypto.generate(uid)

        val imported = OpenPgpCrypto.importAndReArmor(original.secretArmored, null).getOrThrow()

        // Same key identity, re-encoded without a passphrase.
        assertEquals(original.fingerprint, imported.fingerprint)
        assertTrue(imported.secretArmored.startsWith(OpenPgpCrypto.SECRET_ARMOR_HEADER))

        // Round-trip through the re-armed ring still works.
        val armor = OpenPgpCrypto.encrypt("after import", imported.publicArmored).getOrThrow()
        assertEquals("after import", OpenPgpCrypto.decryptText(armor, imported.secretArmored).getOrThrow())
    }

    @Test
    fun importAndReArmor_passphraseProtectedKey_unlocksOnceAndReArms() {
        val original = OpenPgpCrypto.generate(uid)

        // Build a passphrase-protected variant exactly like an exported key
        // from GnuPG/Proton would arrive.
        val passphrase = "correct horse battery staple".toCharArray()
        val bytes = java.io.ByteArrayInputStream(original.secretArmored.toByteArray()).use { ins ->
            org.bouncycastle.bcpg.ArmoredInputStream(ins).use { it.readBytes() }
        }
        val decryptor = org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder(
            BcPGPDigestCalculatorProvider()
        ).build(CharArray(0))
        val encryptor = BcPBESecretKeyEncryptorBuilder(
            org.bouncycastle.bcpg.HashAlgorithmTags.SHA256,
            BcPGPDigestCalculatorProvider().get(org.bouncycastle.bcpg.HashAlgorithmTags.SHA256)
        ).build(passphrase)
        val protectedRing = PGPSecretKeyRing.copyWithNewPassword(
            PGPSecretKeyRing(bytes, BcKeyFingerprintCalculator()),
            decryptor,
            encryptor
        )
        val protectedArmor = java.io.ByteArrayOutputStream().use { out ->
            org.bouncycastle.bcpg.ArmoredOutputStream(out).use { it.write(protectedRing.encoded) }
            out.toString("UTF-8")
        }

        // Wrong passphrase fails BEFORE anything is persisted.
        assertTrue(OpenPgpCrypto.importAndReArmor(protectedArmor, "wrong".toCharArray()).isFailure)

        // Correct passphrase: unlocked once, re-armed passphraseless.
        val imported = OpenPgpCrypto.importAndReArmor(protectedArmor, passphrase).getOrThrow()
        assertEquals(original.fingerprint, imported.fingerprint)

        // The re-armed ring decrypts with NO passphrase.
        val armor = OpenPgpCrypto.encrypt("via import", imported.publicArmored).getOrThrow()
        assertEquals("via import", OpenPgpCrypto.decryptText(armor, imported.secretArmored).getOrThrow())
    }

    @Test
    fun importAndReArmor_rejectsNonSecretBlocks() {
        val material = OpenPgpCrypto.generate(uid)
        assertTrue(OpenPgpCrypto.importAndReArmor(material.publicArmored, null).isFailure)
        assertTrue(OpenPgpCrypto.importAndReArmor("not pgp at all", null).isFailure)
    }

    @Test
    fun publicArmoredOf_extractsThePublicHalf() {
        val material = OpenPgpCrypto.generate(uid)
        val pub = OpenPgpCrypto.publicArmoredOf(material.secretArmored)
        assertTrue(pub.startsWith(OpenPgpCrypto.PUBLIC_ARMOR_HEADER))
        assertTrue(OpenPgpCrypto.hasEncryptionKey(pub))
    }

    @Test
    fun sniffHtml_detectsHtmlRootTagsOnly() {
        assertTrue(OpenPgpCrypto.sniffHtml("<html><body>hi</body></html>"))
        assertTrue(OpenPgpCrypto.sniffHtml("  \n<div>hi</div>"))
        assertTrue(OpenPgpCrypto.sniffHtml("<p>para</p>"))
        assertFalse(OpenPgpCrypto.sniffHtml("just plain text"))
        assertFalse(OpenPgpCrypto.sniffHtml("talking about the <html> tag mid-sentence"))
    }
}
