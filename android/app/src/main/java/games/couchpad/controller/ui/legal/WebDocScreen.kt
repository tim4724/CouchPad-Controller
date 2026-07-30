package games.couchpad.controller.ui.legal

import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import games.couchpad.controller.data.LAUNCHER_HOSTS
import games.couchpad.controller.ui.components.BackScaffold
import games.couchpad.controller.ui.components.ServerUnreachableRetry
import games.couchpad.controller.ui.components.denyLocalFileAccess

// The page renders its own <h1> title (e.g. "DATENSCHUTZERKLÄRUNG"); in-app the
// nav bar already shows it, so hide the page copy to avoid the duplicate. Injected
// at document-start (below) so the heading never flashes before it's hidden.
//
// The page ships a strict CSP (style-src 'self'), so an injected inline <style>
// element is rejected by the browser and never applies. A constructable
// stylesheet is a pure CSSOM object — exempt from CSP — and still covers nodes
// added after injection. Older WebViews without it fall back to setting the
// style directly on each heading via the CSSOM (also CSP-exempt).
private const val HIDE_HEADING_JS = """
  (function () {
    if (window.__cgHideHeading) return;
    window.__cgHideHeading = true;
    var CSS = '.legal-shell > h1{display:none !important;}';
    try {
      if ('adoptedStyleSheets' in document && 'replaceSync' in CSSStyleSheet.prototype) {
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(CSS);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat(sheet);
        return;
      }
    } catch (e) { /* fall through */ }
    function hide() {
      var nodes = document.querySelectorAll('.legal-shell > h1'), i;
      for (i = 0; i < nodes.length; i++) nodes[i].style.setProperty('display', 'none', 'important');
      return nodes.length > 0;
    }
    if (hide()) return;
    document.addEventListener('DOMContentLoaded', hide);
    if (window.MutationObserver) {
      var obs = new MutationObserver(function () { if (hide()) obs.disconnect(); });
      obs.observe(document.documentElement, { childList: true, subtree: true });
    }
  })();
"""

// The legal pages are served from a launcher apex; scope the injected script to those
// origins. Legacy apexes are included — a pre-rebrand App Link still lands here.
private val LEGAL_ORIGINS = LAUNCHER_HOSTS.map { "https://$it" }.toSet()

/**
 * A read-only in-app viewer for a hosted legal document (privacy / imprint). Kept
 * deliberately separate from the game-host WebView: there is no navigation
 * allow-list or JS bridge here — these are trusted first-party pages, loaded so
 * the documents stay reachable from within the app.
 *
 * Each screen shows exactly one document. Tapping the page's cross-link to the
 * other doc opens it as its own screen ([onOpenDoc]), so the system back button
 * returns here — no in-page history to walk.
 */
@Composable
fun WebDocScreen(
  url: String,
  privacyTitle: String,
  imprintTitle: String,
  onOpenDoc: (String) -> Unit,
  onBack: () -> Unit,
) {
  var loading by remember { mutableStateOf(true) }
  // Main-document load failure (no connection / host unreachable). We show our own
  // error state over the WebView so the stock net::ERR_* page never shows.
  var failed by remember { mutableStateOf(false) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  val title = if (LegalLinks.isImprint(url)) imprintTitle else privacyTitle

  BackScaffold(title = title, onBack = onBack) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // JS drives the page's i18n (localizes to the device language); without
            // it the German fallback text still renders.
            settings.javaScriptEnabled = true
            // Harden: a legal page has no business touching local files.
            denyLocalFileAccess()
            // Best-effort: hide the page's own heading before first paint. Falls
            // back to a post-load inject (brief flash) on WebViews that lack the
            // document-start feature.
            val hasDocumentStart =
              WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (hasDocumentStart) {
              WebViewCompat.addDocumentStartJavaScript(this, HIDE_HEADING_JS, LEGAL_ORIGINS)
            }
            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                // A tap on the cross-link to the other legal doc opens it as its own
                // screen, so the system back button returns here. (The initial page
                // load isn't routed through here.) Everything else — external references
                // (e.g. the linked data-protection authority), the site's home link, and
                // non-web schemes like mailto:/tel: (which a WebView can't render and
                // would show an error page for) — is handed to the system app.
                if (LegalLinks.isPrivacy(target) || LegalLinks.isImprint(target)) {
                  // Same doc, other language (e.g. /privacy → /en/privacy): the page's
                  // lang switch is a JS location.replace that also routes here. Load it
                  // in place rather than stacking a second screen — only the cross-link
                  // to the *other* doc opens a new one. (iOS distinguishes these two by
                  // navigation type instead, so it needs no equivalent.)
                  if (LegalLinks.isImprint(target) == LegalLinks.isImprint(url)) return false
                  onOpenDoc(target)
                  return true
                }
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
              }
              override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loading = true
                failed = false  // a fresh load (incl. Retry) clears any prior error
              }
              override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
                if (!hasDocumentStart) view?.evaluateJavascript(HIDE_HEADING_JS, null)
              }
              override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // Only the main document counts — a failed subresource shouldn't blank
                // the page. (An HTTP 4xx/5xx arrives via onReceivedHttpError, not here.)
                if (request.isForMainFrame) failed = true
              }
            }
            loadUrl(url)
          }.also { webViewRef = it }
        },
      )
      if (loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
      }
      if (failed) {
        // Opaque, so it hides the stock WebView error page underneath.
        ServerUnreachableRetry(onRetry = { webViewRef?.reload() })
      }
    }
  }
}
