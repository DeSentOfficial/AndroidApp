package xyz.desent.presentation.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import xyz.desent.R
import xyz.desent.data.nostr.MaterialSymbolsIndex

/**
 * Material Symbols Rounded (filled instance) — the full ~4,271-glyph set the
 * DeSent badge `icon` tags name from (refs/FROM_email.desent.xyz/
 * BADGES_PROTOCOL.md §Art modes). The TTF is a pinned static instance
 * (FILL=1, wght=400, opsz=24, GRAD=0) so glyphs render filled with no
 * variation settings.
 *
 * Glyph names resolve via the codepoints index bundled in assets
 * ([codepointIndex]); rendering uses the codepoint character directly, which
 * avoids ligature/fontFeatureSettings pitfalls in Compose.
 */
val MaterialSymbolsFontFamily = FontFamily(
    Font(R.font.material_symbols_rounded_fill, FontWeight.Normal)
)

/**
 * Lazily-loaded name → codepoint index over
 * `assets/material_symbols_rounded.codepoints`. Parsed once per process on
 * first use (a ~79 KB / ~4,271-line asset; parse cost ~ms) and cached.
 */
object MaterialSymbols {

    @Volatile
    private var index: Map<String, Int>? = null

    /**
     * Resolve a glyph name (e.g. `workspace_premium`) to its codepoint.
     * Null when the name is unknown — callers must fall back (the badge
     * doc's circle+letter fallback; never crash on unknown names).
     */
    fun codepointFor(context: Context, name: String): Int? {
        if (name.isBlank()) return null
        val idx = index ?: synchronized(this) {
            index ?: MaterialSymbolsIndex.parse(readCodepoints(context)).also { index = it }
        }
        return idx[name.lowercase().trim()]
    }

    private fun readCodepoints(context: Context): List<String> = try {
        context.assets.open("material_symbols_rounded.codepoints")
            .bufferedReader()
            .readLines()
    } catch (e: Exception) {
        // Missing/unreadable asset → every name falls back; badges still
        // render (name chips / circle+letter), nothing crashes.
        emptyList()
    }
}
