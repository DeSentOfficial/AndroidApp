package xyz.desent.data.nostr

/**
 * Index of Material Symbols Rounded glyph names → codepoints
 * (assets/material_symbols_rounded.codepoints, ~4,271 entries — the same
 * vocabulary the admin panel's Badges tab offers; see
 * refs/FROM_email.desent.xyz/BADGES_PROTOCOL.md §Art modes).
 *
 * The parser is pure (line list in, map out) so it is unit-testable; the
 * Android side wires the asset through [xyz.desent.presentation.theme].
 * Rendering uses the codepoint directly (not the ligature name), which is
 * robust in Compose — no fontFeatureSettings required.
 */
object MaterialSymbolsIndex {

    /**
     * Parse codepoints lines ("`name hex`", e.g. "`workspace_premium e7af`").
     * Blank/malformed lines and duplicate names (first wins) are skipped.
     */
    fun parse(lines: List<String>): Map<String, Int> {
        val map = HashMap<String, Int>(lines.size)
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val name = line.substringBefore(' ')
            val hex = line.substringAfter(' ', "")
            if (name.isEmpty() || name.contains(' ')) continue
            val codepoint = hex.toIntOrNull(16) ?: continue
            if (!Character.isValidCodePoint(codepoint)) continue
            map.putIfAbsent(name, codepoint)
        }
        return map
    }
}
