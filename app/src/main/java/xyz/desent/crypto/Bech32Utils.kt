package xyz.desent.crypto

/**
 * Utility class for Bech32 encoding/decoding operations related to Nostr keys.
 * Uses a self-contained Bech32 implementation based on the Bitcoin reference.
 *
 * See: https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki
 */
object Bech32Utils {
    
    private const val NPUB_HRP = "npub"
    private const val NSEC_HRP = "nsec"
    private const val SEPARATOR = '1'
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    
    /**
     * Convert a hex public key to npub (Bech32) format.
     * Handles hex strings up to 64 characters, padding with leading zeros if needed.
     *
     * @param hex The hex-encoded public key (up to 64 characters)
     * @return The npub string (e.g., "npub1...")
     * @throws IllegalArgumentException if hex is invalid
     */
    fun hexToNpub(hex: String): String {
        require(hex.length <= 64) { "Hex public key must be at most 64 characters, got ${hex.length}" }
        require(hex.matches(Regex("[0-9a-f]{1,64}", RegexOption.IGNORE_CASE))) {
            "Invalid hex format"
        }
        
        val paddedHex = hex.padStart(64, '0')
        
        val bytes = paddedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val converted = convertBits(bytes, 8, 5)
        val npub = bech32Encode(NPUB_HRP, converted)
        
        return npub
    }
    
    /**
     * Convert an npub (Bech32) public key to 64-character hex format.
     *
     * @param npub The npub string (e.g., "npub1...")
     * @return The 64-character hex-encoded public key
     * @throws IllegalArgumentException if npub is invalid
     */
    fun npubToHex(npub: String): String {
        val (hrp, data) = bech32Decode(npub)
        require(hrp == NPUB_HRP) {
            "Invalid npub: expected hrp '$NPUB_HRP', got '$hrp'"
        }

        val bytes = convertBits(data, 5, 8, false)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Extract the pubkey from an nprofile (NIP-19 entity, Bech32 + TLV) and
     * return it as 64-character hex. The TLV payload carries the 32-byte
     * pubkey under type 0x00; relay hints (0x01) and other types are
     * ignored.
     *
     * @param nprofile The nprofile string (e.g., "nprofile1q...")
     * @return The 64-character hex-encoded public key
     * @throws IllegalArgumentException if the nprofile is invalid or carries no pubkey
     */
    fun nprofileToHex(nprofile: String): String {
        val (hrp, data) = bech32Decode(nprofile)
        require(hrp == "nprofile") {
            "Invalid nprofile: expected hrp 'nprofile', got '$hrp'"
        }
        val bytes = convertBits(data, 5, 8, false)
        var i = 0
        while (i + 1 < bytes.size) {
            val type = bytes[i]
            val length = bytes[i + 1]
            if (i + 2 + length > bytes.size) break
            if (type == 0 && length == 32) {
                return bytes.subList(i + 2, i + 2 + length)
                    .joinToString("") { "%02x".format(it) }
            }
            i += 2 + length
        }
        throw IllegalArgumentException("Invalid nprofile: no 32-byte pubkey TLV found")
    }
    
    /**
     * Convert a hex private key to nsec Bech32 format.
     * Handles hex strings up to 64 characters, padding with leading zeros if needed.
     *
     * @param hex The hex-encoded private key (up to 64 characters)
     * @return The nsec string (e.g., "nsec1...")
     * @throws IllegalArgumentException if hex is invalid
     */
    fun hexToNsec(hex: String): String {
        require(hex.length <= 64) { "Hex private key must be at most 64 characters, got ${hex.length}" }
        require(hex.matches(Regex("[0-9a-f]{1,64}", RegexOption.IGNORE_CASE))) {
            "Invalid hex format"
        }
        
        val paddedHex = hex.padStart(64, '0')
        
        val bytes = paddedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val converted = convertBits(bytes, 8, 5)
        val nsec = bech32Encode(NSEC_HRP, converted)
        
        return nsec
    }
    
    /**
     * Convert an nsec (Bech32) private key to 64-character hex format.
     *
     * @param nsec The nsec string (e.g., "nsec1...")
     * @return The 64-character hex-encoded private key
     * @throws IllegalArgumentException if nsec is invalid
     */
    fun nsecToHex(nsec: String): String {
        val (hrp, data) = bech32Decode(nsec)
        require(hrp == NSEC_HRP) {
            "Invalid nsec: expected hrp '$NSEC_HRP', got '$hrp'"
        }
        
        val bytes = convertBits(data, 5, 8, false)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Decode an arbitrary Bech32 string (any HRP, e.g. "lnurl") into its HRP
     * and raw data bytes. Verifies the checksum; throws on invalid input.
     */
    fun decodeGeneric(bech32: String): Pair<String, ByteArray> {
        val (hrp, data) = bech32Decode(bech32.lowercase())
        val bytes = convertBits(data, 5, 8, false).map { it.toByte() }
        return Pair(hrp, bytes.toByteArray())
    }

    /**
     * Encode raw data bytes as a Bech32 string with the given HRP (e.g.
     * "ncryptsec"). Inverse of [decodeGeneric].
     */
    fun encodeGeneric(hrp: String, bytes: ByteArray): String =
        bech32Encode(hrp, convertBits(bytes, 8, 5))

    /**
     * Encode data as Bech32 string with the given HRP (Human Readable Part).
     */
    private fun bech32Encode(hrp: String, data: List<Int>): String {
        val values = data.toMutableList()
        val checksum = bech32CreateChecksum(hrp, values)
        values.addAll(checksum)
        
        val encodedData = values.map { CHARSET[it] }.joinToString("")
        return "$hrp$SEPARATOR$encodedData"
    }
    
    /**
     * Decode a Bech32 string and return the HRP and data.
     */
    private fun bech32Decode(bech32: String): Pair<String, List<Int>> {
        require(bech32.length >= 8) { "Invalid bech32: too short" }
        require(bech32 == bech32.lowercase()) { "Invalid bech32: mixed case" }
        
        val separatorIndex = bech32.lastIndexOf(SEPARATOR)
        require(separatorIndex in 1..bech32.length - 7) { "Invalid bech32: invalid separator position" }
        
        val hrp = bech32.substring(0, separatorIndex)
        val data = bech32.substring(separatorIndex + 1)
            .map { CHARSET.indexOf(it) }
            .apply {
                require(all { it >= 0 }) { "Invalid bech32: invalid character" }
            }
        
        require(bech32VerifyChecksum(hrp, data)) { "Invalid bech32: checksum failed" }
        
        return Pair(hrp, data.dropLast(6))
    }
    
    /**
     * Create the 6-character checksum for Bech32 encoding.
     */
    private fun bech32CreateChecksum(hrp: String, data: List<Int>): List<Int> {
        val values = bech32HrpExpand(hrp) + data + listOf(0, 0, 0, 0, 0, 0)
        val polymod = bech32Polymod(values) xor 1
        return (0..5).map { (polymod shr (5 * (5 - it))) and 31 }
    }
    
    /**
     * Verify the checksum of a Bech32 string.
     */
    private fun bech32VerifyChecksum(hrp: String, data: List<Int>): Boolean {
        return bech32Polymod(bech32HrpExpand(hrp) + data) == 1
    }
    
    /**
     * Expand the HRP into the values used for checksum calculation.
     */
    private fun bech32HrpExpand(hrp: String): List<Int> {
        return hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }
    }
    
    /**
     * Calculate the Bech32 polymod (checksum).
     */
    private fun bech32Polymod(values: List<Int>): Int {
        val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (i in 0..4) {
                if ((top shr i) and 1 == 1) {
                    chk = chk xor GENERATOR[i]
                }
            }
        }
        return chk
    }
    
    /**
     * Generic bit conversion (from Bitcoin's bech32 reference implementation).
     * Converts between different bit widths, used for encoding/decoding Bech32.
     */
    private fun convertBits(
        data: ByteArray,
        fromBits: Int,
        toBits: Int,
        pad: Boolean = true
    ): List<Int> {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        
        for (b in data) {
            acc = (acc shl fromBits) or (b.toInt() and 0xFF)
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc shr bits) and maxv)
            }
        }
        
        if (pad) {
            if (bits > 0) {
                result.add((acc shl (toBits - bits)) and maxv)
            }
        } else {
            require(bits < fromBits) { "Invalid padding in bits" }
            require(((acc shl (toBits - bits)) and maxv) == 0) { "Non-zero padding bits" }
        }
        
        return result
    }
    
    /**
     * Overload for converting List<Int> directly.
     */
    private fun convertBits(
        data: List<Int>,
        fromBits: Int,
        toBits: Int,
        pad: Boolean = true
    ): List<Int> {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        
        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc shr bits) and maxv)
            }
        }
        
        if (pad) {
            if (bits > 0) {
                result.add((acc shl (toBits - bits)) and maxv)
            }
        } else {
            require(bits < fromBits) { "Invalid padding in bits" }
            require(((acc shl (toBits - bits)) and maxv) == 0) { "Non-zero padding bits" }
        }
        
        return result
    }
}
