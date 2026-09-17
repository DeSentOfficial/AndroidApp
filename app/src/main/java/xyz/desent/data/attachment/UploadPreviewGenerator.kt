package xyz.desent.data.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * END-23 §2 step 2 (ANDROID_USER_FILES.md): for image uploads, decode the
 * bytes, downscale to ≤32 px on the long edge, encode a blurhash, and record
 * the natural width/height. Any failure returns null — preview fields are
 * optional and must never block an upload.
 *
 * Split into [decode] + [encodePreview] so the preview-quality slider can
 * re-encode from the held downscaled pixels without touching the source
 * bytes again.
 */
class UploadPreviewGenerator {

    data class Preview(val blurhash: String, val width: Int, val height: Int)

    /** A decoded, downscaled image ready for blurhash encoding. */
    class DecodedPreview(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val naturalWidth: Int,
        val naturalHeight: Int
    )

    /** Decode + downscale (long edge ≤32 px per END-23); null on failure. */
    suspend fun decode(bytes: ByteArray): DecodedPreview? = withContext(Dispatchers.Default) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val naturalWidth = bounds.outWidth
            val naturalHeight = bounds.outHeight
            if (naturalWidth <= 0 || naturalHeight <= 0) return@withContext null

            // Halve while the long edge would still be ≥16 px after another
            // halving — final long edge lands in [16, 32), inside the spec's
            // ≤32 px without throwing away DCT fidelity.
            var sample = 1
            val longEdge = maxOf(naturalWidth, naturalHeight)
            while (longEdge / (sample * 2) >= 16) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return@withContext null
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()

            DecodedPreview(pixels, w, h, naturalWidth, naturalHeight)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encode (or re-encode) a blurhash from the downscaled pixels. Pure and
     * cheap enough to call on every slider tick. Null on failure.
     */
    fun encodePreview(decoded: DecodedPreview, numX: Int, numY: Int): Preview? =
        BlurhashEncoder.encode(decoded.pixels, decoded.width, decoded.height, numX, numY)?.let {
            Preview(it, decoded.naturalWidth, decoded.naturalHeight)
        }
}
