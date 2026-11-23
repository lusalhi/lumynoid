package dev.lusalhi.lumynoid.app

import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.util.Locale

private const val HOST = "lumynoid-preview.invalid"
private const val PREFIX = "/workspace/"
private const val SOURCE = "https://$HOST$PREFIX"

/** Only ArtifactHtmlActivity calls this in production, after verifying its Internet permission is denied. */
@Suppress("SetJavaScriptEnabled", "DEPRECATION")
internal fun createOfflinePreviewWebView(
    context: Context,
    path: String,
    javascript: Boolean,
    read: (String) -> ByteArray?,
    onNavigationBlocked: ((String) -> Unit)? = null,
    onUnavailable: () -> Unit,
): WebView {
    val document = Uri.Builder().scheme("https").authority(HOST).appendPath("workspace").apply {
        for (component in path.split('/')) appendPath(component)
    }.build()
    val headers = mapOf(
        "Content-Security-Policy" to policy(javascript),
        "X-Content-Type-Options" to "nosniff",
        "X-DNS-Prefetch-Control" to "off",
        "Referrer-Policy" to "no-referrer",
        "Cache-Control" to "no-store",
        // The sandbox has an opaque origin; local module scripts and fonts still need CORS.
        "Access-Control-Allow-Origin" to "*",
    )
    val loader = WebViewAssetLoader.Builder().setDomain(HOST).addPathHandler(PREFIX) { relative ->
        val mime = if (relative == path) "text/html" else resourceMime(relative)
        val bytes = if (mime == null) null else read(relative)
        if (mime == null || bytes == null) blockedResponse() else WebResourceResponse(
            mime, if (mime.startsWith("text/") || mime == "image/svg+xml") "UTF-8" else null,
            200, "OK", headers, ByteArrayInputStream(bytes),
        )
    }.build()

    return WebView(context).apply {
        settings.apply {
            javaScriptEnabled = javascript
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true) // There is no WebChromeClient to create a window.
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            safeBrowsingEnabled = false // No network lookups; all resource responses belong to this preview.
            disabledActionModeMenuItems =
                WebSettings.MENU_ITEM_SHARE or WebSettings.MENU_ITEM_WEB_SEARCH or WebSettings.MENU_ITEM_PROCESS_TEXT
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (onNavigationBlocked != null) onNavigationBlocked(request.url.toString())
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                onNavigationBlocked?.invoke(url)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (request.method != "GET" || url.scheme != "https" || url.encodedAuthority != HOST ||
                    url.encodedPath?.startsWith(PREFIX) != true ||
                    (request.isForMainFrame && (url.path != document.path || url.query != null))
                ) return blockedResponse()
                return loader.shouldInterceptRequest(url) ?: blockedResponse()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) onUnavailable()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onUnavailable()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onUnavailable()
                return true
            }
        }
        loadUrl(document.toString())
    }
}

private fun blockedResponse() = WebResourceResponse(
    "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()),
)

private fun policy(javascript: Boolean): String {
    val sandbox = if (javascript) "sandbox allow-scripts" else "sandbox"
    val scripts = if (javascript) "$SOURCE 'unsafe-inline'" else "'none'"
    return "$sandbox; default-src 'none'; connect-src 'none'; script-src $scripts; " +
        "style-src $SOURCE 'unsafe-inline'; img-src $SOURCE data:; font-src $SOURCE; " +
        "object-src 'none'; frame-src 'none'; worker-src 'none'; frame-ancestors 'none'; " +
        "form-action 'none'; base-uri 'none'"
}

private fun resourceMime(path: String): String? = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "css" -> "text/css"
    "js", "mjs" -> "text/javascript"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> null
}
