package xyz.desent.crypto

import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date

/**
 * OpenPGP armor crypto for DeSent mail E2E encryption (refs/FromServer/
 * PGP_ENCRYPTION.md + ANDROID_PGP.md). Pure JVM — no Android dependencies —
 * so it is directly unit-testable.
 *
 * Wire contract: everything that goes over Nostr or HTTP is an ASCII armor
 * string; nothing DeSent-specific. Encryption uses AES-256 + MDC integrity,
 * literal-data format BINARY carrying UTF-8 text, no compression (bodies are
 * capped at 10 KiB; every OpenPGP implementation reads uncompressed literal
 * data).
 *
 * Curve25519 is done through Bouncy Castle's *lightweight* API (Ed25519 sign
 * master + X25519 ECDH encrypt subkey) because Android's JCA providers do not
 * expose Ed25519/XDH below API 33 (app minSdk 26). Import accepts any valid
 * secret ring (RSA/ECDSA/ECDH/EdDSA) and re-arms it passphraseless.
 */
object OpenPgpCrypto {

    /** The armor header every encrypted rumor content must start with (server contract). */
    const val MESSAGE_ARMOR_HEADER = "-----BEGIN PGP MESSAGE-----"
    const val PUBLIC_ARMOR_HEADER = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
    const val SECRET_ARMOR_HEADER = "-----BEGIN PGP PRIVATE KEY BLOCK-----"

    /** One PGP identity: armored halves + v4 fingerprint (40 hex, uppercase). */
    data class KeyMaterial(
        val secretArmored: String,
        val publicArmored: String,
        val fingerprint: String
    )

    private val fingerprintCalc = BcKeyFingerprintCalculator()
    private val digestCalcProvider = BcPGPDigestCalculatorProvider()

    // ------------------------------------------------------------------
    // Key generation / import
    // ------------------------------------------------------------------

    /**
     * Generate a fresh passphraseless curve25519 key ring:
     * Ed25519 (EdDSA legacy algo 27) signing master + X25519 ECDH encryption
     * subkey, UID = [userId]. The NIP-44-to-self kind-30078 copy is the
     * at-rest protection (ANDROID_PGP.md §2.1).
     */
    fun generate(userId: String): KeyMaterial {
        val random = SecureRandom()
        val now = Date()

        val masterGen = Ed25519KeyPairGenerator()
        masterGen.init(Ed25519KeyGenerationParameters(random))
        val master = BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA_LEGACY, masterGen.generateKeyPair(), now)

        val subGen = X25519KeyPairGenerator()
        subGen.init(X25519KeyGenerationParameters(random))
        val encryptSub = BcPGPKeyPair(PublicKeyAlgorithmTags.ECDH, subGen.generateKeyPair(), now)

        val masterHashed = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
            setPreferredSymmetricAlgorithms(
                false,
                intArrayOf(
                    SymmetricKeyAlgorithmTags.AES_256,
                    SymmetricKeyAlgorithmTags.AES_192,
                    SymmetricKeyAlgorithmTags.AES_128
                )
            )
            setPreferredHashAlgorithms(
                false,
                intArrayOf(
                    HashAlgorithmTags.SHA512,
                    HashAlgorithmTags.SHA384,
                    HashAlgorithmTags.SHA256
                )
            )
            setPreferredCompressionAlgorithms(
                false,
                intArrayOf(
                    CompressionAlgorithmTags.ZLIB,
                    CompressionAlgorithmTags.BZIP2,
                    CompressionAlgorithmTags.ZIP
                )
            )
        }.generate()

        val subHashed = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
        }.generate()

        val signerBuilder = BcPGPContentSignerBuilder(master.publicKey.algorithm, HashAlgorithmTags.SHA256)

        val ringGen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            master,
            userId,
            digestCalcProvider.get(HashAlgorithmTags.SHA256),
            masterHashed,
            null,
            signerBuilder,
            null // passphraseless: NIP-44-to-self 30078 is the at-rest layer
        )
        // 3-arg addSubKey: the binding signature is made by the generator's
        // own master-key signer (passing a subkey-specific signer builder
        // would init it with the X25519 subkey and blow up).
        ringGen.addSubKey(encryptSub, subHashed, null)

        val secretRing = ringGen.generateSecretKeyRing()
        val publicRing = ringGen.generatePublicKeyRing()
        return KeyMaterial(
            secretArmored = armor(secretRing.encoded),
            publicArmored = armor(publicRing.encoded),
            fingerprint = fingerprintOf(secretRing)
        )
    }

    /**
     * Import an armored secret key block. If it is passphrase-protected it is
     * unlocked ONCE with [passphrase] and re-armed WITHOUT the passphrase
     * before anything is persisted (custody contract, ANDROID_PGP.md §2).
     * Returns the re-armed [KeyMaterial] with `source: imported` semantics
     * left to the caller.
     */
    fun importAndReArmor(secretArmored: String, passphrase: CharArray?): Result<KeyMaterial> = runCatching {
        val src = parseSecretRing(secretArmored)

        val decryptor = secretKeyDecryptor(passphrase)
        // Unlock + re-arm WITHOUT the passphrase in one step; a wrong
        // passphrase fails here, before anything is persisted.
        val rebuilt = PGPSecretKeyRing.copyWithNewPassword(src, decryptor, null)
        KeyMaterial(
            secretArmored = armor(rebuilt.encoded),
            publicArmored = armor(publicRingOf(rebuilt).encoded),
            fingerprint = fingerprintOf(rebuilt)
        )
    }

    /** 40-hex uppercase fingerprint of a secret ring's master key. */
    fun fingerprint(secretArmored: String): String = fingerprintOf(parseSecretRing(secretArmored))

    /** The armored PUBLIC half of a secret ring (for PUT /api/pgp/key). */
    fun publicArmoredOf(secretArmored: String): String =
        armor(publicRingOf(parseSecretRing(secretArmored)).encoded)

    /**
     * True when the armored block carries a key/subkey usable for encryption
     * (mirrors the server's `422` validation so the client can pre-check).
     */
    fun hasEncryptionKey(publicArmored: String): Boolean =
        encryptionKey(parsePublicRing(publicArmored)) != null

    // ------------------------------------------------------------------
    // Encrypt / decrypt
    // ------------------------------------------------------------------

    /**
     * Encrypt [plaintext] to the recipient's armored public key. Returns an
     * armored PGP MESSAGE block (starts with [MESSAGE_ARMOR_HEADER]).
     */
    fun encrypt(plaintext: ByteArray, recipientPublicArmored: String): Result<String> = runCatching {
        val encKey = encryptionKey(parsePublicRing(recipientPublicArmored))
            ?: throw PGPException("No encryption-capable key in recipient's public key")

        val encryptor = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom())
        )
        encryptor.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(encKey))

        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armored ->
            BCPGOutputStream(armored).use { bcpg ->
                encryptor.open(bcpg, ByteArray(BUFFER_SIZE)).use { encOut ->
                    val litGen = PGPLiteralDataGenerator()
                    litGen.open(encOut, PGPLiteralData.BINARY, PGPLiteralData.CONSOLE, Date(), ByteArray(BUFFER_SIZE))
                        .use { litOut -> litOut.write(plaintext) }
                    litGen.close()
                }
            }
        }
        out.toString("UTF-8")
    }

    fun encrypt(plaintext: String, recipientPublicArmored: String): Result<String> =
        encrypt(plaintext.toByteArray(Charsets.UTF_8), recipientPublicArmored)

    /**
     * Decrypt an armored PGP MESSAGE with the user's armored (passphraseless)
     * secret key. Verifies the MDC integrity packet when present — a tampered
     * ciphertext fails loudly instead of yielding garbage.
     */
    fun decrypt(armoredMessage: String, secretArmored: String): Result<ByteArray> = runCatching {
        val secretRing = parseSecretRing(secretArmored)
        val decryptor = secretKeyDecryptor(null)

        val factory = PGPObjectFactory(decodeArmored(armoredMessage), fingerprintCalc)
        var obj = factory.nextObject()
        var plaintext: ByteArray? = null
        while (obj != null && plaintext == null) {
            when (obj) {
                is PGPEncryptedDataList -> {
                    for (item in obj) {
                        val encData = item as? PGPPublicKeyEncryptedData ?: continue
                        val secretKey = secretRing.getSecretKey(encData.keyID) ?: continue
                        val priv = secretKey.extractPrivateKey(decryptor)
                        val clear = encData.getDataStream(BcPublicKeyDataDecryptorFactory(priv))
                        plaintext = readLiteral(clear, encData)
                        break
                    }
                    if (plaintext == null) throw PGPException("No decryptable session key for this key ring")
                }
                is PGPCompressedData -> {
                    // Uncompressed envelope wrapped in compression (common
                    // from GnuPG senders): unwrap and keep walking.
                    val inner = PGPObjectFactory(obj.dataStream, fingerprintCalc)
                    var sub = inner.nextObject()
                    while (sub != null && plaintext == null) {
                        when (sub) {
                            is PGPLiteralData -> {
                                plaintext = sub.inputStream.readBytes()
                            }
                            is PGPEncryptedDataList -> {
                                for (item in sub) {
                                    val encData = item as? PGPPublicKeyEncryptedData ?: continue
                                    val secretKey = secretRing.getSecretKey(encData.keyID) ?: continue
                                    val priv = secretKey.extractPrivateKey(decryptor)
                                    val clear = encData.getDataStream(BcPublicKeyDataDecryptorFactory(priv))
                                    plaintext = readLiteral(clear, encData)
                                    break
                                }
                            }
                            else -> {
                                // signatures and other packets are skipped
                            }
                        }
                        sub = inner.nextObject()
                    }
                }
                is PGPLiteralData -> {
                    plaintext = obj.inputStream.readBytes()
                }
                else -> {
                    // marker packet / signatures: skip
                }
            }
            obj = factory.nextObject()
        }
        plaintext ?: throw PGPException("No literal data packet found in PGP MESSAGE")
    }

    fun decryptText(armoredMessage: String, secretArmored: String): Result<String> =
        decrypt(armoredMessage, secretArmored).map { String(it, Charsets.UTF_8) }

    /**
     * Sniff a decrypted body for the render format (ANDROID_PGP.md §3.2):
     * HTML when it starts with a common HTML root tag, else plain text.
     */
    fun sniffHtml(plaintext: String): Boolean {
        val head = plaintext.trimStart().take(16).lowercase()
        return head.startsWith("<html") || head.startsWith("<body") ||
            head.startsWith("<div") || head.startsWith("<p")
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private const val BUFFER_SIZE = 1 shl 16

    private fun readLiteral(clear: java.io.InputStream, encData: PGPPublicKeyEncryptedData): ByteArray {
        val inner = PGPObjectFactory(clear, fingerprintCalc)
        var sub = inner.nextObject()
        while (sub != null) {
            if (sub is PGPLiteralData) {
                val bytes = sub.inputStream.readBytes()
                if (encData.isIntegrityProtected && !encData.verify()) {
                    throw PGPException("Integrity check failed — message was tampered with")
                }
                return bytes
            }
            sub = inner.nextObject()
        }
        throw PGPException("No literal data packet inside encrypted data")
    }

    private fun secretKeyDecryptor(passphrase: CharArray?): PBESecretKeyDecryptor =
        BcPBESecretKeyDecryptorBuilder(digestCalcProvider).build(passphrase ?: CharArray(0))

    private fun parseSecretRing(armored: String): PGPSecretKeyRing {
        if (!armored.contains(SECRET_ARMOR_HEADER)) {
            throw PGPException("Not an armored PGP PRIVATE KEY block")
        }
        return PGPSecretKeyRing(decodeArmored(armored), fingerprintCalc)
    }

    private fun parsePublicRing(armored: String): PGPPublicKeyRing {
        val bytes = decodeArmored(armored)
        return try {
            PGPPublicKeyRing(bytes, fingerprintCalc)
        } catch (e: Exception) {
            // Some WKD sources serve secret rings; tolerate by extracting the
            // public half rather than failing the lookup.
            runCatching { publicRingOf(PGPSecretKeyRing(bytes, fingerprintCalc)) }
                .getOrElse { throw PGPException("Not a parseable OpenPGP key block", e) }
        }
    }

    private fun encryptionKey(ring: PGPPublicKeyRing): PGPPublicKey? =
        ring.publicKeys.asSequence()
            .filter { !it.isRevoked && it.isEncryptionKey }
            .filter { it.algorithm != PublicKeyAlgorithmTags.EDDSA_LEGACY && it.algorithm != PublicKeyAlgorithmTags.EDDSA }
            .maxByOrNull { if (it.isMasterKey) 0 else 1 } // prefer the encryption subkey
            ?: ring.publicKeys.asSequence().firstOrNull { !it.isRevoked && it.isEncryptionKey }

    private fun fingerprintOf(ring: PGPSecretKeyRing): String =
        ring.publicKey.fingerprint.toHex().uppercase()

    /** Public-key view of a secret ring: master + subkeys in packet order. */
    private fun publicRingOf(ring: PGPSecretKeyRing): PGPPublicKeyRing {
        val publicKeys = ring.publicKeys.asSequence().toList()
        return PGPPublicKeyRing(publicKeys)
    }

    private fun armor(encoded: ByteArray): String =
        ByteArrayOutputStream(encoded.size + 256).use { out ->
            ArmoredOutputStream(out).use { it.write(encoded) }
            out.toString("UTF-8")
        }

    private fun decodeArmored(armored: String): ByteArray =
        ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)).use { bytes ->
            ArmoredInputStream(bytes).use { it.readBytes() }
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
