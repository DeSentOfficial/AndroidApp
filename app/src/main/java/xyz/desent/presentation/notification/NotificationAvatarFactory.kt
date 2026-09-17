package xyz.desent.presentation.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.BitmapShader
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import xyz.desent.R
import xyz.desent.crypto.Bech32Utils
import xyz.desent.data.avatar.FaviconResolver
import xyz.desent.domain.model.Email
import xyz.desent.domain.repository.ContactProfileResolver
import xyz.desent.presentation.ui.components.avatarTintFor
import xyz.desent.presentation.ui.components.senderInitials
import androidx.compose.ui.graphics.toArgb
import kotlin.math.min

/**
 * Telegram-style decorated avatars for email notifications: the sender's
 * picture (or initials on the deterministic sender tint) in a circle, with a
 * small white DeSent badge disc in the bottom-right corner.
 *
 * URL resolution is **Room-only** (no relay/HTTP traffic on the notify
 * path): kind-0 `picture` via the thread sender key or the NIP-05 link,
 * else a cached favicon availability. Bitmap bytes come from Coil
 * (memory/disk first); when a URL is known but its bytes aren't cached, a
 * short bounded network wait is allowed before falling back to initials.
 * See refs/CONTACTS_PROFILE_PIPELINE.md.
 */
class NotificationAvatarFactory(
    private val context: Context,
    private val profileResolver: ContactProfileResolver,
    private val faviconResolver: FaviconResolver
) {

    /** Room-only avatar URL precedence: pubkey picture → NIP-05 picture → cached favicon. */
    suspend fun avatarUrlFor(email: Email): String? {
        val picture = senderPicture(email)
        if (!picture.isNullOrBlank()) return picture
        return faviconResolver.cachedUrlFor(email.senderEmail)
    }

    private suspend fun senderPicture(email: Email): String? {
        val senderHex = email.threadSenderPubkey
            ?.let { runCatching { Bech32Utils.npubToHex(it) }.getOrNull() }
        if (senderHex != null) {
            profileResolver.observeProfile(senderHex).first()?.let { return it.picture }
        }
        val key = email.senderEmail.trim().lowercase()
        if (key.isNotEmpty()) {
            profileResolver.observeProfileByIdentifier(key).first()?.let { return it.picture }
        }
        return null
    }

    /** Decorated large-icon bitmap: avatar-or-initials circle + DeSent badge. */
    suspend fun avatarBitmapFor(email: Email): Bitmap {
        val url = avatarUrlFor(email)
        val avatar = url?.takeIf { it.isNotBlank() }?.let { loadBitmap(it) }
        return compose(avatar, email.displaySender, email.senderEmail)
    }

    /**
     * Post-notify warm-up: refresh the sender's Room caches (profile relay
     * lookup + favicon probe) so the NEXT notification resolves instantly.
     */
    suspend fun warmUp(email: Email) {
        val senderHex = email.threadSenderPubkey
            ?.let { runCatching { Bech32Utils.npubToHex(it) }.getOrNull() }
        if (senderHex != null) {
            runCatching { profileResolver.resolve(senderHex) }
        } else {
            runCatching { profileResolver.resolveByIdentifier(email.senderEmail.trim().lowercase()) }
        }
        runCatching { faviconResolver.faviconUrlFor(email.senderEmail) }
    }

    /** Coil load bounded by [LOAD_TIMEOUT_MS]; null on timeout/failure. */
    private suspend fun loadBitmap(url: String): Bitmap? {
        val result = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            runCatching {
                Coil.imageLoader(context).execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false)
                        .build()
                )
            }.getOrNull()
        } ?: return null
        return result?.drawable?.toBitmap()
    }

    /** Pure-canvas composition — deliberately thin (no Robolectric in the project). */
    private fun compose(avatar: Bitmap?, displayName: String, seed: String): Bitmap {
        val size = (context.resources.displayMetrics.density * LARGE_ICON_DP).toInt()
            .coerceAtLeast(LARGE_ICON_MIN_PX)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val center = size / 2f
        val radius = center

        if (avatar != null) {
            // Center-crop the source into the circle via a bitmap shader.
            val shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = size.toFloat() / min(avatar.width, avatar.height)
            val dx = (avatar.width * scale - size) / 2f
            val dy = (avatar.height * scale - size) / 2f
            val matrix = android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate(-dx, -dy)
            }
            shader.setLocalMatrix(matrix)
            canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.shader = shader
            })
        } else {
            canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = avatarTintFor(seed).toArgb()
            })
            val initials = senderInitials(displayName, seed)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = size * 0.38f
                isFakeBoldText = true
            }
            val baseline = center - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(initials, center, baseline, textPaint)
        }

        drawBadge(canvas, size)
        return out
    }

    /** White disc in the bottom-right + the DeSent glyph in brand amber. */
    private fun drawBadge(canvas: Canvas, size: Int) {
        val badgeDiameter = size * BADGE_FRACTION
        val inset = size * BADGE_INSET_FRACTION
        val cx = size - inset - badgeDiameter / 2f
        val cy = size - inset - badgeDiameter / 2f

        canvas.drawCircle(cx, cy, badgeDiameter / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        })

        badgeGlyph()?.let { bitmap ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = amberFilter
            }
            val glyphSize = badgeDiameter * BADGE_GLYPH_FRACTION
            val left = cx - glyphSize / 2f
            val top = cy - glyphSize / 2f
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(left, top, left + glyphSize, top + glyphSize),
                paint
            )
        }
    }

    private fun badgeGlyph(): Bitmap? {
        if (cachedGlyph != null) return cachedGlyph
        val decoded = runCatching {
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_notification)
                ?.toBitmap()
        }.getOrNull()
        cachedGlyph = decoded
        return decoded
    }

    private val amberFilter: ColorFilter by lazy {
        PorterDuffColorFilter(
            ContextCompat.getColor(context, R.color.desent_amber),
            PorterDuff.Mode.SRC_IN
        )
    }

    companion object {
        /** Bounded network wait for uncached avatar bytes (user-approved). */
        private const val LOAD_TIMEOUT_MS = 1_500L

        private const val LARGE_ICON_DP = 64
        private const val LARGE_ICON_MIN_PX = 128

        /** Badge disc diameter as a fraction of the large-icon size. */
        private const val BADGE_FRACTION = 0.46f
        private const val BADGE_INSET_FRACTION = 0.02f
        private const val BADGE_GLYPH_FRACTION = 0.62f

        private var cachedGlyph: Bitmap? = null
    }
}
