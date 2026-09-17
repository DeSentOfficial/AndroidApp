package xyz.desent.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import java.io.ByteArrayOutputStream

object ImageCompressor {
    private const val TAG = "ImageCompressor"

    fun compressImage(
        bitmap: Bitmap,
        maxDimension: Int = 1024,
        quality: Int = 85
    ): ByteArray {
        val resizedBitmap = resizeBitmap(bitmap, maxDimension)
        
        val outputStream = ByteArrayOutputStream()
        var currentQuality = quality
        var compressedData: ByteArray
        
        do {
            outputStream.reset()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, outputStream)
            compressedData = outputStream.toByteArray()
            
            if (compressedData.size > 500 * 1024 && currentQuality > 10) {
                currentQuality -= 10
                Log.d(TAG, "Image too large (${compressedData.size / 1024}KB), reducing quality to $currentQuality")
            } else {
                break
            }
        } while (true)

        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        Log.d(TAG, "Compressed image: ${compressedData.size / 1024}KB at quality $currentQuality")
        return compressedData
    }

    private fun resizeBitmap(
        bitmap: Bitmap,
        maxDimension: Int
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val ratio = minOf(
            maxDimension.toFloat() / width,
            maxDimension.toFloat() / height
        )

        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        Log.d(TAG, "Resizing from ${width}x${height} to ${newWidth}x${newHeight}")

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
