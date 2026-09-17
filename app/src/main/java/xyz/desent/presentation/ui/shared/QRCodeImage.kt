package xyz.desent.presentation.ui.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generate QR code image from content string
 */
@Composable
fun QRCodeImage(
    content: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }

    val bitmap = remember(content) {
        if (content.isBlank()) {
            return@remember android.graphics.Bitmap.createBitmap(
                sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888
            ).apply {
                eraseColor(Color.White.toArgb())
            }
        }

        val qrCodeWriter = QRCodeWriter()
        val hints = mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 1)
        val bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val width = bitMatrix.width
        val height = bitMatrix.height

        android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    setPixel(x, y, if (bitMatrix[x, y]) Color.Black.toArgb() else Color.White.toArgb())
                }
            }
        }
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR Code",
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit
    )
}
