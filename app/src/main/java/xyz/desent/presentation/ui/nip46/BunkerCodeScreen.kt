package xyz.desent.presentation.ui.nip46

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import xyz.desent.R
import xyz.desent.presentation.theme.Spacing

/**
 * "Show my bunker code" — the signer-initiated NIP-46 pairing direction: this
 * phone displays a `bunker://` URI (as a QR + copyable text); the user pastes
 * or scans it into an external app (Amethyst, iris, …), which then sends an
 * inbound `connect`. The embedded secret is single-use and expires; Regenerate
 * invalidates the previous code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BunkerCodeScreen(
    viewModel: Nip46ViewModel,
    onBack: () -> Unit
) {
    val uri by viewModel.bunkerUri.collectAsState()
    val context = LocalContext.current

    // Fresh secret on entry; invalidate it on leave.
    DisposableEffect(Unit) {
        viewModel.generateBunkerCode()
        onDispose { viewModel.clearBunkerCode() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nip46_bunker_code_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { viewModel.generateBunkerCode() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.nip46_bunker_code_regenerate))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.nip46_bunker_code_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val code = uri
            if (code == null) {
                Spacer(Modifier.height(Spacing.lg))
                CircularProgressIndicator()
            } else {
                QrCode(data = code, modifier = Modifier.fillMaxWidth(0.82f))

                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        code,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        modifier = Modifier.padding(Spacing.md)
                    )
                }

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("bunker", code))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text("  " + stringResource(R.string.nip46_bunker_code_copy))
                }
            }

            Text(
                stringResource(R.string.nip46_bunker_code_security),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Simple monochrome QR rendered from zxing's BitMatrix. */
@Composable
private fun QrCode(data: String, modifier: Modifier = Modifier) {
    val matrix = remember(data) {
        QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0)
    }
    Card(modifier = modifier.aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize().padding(Spacing.sm)) {
            drawBitMatrix(matrix, Color.Black, Color.White)
        }
    }
}

private fun DrawScope.drawBitMatrix(
    matrix: com.google.zxing.common.BitMatrix,
    dark: Color,
    light: Color
) {
    drawRect(light)
    val modules = matrix.width.coerceAtLeast(1)
    // BitMatrix encoded with width/height hints of 0 returns the minimal
    // module grid (no quiet zone) — scale it to the canvas.
    val cell = minOf(size.width, size.height) / modules
    val gridSize = cell * modules
    val originX = (size.width - gridSize) / 2
    val originY = (size.height - gridSize) / 2
    for (x in 0 until modules) {
        for (y in 0 until matrix.height) {
            if (matrix[x, y]) {
                drawRect(
                    dark,
                    topLeft = Offset(originX + x * cell, originY + y * cell),
                    size = Size(cell, cell)
                )
            }
        }
    }
}
