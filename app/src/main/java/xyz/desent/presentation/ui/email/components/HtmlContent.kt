package xyz.desent.presentation.ui.email.components

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Renders an HTML string in a [WebView].
 *
 *  - JavaScript stays disabled (email scripts never run).
 *  - Link clicks are intercepted and opened in the device's external browser (never inside
 *    the WebView) via [WebViewClient.shouldOverrideUrlLoading].
 *  - The WebView is destroyed in [AndroidView]'s onRelease when leaving composition.
 *
 * @param wrapContentHeight when false (default) the caller supplies a modifier that gives the
 *    WebView a definite size (e.g. fillMaxWidth + weight in a bounded Column) and the WebView
 *    scrolls internally for content taller than its viewport. When true, the WebView's own
 *    scrolling is disabled and its Compose height is driven by the HTML content's natural
 *    height so it can be embedded inside an externally-scrolling container such as a
 *    `LazyColumn` card.
 * @param blockRemoteImages when true, all http/https subresource requests are short-circuited
 *    with an empty response so remote images, CSS backgrounds, and tracking pixels never load
 *    automatically. Inline `data:`/`content:` content still renders. Callers toggle this at
 *    runtime by keying the composable (e.g. `key(loadImages) { HtmlContent(…) }`) so the
 *    WebView is recreated with the new setting.
 */
@Composable
fun HtmlContent(
    html: String,
    modifier: Modifier = Modifier,
    wrapContentHeight: Boolean = false,
    blockRemoteImages: Boolean = false
) {
    if (html.isBlank()) return

    // WebView.getContentHeight() returns the content height in CSS px, which is numerically
    // equal to Android dp (both are 1/160 inch). It must therefore be fed to Compose directly
    // as dp — NOT converted via Density.toDp(), which would divide by the device density and
    // shrink the card to ~1/density of its true height (the clipping bug).
    var contentHeightDp by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    if (wrapContentHeight) {
        // A WebView does NOT reliably self-report a re-layout when its page reflows (e.g. async
        // image loads), and a self-resizing child doesn't reliably push size changes back up to
        // Compose inside a LazyColumn. So we drive the height from Compose state: poll
        // contentHeight on a short cadence and write it into contentHeightDp; the state-driven
        // Modifier.height then propagates to the layout. Stops early once the height stabilizes.
        LaunchedEffect(webView) {
            val wv = webView ?: return@LaunchedEffect
            val deadline = System.currentTimeMillis() + POLL_DURATION_MS
            var lastRead = -1
            var stableReads = 0
            while (System.currentTimeMillis() < deadline) {
                val h = wv.contentHeight
                if (h > 0) {
                    if (h != contentHeightDp) contentHeightDp = h
                    if (h == lastRead) {
                        stableReads++
                        if (stableReads >= STABLE_READS_TO_STOP) break
                    } else {
                        stableReads = 0
                    }
                    lastRead = h
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    val resolvedModifier = if (wrapContentHeight) {
        // contentHeightDp is already in dp; use directly. Placeholder until first measure so the
        // view has a definite height to lay out before the first poll completes.
        val hDp = if (contentHeightDp > 0) contentHeightDp.dp else PLACEHOLDER_HEIGHT_DP
        modifier.then(Modifier.height(hDp))
    } else {
        modifier
    }

    AndroidView(
        factory = { context ->
            WebView(context).also { webView = it }.apply {
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.useWideViewPort = true  // honor the viewport meta tag
                // When remote images ARE allowed, serve them from the WebView
                // HTTP cache when possible instead of re-fetching on every
                // open of the same message.
                settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

                if (wrapContentHeight) {
                    // The list scrolls; the WebView should never scroll internally.
                    isScrollContainer = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                }

                webViewClient = object : WebViewClient() {
                    // When [blockRemoteImages] is on, deny every http/https subresource fetch
                    // (remote <img>, CSS backgrounds, web beacons, tracking pixels — including
                    // extensionless ones MIME heuristics miss). Inline data:/content: content
                    // still renders. The main HTML is loaded via loadDataWithBaseURL(null,…),
                    // so this never blocks the message body itself.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (blockRemoteImages) {
                            val scheme = request.url.scheme?.lowercase()
                            if (scheme == "http" || scheme == "https") {
                                return BLOCKED_RESOURCE
                            }
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        openInExternalBrowser(view, request.url.toString())

                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        openInExternalBrowser(view, url)

                    private fun openInExternalBrowser(view: WebView, url: String): Boolean = try {
                        view.context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                        true
                    } catch (e: Exception) {
                        false
                    }
                }

                loadDataWithBaseURL(null, buildEmailHtml(html), "text/html", "UTF-8", null)
            }
        },
        onRelease = {
            webView = null
            it.destroy()
        },
        modifier = resolvedModifier
    )
}

private const val POLL_INTERVAL_MS = 100L
private const val POLL_DURATION_MS = 4_000L
private const val STABLE_READS_TO_STOP = 6
private val PLACEHOLDER_HEIGHT_DP = 128.dp

/** Empty body returned for blocked remote resources so the WebView renders nothing for them. */
private val BLOCKED_RESOURCE = WebResourceResponse(
    "text/plain",
    "utf-8",
    java.io.ByteArrayInputStream(ByteArray(0))
)

/**
 * Wraps/injects a mobile viewport and overflow-preventing CSS so the email lays out at the
 * device width and never scrolls horizontally. Injected into the email's existing <head>
 * when present (to preserve its styling), otherwise the fragment is wrapped in a shell.
 */
private val EMAIL_HEAD_INJECTION = """
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { width: 100% !important; max-width: 100% !important; margin: 0 !important; padding: 0 !important; overflow-x: hidden !important; }
  * { max-width: 100% !important; box-sizing: border-box !important; }
  body { overflow-wrap: anywhere !important; word-wrap: break-word !important; word-break: break-word !important; -webkit-text-size-adjust: 100% !important; }
  img, video { height: auto !important; }
  table { width: 100% !important; }
  td, th { word-break: break-word !important; overflow-wrap: anywhere !important; }
  pre { white-space: pre-wrap !important; word-wrap: break-word !important; }
</style>
""".trimIndent()

private fun buildEmailHtml(emailHtml: String): String {
    val lower = emailHtml.lowercase()
    return when {
        lower.contains("<head") -> emailHtml.replaceFirst(
            Regex("<head[^>]*>", RegexOption.IGNORE_CASE), "$0$EMAIL_HEAD_INJECTION"
        )
        lower.contains("<html") -> emailHtml.replaceFirst(
            Regex("<html[^>]*>", RegexOption.IGNORE_CASE), "$0<head>$EMAIL_HEAD_INJECTION</head>"
        )
        lower.contains("<body") -> "<html><head>$EMAIL_HEAD_INJECTION</head>$emailHtml</html>"
        else -> "<html><head>$EMAIL_HEAD_INJECTION</head><body>$emailHtml</body></html>"
    }
}
