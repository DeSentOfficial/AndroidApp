package xyz.desent.crypto

import org.junit.Test
import org.junit.Assert.*
import xyz.desent.crypto.Bech32Utils

/**
 * Unit tests for Bech32Utils using known Nostr test vectors.
 * These test vectors come from the Nostr specification and well-known public keys.
 */
class Bech32UtilsTest {
    
    /**
     * Test hex to npub conversion with a known test vector.
     * This uses the public key from the Nostr spec examples.
     */
    @Test
    fun testHexToNpub() {
        // Known test vector: Nostr's creator's public key (public example)
        // This is a 64-character hex string representing a public key
        val hexKey = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aea3b67f49"
        
        // Convert to npub
        val npub = Bech32Utils.hexToNpub(hexKey)
        
        // Should start with "npub1"
        assertTrue("npub should start with 'npub1'", npub.startsWith("npub1"))
        
        // Should be valid length (npub1 + 59 characters of bech32)
        assertEquals("npub should be 63 characters long", 63, npub.length)
        
        // Should only contain valid bech32 characters (no 'b', 'i', 'o')
        val validChars = setOf('0', '2', '3', '4', '5', '6', '7', '8', '9',
            'a', 'c', 'd', 'e', 'f', 'g', 'h', 'j', 'k', 'l', 'm', 'n', 'p',
            'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z')
        npub.substring(5).forEach { char ->
            assertTrue("npub contains invalid bech32 character: $char", validChars.contains(char))
        }
    }
    
    /**
     * Test npub to hex conversion (round-trip).
     * Ensures that converting hex -> npub -> hex yields the original hex.
     */
    @Test
    fun testNpubToHex_RoundTrip() {
        val originalHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aea3b67f49"
        
        // Convert hex -> npub -> hex
        val npub = Bech32Utils.hexToNpub(originalHex)
        val hexBack = Bech32Utils.npubToHex(npub)
        
        // Should match original
        assertEquals("Round-trip hex->npub->hex should preserve the original hex value", 
            originalHex, hexBack)
    }
    
    /**
     * Test hex to nsec conversion.
     */
    @Test
    fun testHexToNsec() {
        val nsecHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aea3b67f49"
        
        val nsec = Bech32Utils.hexToNsec(nsecHex)
        
        // Should start with "nsec1"
        assertTrue("nsec should start with 'nsec1'", nsec.startsWith("nsec1"))
        
        // Should be valid length
        assertEquals("nsec should be 63 characters long", 63, nsec.length)
    }
    
    /**
     * Test nsec to hex conversion (round-trip).
     */
    @Test
    fun testNsecToHex_RoundTrip() {
        val originalHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aea3b67f49"
        
        val nsec = Bech32Utils.hexToNsec(originalHex)
        val hexBack = Bech32Utils.nsecToHex(nsec)
        
        assertEquals("Round-trip hex->nsec->hex should preserve the original hex value",
            originalHex, hexBack)
    }
    
    /**
     * Test that short hex is padded correctly.
     */
    @Test
    fun testHexToNpub_InvalidChars_ShouldPadCorrectly() {
        val shortHex = "aabbccdd"
        val npub = Bech32Utils.hexToNpub(shortHex)
        
        assertTrue("npub should start with 'npub1'", npub.startsWith("npub1"))
        assertEquals("npub should be 63 characters long", 63, npub.length)
        
        val hexBack = Bech32Utils.npubToHex(npub)
        assertEquals("Round-trip should preserve length", 64, hexBack.length)
        assertTrue("Round-trip hex should end with original value", hexBack.endsWith("aabbccdd"))
    }
    
    /**
     * Test that invalid hex characters are rejected.
     */
    @Test
    fun testHexToNpub_InvalidHex_InvalidChars() {
        try {
            Bech32Utils.hexToNpub("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aea3b67fZZ")
            fail("Expected IllegalArgumentException for invalid hex")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
    
    /**
     * Test that invalid npub (wrong hrp) is rejected.
     */
    @Test(expected = IllegalArgumentException::class)
    fun testNpubToHex_InvalidNpub_WrongHrp() {
        // nsec instead of npub
        Bech32Utils.npubToHex("nsec1eaf2d1f77d123456789abcdef123456789abcdef123456789abcdef1234567")
    }
    
    /**
     * Test that invalid npub format is rejected.
     */
    @Test(expected = IllegalArgumentException::class)
    fun testNpubToHex_InvalidNpub_BadFormat() {
        Bech32Utils.npubToHex("invalid")
    }
    
    /**
     * Test with multiple known test vectors to ensure consistency.
     */
    @Test
    fun testMultipleTestVectors() {
        val testVectors = listOf(
            "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aea3b67f49",
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        
        for (hex in testVectors) {
            val npub = Bech32Utils.hexToNpub(hex)
            val hexBack = Bech32Utils.npubToHex(npub)
            
            assertEquals("Test vector round-trip failed for hex: $hex",
                hex, hexBack)
        }
    }
}
