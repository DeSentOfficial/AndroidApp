package xyz.desent.domain.usecase

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.desent.data.attachment.DesentBlobUploader
import xyz.desent.util.ImageCompressor

class MediaUploadUseCase(
    private val desentBlobUploader: DesentBlobUploader
) {
    suspend fun uploadProfilePicture(
        imageUri: Uri,
        context: Context
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(imageUri)
                ?: return@withContext Result.failure(Exception("Failed to open image"))

            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap == null) {
                return@withContext Result.failure(Exception("Failed to decode image"))
            }

            Log.d(TAG, "Original image size: ${bitmap.width}x${bitmap.height}")

            val compressedData = ImageCompressor.compressImage(
                bitmap = bitmap,
                maxDimension = 1024,
                quality = 85
            )
            bitmap.recycle()

            val fileName = imageUri.lastPathSegment ?: "profile.jpg"
            val mimeType = ImageCompressor.getMimeType(fileName)

            Log.d(TAG, "Uploading ${compressedData.size / 1024}KB image as $mimeType")

            desentBlobUploader.upload(compressedData, mimeType, fileName)
                .fold(
                    onSuccess = { blob ->
                        Log.d(TAG, "Upload successful: ${blob.url}")
                        Result.success(blob.url)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Upload failed", error)
                        Result.failure(error)
                    }
                )
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading profile picture", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "MediaUploadUseCase"
    }
}
