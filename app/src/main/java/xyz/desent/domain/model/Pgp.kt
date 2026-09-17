package xyz.desent.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Content JSON of the kind-30078 `d = "desent:pgp"` private-storage event,
 * NIP-44-encrypted-to-self (PGP_ENCRYPTION.md § Storage). The private key is
 * passphraseless — this layer is the at-rest protection. Wire field names
 * are the server contract; do not rename.
 */
@Serializable
data class PgpKeyPayload(
    @SerialName("private_key_armored") val privateKeyArmored: String,
    @SerialName("public_key_armored") val publicKeyArmored: String,
    /** 40-hex uppercase. */
    val fingerprint: String,
    /** "generated" | "imported". */
    val source: String
)

/**
 * PGP end-to-end encryption domain models (refs/FROM_email.desent.xyz/
 * ANDROID_PGP.md). One key per account; the private half only ever lives in
 * kind 30078 `desent:pgp` (NIP-44-to-self) + the device's encrypted mirror.
 */
data class PgpKeyInfo(
    /** 40-hex uppercase v4 fingerprint. */
    val fingerprint: String,
    /** "generated" | "imported" — how the key entered the account. */
    val source: String,
    val publicArmored: String
) {
    /** Fingerprint pretty-printed in 4-char groups for display. */
    val prettyFingerprint: String
        get() = fingerprint.chunked(4).joinToString(" ")
}

/** Key state for the active account, surfaced to UI and the send path. */
sealed class PgpKeyState {
    data object NoKey : PgpKeyState()
    data class Available(val info: PgpKeyInfo) : PgpKeyState()
}

/** A decrypted PGP message ready for rendering. */
data class PgpDecryptedMessage(
    val body: String,
    val isHtml: Boolean,
    val attachments: List<PgpInnerAttachment> = emptyList()
)

/** An attachment extracted from inside the PGP envelope (inner MIME). */
data class PgpInnerAttachment(
    val filename: String,
    val mimeType: String,
    val data: ByteArray
) {
    val size: Long get() = data.size.toLong()
}

/** Reason a PGP message could not be decrypted on this device. */
enum class PgpDecryptFailure {
    /** No `desent:pgp` key on this account. */
    NO_KEY,

    /** A key exists but cannot decrypt this message. */
    WRONG_KEY,

    /** `pgp_enabled` is off on the relay (fail-closed gate). */
    FEATURE_DISABLED
}
