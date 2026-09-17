package xyz.desent.presentation.ui.files

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign

/**
 * Pure-Kotlin blurhash decoder (no dependency). Decodes a blurhash string
 * (carried in the attachments API list response) into an [ImageBitmap] for use
 * as a thumbnail placeholder before/instead of downloading the full image.
 *
 * Reference: https://github.com/woltapp/blurhash
 */
object Blurhash {

    private const val ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    fun decode(blurhash: String, width: Int, height: Int, punch: Float = 1f): ImageBitmap? {
        if (blurhash.length < 6) return null
        return try {
            val sizeFlag = decode83(blurhash, 0, 1)
            val numY = sizeFlag / 9 + 1
            val numX = sizeFlag % 9 + 1
            val quantMax = (decode83(blurhash, 1, 2) + 1) / 166f

            val components = Array(numX * numY) { FloatArray(3) }
            components[0] = decodeDC(blurhash, 2, 6)
            for (i in 1 until numX * numY) {
                val s = 4 + i * 2
                if (s + 2 > blurhash.length) break
                components[i] = decodeAC(blurhash, s, s + 2, quantMax * punch)
            }

            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val yNorm = y.toFloat() / height
                val basisY = (0 until numY).map { j -> cos(PI * yNorm * j).toFloat() }
                for (x in 0 until width) {
                    val xNorm = x.toFloat() / width
                    var r = 0f; var g = 0f; var b = 0f
                    for (j in 0 until numY) {
                        val by = basisY[j]
                        val row = components[j * numX]
                        for (i in 0 until numX) {
                            val basis = cos(PI * xNorm * i).toFloat() * by
                            val c = components[j * numX + i]
                            r += basis * c[0]
                            g += basis * c[1]
                            b += basis * c[2]
                        }
                    }
                    pixels[y * width + x] = (0xFF shl 24) or
                        (linearToSrgb(r) shl 16) or
                        (linearToSrgb(g) shl 8) or
                        linearToSrgb(b)
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private fun decode83(str: String, start: Int, end: Int): Int {
        var value = 0
        for (i in start until end) {
            val idx = ALPHABET.indexOf(str[i])
            if (idx < 0) return 0
            value = value * 83 + idx
        }
        return value
    }

    private fun decodeDC(str: String, start: Int, end: Int): FloatArray {
        val v = decode83(str, start, end)
        return floatArrayOf(
            srgbToLinear((v shr 16) and 0xFF),
            srgbToLinear((v shr 8) and 0xFF),
            srgbToLinear(v and 0xFF)
        )
    }

    private fun decodeAC(str: String, start: Int, end: Int, max: Float): FloatArray {
        val v = decode83(str, start, end)
        val r = v / (19 * 19)
        val g = (v / 19) % 19
        val b = v % 19
        return floatArrayOf(
            signPow((r - 9) / 9f, 2f) * max,
            signPow((g - 9) / 9f, 2f) * max,
            signPow((b - 9) / 9f, 2f) * max
        )
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
