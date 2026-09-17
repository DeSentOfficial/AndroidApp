package xyz.desent.data.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurhashEncoderTest {

    private val alphabet =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    private fun solid(width: Int, height: Int, argb: Int): IntArray = IntArray(width * height) { argb }

    @Test
    fun solidBlack_encodesToExactVector() {
        // All-zero linear components: size flag 27 → 'R', quantMax 0 → '0',
        // DC (0,0,0) → "0000", every AC quantises to 9/9/9 → "fQ".
        val hash = BlurhashEncoder.encode(solid(4, 4, 0xFF000000.toInt()), 4, 4)
        assertNotNull(hash)
        assertEquals("R0" + "0000" + "fQ".repeat(47), hash)
    }

    @Test
    fun solidWhite_encodesWhiteDc() {
        val hash = BlurhashEncoder.encode(solid(4, 4, 0xFFFFFFFF.toInt()), 4, 4)
        assertNotNull(hash)
        // 8×6 → 1 (size) + 1 (quant) + 4 (DC) + 47×2 (AC) = 100 chars. The
        // cos(π·i·x/w) basis does NOT annihilate solid images (Σ cos over
        // x = 0..w-1 is non-zero for odd i), so AC energy — and a clamped
        // quantMax '~' — is expected even for a uniform colour. The DC block
        // is the white sRGB triple 0xFFFFFF → "TSUA".
        assertEquals(100, hash!!.length)
        assertEquals("R~TSUA", hash.substring(0, 6))
    }

    @Test
    fun gradient_usesOnlyBase83Characters() {
        val pixels = IntArray(16 * 16) { i ->
            val x = i % 16
            val y = i / 16
            (0xFF shl 24) or (x * 17 shl 16) or (y * 17 shl 8) or ((x + y) * 8)
        }
        val hash = BlurhashEncoder.encode(pixels, 16, 16)
        assertNotNull(hash)
        assertEquals(100, hash!!.length)
        assertTrue(hash.all { it in alphabet })
    }

    @Test
    fun invalidInputs_returnNull() {
        assertNull(BlurhashEncoder.encode(IntArray(0), 0, 0))
        assertNull(BlurhashEncoder.encode(IntArray(1), 2, 2)) // too few pixels
        assertNull(BlurhashEncoder.encode(IntArray(4), 2, 2, numX = 10)) // components > 9
        assertNull(BlurhashEncoder.encode(IntArray(4), 2, 2, numY = 0))
    }

    @Test
    fun smallComponentCount_producesShorterHash() {
        val hash = BlurhashEncoder.encode(solid(4, 4, 0xFF336699.toInt()), 4, 4, numX = 1, numY = 1)
        // 1 + 1 + 4 (DC only, no AC) = 6 chars.
        assertNotNull(hash)
        assertEquals(6, hash!!.length)
    }
}
