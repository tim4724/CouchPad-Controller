import SwiftUI
import WebKit
import UIKit

/// A read-only in-app viewer for a hosted legal document (privacy / imprint).
/// Deliberately separate from `GameWebView`: no navigation allow-list and no JS
/// bridge — these are trusted first-party pages, loaded so the documents stay
/// reachable from within the app.
///
/// Each screen shows exactly one document. Tapping the page's cross-link to the
/// other doc opens it as its own pushed screen (`onOpenDoc`), so the standard
/// system Back button and swipe just work — no custom back handling.
struct WebDocScreen: View {
    let url: String
    let title: String
    let onOpenDoc: (String) -> Void

    @Environment(\.cpPalette) private var palette
    @State private var isLoading = true
    // Main-document load failure (no connection / host unreachable). Retry bumps a
    // token the representable observes to re-issue the request.
    @State private var failed = false
    @State private var reloadToken = 0

    var body: some View {
        ZStack {
            // The screen chrome uses the same surface as home/About (and as the
            // hosted page itself), so nothing shows the stock WKWebView white.
            palette.background.ignoresSafeArea()
            WebDocView(url: url, surface: UIColor(palette.background), isLoading: $isLoading,
                       failed: $failed, reloadToken: reloadToken, onOpenDoc: onOpenDoc)
                .ignoresSafeArea(.container, edges: .bottom)
            if isLoading && !failed {
                ProgressView()
            }
            if failed {
                RetryCover(background: palette.background, foreground: palette.onBackground) {
                    reloadToken += 1
                }
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct WebDocView: UIViewRepresentable {
    let url: String
    let surface: UIColor
    @Binding var isLoading: Bool
    @Binding var failed: Bool
    let reloadToken: Int
    let onOpenDoc: (String) -> Void

    // The page renders its own <h1> title (e.g. "DATENSCHUTZERKLÄRUNG"); in-app the
    // native nav bar already shows it, so hide the page copy to avoid the duplicate.
    // Injected at document-start so the heading never flashes before it's hidden.
    //
    // The page ships a strict CSP (style-src 'self'), so an injected inline <style>
    // element is rejected by the browser and never applies. A constructable
    // stylesheet is a pure CSSOM object — exempt from CSP — and still covers nodes
    // added after injection. Older WebViews without it fall back to setting the
    // style directly on each heading via the CSSOM (also CSP-exempt).
    private static let hideHeadingJS = """
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

    func makeCoordinator() -> Coordinator {
        Coordinator(isLoading: $isLoading, failed: $failed, onOpenDoc: onOpenDoc)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.userContentController.addUserScript(
            WKUserScript(source: Self.hideHeadingJS, injectionTime: .atDocumentStart, forMainFrameOnly: true)
        )

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        // Match the app surface while the page is blank — kills the white flash and
        // the slight #FFFFFF-vs-#FAFAFB seam against the page background.
        webView.isOpaque = false
        webView.backgroundColor = surface
        webView.scrollView.backgroundColor = surface
        if let requestURL = URL(string: url) {
            webView.load(URLRequest(url: requestURL))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        // A Retry tap bumps reloadToken; re-issue the original request (reload() is a
        // no-op when the prior navigation never committed, e.g. offline at first load).
        if reloadToken != context.coordinator.lastReloadToken {
            context.coordinator.lastReloadToken = reloadToken
            if let requestURL = URL(string: url) {
                uiView.load(URLRequest(url: requestURL))
            }
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        private let isLoading: Binding<Bool>
        private let failed: Binding<Bool>
        private let onOpenDoc: (String) -> Void
        var lastReloadToken = 0

        init(isLoading: Binding<Bool>, failed: Binding<Bool>, onOpenDoc: @escaping (String) -> Void) {
            self.isLoading = isLoading
            self.failed = failed
            self.onOpenDoc = onOpenDoc
        }

        // Dispatch async so we never mutate SwiftUI state during a view update.
        private func setLoading(_ value: Bool) {
            DispatchQueue.main.async { self.isLoading.wrappedValue = value }
        }

        private func setFailed(_ value: Bool) {
            DispatchQueue.main.async { self.failed.wrappedValue = value }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let target = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }
            if CP.legalTitle(for: target) != nil {
                // A tap on the cross-link to the other legal doc opens it as its own
                // pushed screen, so the system Back button returns here. The initial
                // page load (navigationType .other) just loads in place.
                if navigationAction.navigationType == .linkActivated {
                    let destination = target.absoluteString
                    DispatchQueue.main.async { self.onOpenDoc(destination) }
                    decisionHandler(.cancel)
                } else {
                    decisionHandler(.allow)
                }
            } else {
                // External references (e.g. the linked data-protection authority), the
                // site's home link, and non-web schemes like mailto:/tel: (which
                // WKWebView can't render and would surface an error page for) are handed
                // to the system app (browser/mail/dialer).
                UIApplication.shared.open(target)
                decisionHandler(.cancel)
            }
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            setLoading(true)
            setFailed(false)  // a fresh load (incl. Retry) clears any prior error
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            setLoading(false)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            reportLoadFailure(error)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            reportLoadFailure(error)
        }

        // A real load failure (offline, host unreachable, TLS/timeout) surfaces the
        // error state; a deliberately-cancelled navigation (external links, the
        // cross-doc push) is not one.
        private func reportLoadFailure(_ error: Error) {
            setLoading(false)
            if isDeliberateNavigationCancellation(error) { return }
            setFailed(true)
        }
    }
}
