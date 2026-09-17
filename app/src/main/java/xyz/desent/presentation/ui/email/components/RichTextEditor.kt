package xyz.desent.presentation.ui.email.components

import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * External handle to the [RichTextEditor]'s formatting commands. The editor
 * renders no toolbar of its own anymore — the compose/reply bottom bars own
 * the B / I / U / list / link actions and call through this state.
 *
 * The handle no-ops while no WebView is attached (before first composition
 * or after disposal, e.g. across a `key(format) { … }` switch).
 */
class RichTextEditorState {
    internal var webView: WebView? = null

    private fun exec(command: String) {
        webView?.evaluateJavascript("execAndNotify('$command');", null)
    }

    fun bold() = exec("bold")

    fun italic() = exec("italic")

    fun underline() = exec("underline")

    fun bulletList() = exec("insertUnorderedList")

    /** Wraps the current selection in a link pointing at [url]. */
    fun insertLink(url: String) {
        webView?.evaluateJavascript("wrapLink(${jsString(url)});", null)
    }
}

@Composable
fun rememberRichTextEditorState(): RichTextEditorState = remember { RichTextEditorState() }

/**
 * Minimal HTML body editor for outbound email (NIP-EMAIL `format: html`).
 *
 * A WebView hosting a `contenteditable` div, driven by `document.execCommand`
 * for the small tag set the email bridge supports (bold / italic / underline /
 * strikethrough / link / bullet list / clear). Everything is local:
 *  - no network: every http(s) subresource is short-circuited (the editor
 *    only ever renders what the user typed),
 *  - no external navigation: link taps never leave the editor (links are
 *    created, not followed),
 *  - the edited HTML flows back to Compose via a JS bridge with the content
 *    injected as a JSON string, so no quoting issues.
 *
 * Rendered chrome-free (no border, no fill) so the body reads as one open
 * canvas. Height follows content (min [minHeightDp]), same polling approach
 * as [HtmlContent]'s wrapContentHeight mode.
 */
@Composable
fun RichTextEditor(
    state: RichTextEditorState,
    html: String,
    onHtmlChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeightDp: Int = 160,
    placeholder: String = "Type your message…"
) {
    var contentHeightDp by remember { mutableIntStateOf(0) }
    // Latest known content: seeded from [html] at creation, then kept in sync
    // by the bridge. The WebView is initialized from this; later `html` param
    // changes are echoes of our own callbacks and are NOT pushed back in
    // (that would reset the caret). Callers force a true reset by keying the
    // composable (e.g. `key(format) { RichTextEditor(…) }`).
    var currentHtml by remember { mutableStateOf(html) }

    // The editor shell has no theme of its own — paint it with the screen's
    // colors so body and placeholder stay readable in both themes.
    val textColor = MaterialTheme.colorScheme.onBackground.toHexCss()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toHexCss()

    Column(modifier = modifier) {
        val hDp = if (contentHeightDp > 0) contentHeightDp.coerceAtLeast(minHeightDp) else minHeightDp
        AndroidView(
            factory = { context ->
                WebView(context).also { wv ->
                    state.webView = wv
                }.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    isScrollContainer = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setBackgroundColor(Color.TRANSPARENT)

                    addJavascriptInterface(
                        EditorBridge(
                            onHtml = { newHtml ->
                                currentHtml = newHtml
                                onHtmlChange(newHtml)
                            },
                            onHeight = { cssPx -> contentHeightDp = cssPx }
                        ),
                        "AndroidEditor"
                    )

                    webViewClient = object : WebViewClient() {
                        // Local-only editor: block every network fetch.
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val scheme = request.url.scheme?.lowercase()
                            return if (scheme == "http" || scheme == "https") BLOCKED else null
                        }

                        // Never navigate anywhere from the editor.
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean = true

                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(
                                "initEditor(${if (enabled) "true" else "false"}, ${jsString(currentHtml)}, " +
                                    "${jsString(placeholder)}, ${jsString(textColor)}, ${jsString(hintColor)});",
                                null
                            )
                        }
                    }

                    loadDataWithBaseURL(null, editorShellHtml, "text/html", "UTF-8", null)
                }
            },
            onRelease = {
                state.webView = null
                it.destroy()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(hDp.dp)
        )
    }
}

/** JSON-encode a string for safe embedding in a JS source literal. */
private fun jsString(s: String): String =
    org.json.JSONObject().put("s", s).toString().let { json ->
        // {"s":"…"} → the quoted+escaped string literal for `…`
        json.substring(json.indexOf(':') + 1).removeSuffix("}")
    }

private fun androidx.compose.ui.graphics.Color.toHexCss(): String =
    "#%06X".format(toArgb() and 0xFFFFFF)

/** JS↔Kotlin bridge: HTML deltas + content height, throttled on the JS side. */
private class EditorBridge(
    private val onHtml: (String) -> Unit,
    private val onHeight: (Int) -> Unit
) {
    @JavascriptInterface
    fun htmlChanged(html: String) {
        onHtml(html)
    }

    @JavascriptInterface
    fun heightChanged(cssPx: Int) {
        onHeight(cssPx)
    }
}

private val BLOCKED = WebResourceResponse(
    "text/plain",
    "utf-8",
    java.io.ByteArrayInputStream(ByteArray(0))
)

private const val EDITOR_POLL_MS = 250L

/**
 * The editor page. `contenteditable` div + execCommand toolbar targets +
 * change notification (input events + after every toolbar action) with the
 * HTML payload JSON-escaped by the bridge contract.
 */
private val editorShellHtml = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html, body { margin: 0; padding: 0; width: 100%; }
  #editor {
    min-height: 96px;
    padding: 12px 14px;
    font-size: 16px;
    line-height: 1.5;
    outline: none;
    word-wrap: break-word;
    overflow-wrap: anywhere;
    color: #1b1b1f;
  }
  #editor:empty::before, #editor[data-empty="true"]::before {
    content: attr(data-placeholder);
    color: var(--hint-color, #9e9e9e);
  }
  #editor[data-enabled="false"] { opacity: 0.55; }
  #editor ul { padding-left: 24px; margin: 8px 0; }
  #editor a { color: #1565c0; }
</style>
</head>
<body>
<div id="editor" contenteditable="true"></div>
<script>
  (function () {
    var editor = document.getElementById('editor');
    var initial = '';
    var suppress = false;
    var lastHeight = -1;
    var lastReported = null;
    var timer = null;

    function isBlankHtml(h) {
      return h.replace(/<br\s*\/?>/gi, '').replace(/&nbsp;/gi, ' ')
              .replace(/<[^>]*>/g, '').trim() === '';
    }

    window.initEditor = function (enabled, html, placeholder, textColor, hintColor) {
      editor.contentEditable = enabled ? 'true' : 'false';
      editor.setAttribute('data-enabled', enabled ? 'true' : 'false');
      editor.setAttribute('data-placeholder', placeholder || '');
      editor.style.color = textColor || '#1b1b1f';
      document.documentElement.style.setProperty('--hint-color', hintColor || '#9e9e9e');
      initial = html || '';
      suppress = true;
      editor.innerHTML = initial;
      refreshEmpty();
      suppress = false;
      scheduleReport();
      startHeightWatch();
    };

    window.setInitialHtmlIfEmpty = function () {
      if (isBlankHtml(editor.innerHTML) && initial) {
        suppress = true;
        editor.innerHTML = initial;
        refreshEmpty();
        suppress = false;
        scheduleReport();
      }
    };

    function refreshEmpty() {
      editor.setAttribute('data-empty', isBlankHtml(editor.innerHTML) ? 'true' : 'false');
    }

    function report() {
      var html = editor.innerHTML;
      refreshEmpty();
      if (html === lastReported) return;
      lastReported = html;
      AndroidEditor.htmlChanged(html);
    }

    function scheduleReport() {
      if (timer) clearTimeout(timer);
      timer = setTimeout(report, 150);
    }

    window.execAndNotify = function (command) {
      document.execCommand(command, false, null);
      scheduleReport();
    };

    window.wrapLink = function (url) {
      if (!url) return;
      document.execCommand('createLink', false, url);
      scheduleReport();
    };

    window.notifyHtml = scheduleReport;

    editor.addEventListener('input', scheduleReport);
    editor.addEventListener('keyup', scheduleReport);
    editor.addEventListener('blur', report);

    function startHeightWatch() {
      setInterval(function () {
        var h = document.body.scrollHeight;
        if (h !== lastHeight) {
          lastHeight = h;
          AndroidEditor.heightChanged(h);
        }
      }, $EDITOR_POLL_MS);
    }
  })();
</script>
</body>
</html>
""".trimIndent()
