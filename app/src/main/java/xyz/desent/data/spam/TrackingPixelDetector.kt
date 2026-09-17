package xyz.desent.data.spam

/**
 * Static tracking-pixel detection over email HTML bodies.
 *
 * Pairs with the remote-image policy (see `RemoteImagePolicy` and the
 * "Remote-image policy" section of `refs/SPAM_FILTER_REFERENCE.md`): the
 * policy decides whether remote images *load*; this detector tells the user
 * that some of those images exist purely to report opens back to the sender.
 *
 * The scan is purely local — no network requests, no state — so it stays
 * accurate even when images are allowed for a sender. Only http/https image
 * sources are considered; `data:` URIs are inline content, not beacons.
 */
data class TrackingPixelScan(
    val pixelCount: Int,
    /** Which signals fired, e.g. "tiny-dimension", "known-tracker", "suspicious-url". */
    val matchedSignals: List<String>
) {
    val hasTrackingPixels: Boolean get() = pixelCount > 0
}

object TrackingPixelDetector {

    /** A single `<img ...>` tag (lowercased source, non-greedy). */
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** `src="..."` / `src='...'` / unquoted, captured up to the delimiter. */
    private val SRC_ATTR = Regex("""src\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    private val WIDTH_ATTR = Regex("""\b(width|height)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val STYLE_ATTR = Regex("""style\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val CSS_SIZE = Regex("""(?:^|;)\s*(width|height)\s*:\s*(\d+(?:\.\d+)?)\s*(px)?""", RegexOption.IGNORE_CASE)

    private val HIDDEN_CSS = listOf("display:none", "display: none", "visibility:hidden", "visibility: hidden", "opacity:0", "opacity: 0", "mso-hide:all")

    /**
     * Known open-tracking beacon hosts/paths. Substring match over the
     * lowercased source URL.
     */
    private val KNOWN_TRACKERS = listOf(
        // Mailchimp
        "list-manage.com/track", ".mc.trk", "mc0.to/", ".mc.us",
        // SendGrid
        "wf/open.php", ".ct.sendgrid.net", "sendgrid.net/wf/open",
        // HubSpot
        "hs-track", "t.sidekickopen", "link.sidekickopen",
        // Litmus / analytics
        "litmus.com/t", "email-analytics",
        // Generic beacon filenames
        "open.gif", "e.gif", "spacer.gif", "blank.gif", "pixel.gif", "track/open"
    )

    /**
     * Suspicious naming in the URL path/query of a remote image: tracking
     * beacons are conventionally named for what they do.
     */
    private val SUSPICIOUS_URL_TOKENS = listOf("pixel", "beacon", "track", "1x1")

    fun scan(html: String): TrackingPixelScan {
        val signals = mutableSetOf<String>()
        var count = 0
        for (tag in IMG_TAG.findAll(html)) {
            val tagText = tag.value
            val src = extractAttr(tagText, SRC_ATTR) ?: continue
            if (!src.startsWith("http://", ignoreCase = true) && !src.startsWith("https://", ignoreCase = true)) continue

            val lowerSrc = src.lowercase()
            val isTracker = when {
                KNOWN_TRACKERS.any { lowerSrc.contains(it) } -> {
                    signals += "known-tracker"; true
                }
                hasSuspiciousUrlToken(lowerSrc) -> {
                    signals += "suspicious-url"; true
                }
                hasTinyOrHiddenDimensions(tagText) -> {
                    signals += "tiny-dimension"; true
                }
                else -> false
            }
            if (isTracker) count++
        }
        return TrackingPixelScan(count, signals.toList())
    }

    fun hasTrackingPixels(html: String): Boolean = scan(html).hasTrackingPixels

    private fun extractAttr(tagText: String, regex: Regex): String? {
        val match = regex.find(tagText) ?: return null
        val raw = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: return null
        return raw.trim('"', '\'')
    }

    private fun hasSuspiciousUrlToken(lowerSrc: String): Boolean {
        // Only inspect path+query so a legit host like "pixelcraft.com/logo.png"
        // isn't flagged purely on its domain name.
        val pathStart = lowerSrc.indexOf('/', lowerSrc.indexOf("//") + 2)
        val pathAndQuery = if (pathStart >= 0) lowerSrc.substring(pathStart) else ""
        return SUSPICIOUS_URL_TOKENS.any { pathAndQuery.contains(it) }
    }

    private fun hasTinyOrHiddenDimensions(tagText: String): Boolean {
        val lowerTag = tagText.lowercase()

        // width/height attributes — HTML attributes have no units, so ≤1 means a beacon-sized image.
        for (match in WIDTH_ATTR.findAll(lowerTag)) {
            val value = match.groupValues.drop(2)
                .firstOrNull { it.isNotEmpty() }
                ?.trim()
                ?.trim('"', '\'')
                ?.removeSuffix("px") ?: continue
            val px = value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt() ?: continue
            if (px <= 1) return true
        }

        val style = extractAttr(lowerTag, STYLE_ATTR) ?: return false
        for (decl in CSS_SIZE.findAll(style)) {
            val px = decl.groupValues[2].toDoubleOrNull() ?: continue
            if (px <= 1) return true
        }
        return HIDDEN_CSS.any { style.contains(it) }
    }
}
