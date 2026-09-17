package xyz.desent.data.attachment

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * Pure-Kotlin blurhash encoder (no dependency) — the write-side counterpart
 * of the decoder in `presentation/ui/files/Blurhash.kt`, both following the
 * wolt blurhash reference (https://github.com/woltapp/blurhash).
 *
 * Used for END-23 user-file uploads: images get an 8×6-component client-side
 * blurhash so other devices can render a thumbnail placeholder without
 * downloading the ciphertext (ANDROID_USER_FILES.md §2 step 2).
 */
object BlurhashEncoder {

    private const val ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    /**
     * Encode ARGB [pixels] ([width]×[height]) into a blurhash string with
     * [numX]×[numY] components. Returns null on invalid input.
     */
    fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
        numX: Int = 8,
        numY: Int = 6
    ): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        if (numX < 1 || numX > 9 || numY < 1 || numY > 9) return null
        return try {
            val factors = Array(numX * numY) { idx ->
                val i = idx % numX
                val j = idx / numX
                multiplyBasis(pixels, width, height, i, j)
            }
            val dc = factors[0]
            val ac = factors.copyOfRange(1, factors.size)

            val sizeFlag = (numX - 1) + (numY - 1) * 4
            val sb = StringBuilder().append(encode83(sizeFlag, 1))

            val maximumValue: Float
            if (ac.isNotEmpty()) {
                val actualMax = ac.maxOf { c -> max(c[0], max(c[1], c[2])) }
                val quantMax = floor(max(0f, min(82f, floor(actualMax * 166f - 0.5f)))).toInt()
                maximumValue = (quantMax + 1) / 166f
                sb.append(encode83(quantMax, 1))
            } else {
                maximumValue = 1f
                sb.append(encode83(0, 1))
            }

            sb.append(encodeDC(dc))
            ac.forEach { sb.append(encodeAC(it, maximumValue)) }
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun multiplyBasis(
        pixels: IntArray,
        width: Int,
        height: Int,
        i: Int,
        j: Int
    ): FloatArray {
        // DC carries no normalisation factor; every AC component is doubled
        // (the cosine basis's negative-frequency half).
        val normalisation = if (i == 0 && j == 0) 1f else 2f
        var r = 0f
        var g = 0f
        var b = 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val basis = (
                    normalisation *
                        cos(PI * i * x / width) *
                        cos(PI * j * y / height)
                    ).toFloat()
                r += basis * srgbToLinear((p shr 16) and 0xFF)
                g += basis * srgbToLinear((p shr 8) and 0xFF)
                b += basis * srgbToLinear(p and 0xFF)
            }
        }
        val scale = 1f / (width * height)
        return floatArrayOf(r * scale, g * scale, b * scale)
    }

    private fun encode83(value: Int, length: Int): String {
        var v = value
        val sb = StringBuilder()
        repeat(length) {
            sb.append(ALPHABET[v % 83])
            v /= 83
        }
        // Digits are written least-significant first.
        return sb.reverse().toString()
    }

    private fun encodeDC(value: FloatArray): String {
        val r = linearToSrgb(value[0])
        val g = linearToSrgb(value[1])
        val b = linearToSrgb(value[2])
        return encode83((r shl 16) + (g shl 8) + b, 4)
    }

    private fun encodeAC(value: FloatArray, maximumValue: Float): String {
        val quantR = quant(value[0], maximumValue)
        val quantG = quant(value[1], maximumValue)
        val quantB = quant(value[2], maximumValue)
        return encode83(quantR * 19 * 19 + quantG * 19 + quantB, 2)
    }

    private fun quant(value: Float, maximumValue: Float): Int {
        val normalised = signPow(value / maximumValue, 0.5f)
        return max(0, min(18, floor(normalised * 9 + 9.5f).toInt()))
    }

    private fun signPow(value: Float, exp: Float): Float = sign(value) * abs(value).pow(exp)

    private fun srgbToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val srgb = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (srgb.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}
