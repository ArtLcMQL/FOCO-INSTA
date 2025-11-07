package com.example.onlydms

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var imagesUnlocked = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileCallback
        fileCallback = null

        if (callback == null) return@registerForActivityResult

        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val clipData = data?.clipData
            when {
                clipData != null -> {
                    val uris = Array(clipData.itemCount) { index ->
                        clipData.getItemAt(index).uri
                    }
                    callback.onReceiveValue(uris)
                }
                data?.data != null -> {
                    callback.onReceiveValue(arrayOf(data.data!!))
                }
                else -> callback.onReceiveValue(emptyArray())
            }
        } else {
            callback.onReceiveValue(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(webView)

        imagesUnlocked = savedInstanceState?.getBoolean(STATE_IMAGES_UNLOCKED, false) ?: false
        configureWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(DIRECT_INBOX_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = imagesUnlocked

            val defaultUserAgent = userAgentString.orEmpty()
            if (!defaultUserAgent.contains("OnlyDMs/1.0")) {
                userAgentString = "$defaultUserAgent OnlyDMs/1.0".trim()
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                webView.settings,
                WebSettingsCompat.FORCE_DARK_AUTO
            )
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = handleUrlOverride(view, request?.url)

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                handleUrlOverride(view, parseUri(url))

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                enforceRouteGuards(view, url)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                if (!uri.isSecureHttps()) {
                    return emptyResponse()
                }

                val host = uri.host.orEmpty().lowercase(Locale.US)
                val allowedHost = host.contains("instagram.com") ||
                    host.contains("cdninstagram") ||
                    host.contains("fbcdn.net")

                val url = uri.toString().lowercase(Locale.US)
                val heavyMatch = HEAVY_PATTERNS.any { pattern -> url.contains(pattern) }

                return if (!allowedHost || heavyMatch) {
                    emptyResponse()
                } else {
                    super.shouldInterceptRequest(view, request)
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                view?.destroy()
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileCallback?.onReceiveValue(null)
                this@MainActivity.fileCallback = filePathCallback

                val chooserIntent = fileChooserParams?.createIntent()?.apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                return try {
                    mediaPickerLauncher.launch(Intent.createChooser(chooserIntent, "Choose media"))
                    true
                } catch (_: Exception) {
                    this@MainActivity.fileCallback?.onReceiveValue(null)
                    this@MainActivity.fileCallback = null
                    false
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        outState.putBoolean(STATE_IMAGES_UNLOCKED, imagesUnlocked)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun handleUrlOverride(view: WebView?, uri: Uri?): Boolean {
        if (uri == null) return true
        return when {
            uri.isDirectRoute() -> false
            uri.isAuthRoute() -> false
            else -> {
                view?.post { view.loadUrl(DIRECT_INBOX_URL) }
                true
            }
        }
    }

    private fun enforceRouteGuards(view: WebView?, url: String?) {
        val uri = parseUri(url) ?: return
        when {
            uri.isDirectRoute() -> {
                if (!imagesUnlocked) {
                    imagesUnlocked = true
                    webView.settings.loadsImagesAutomatically = true
                }
                reinforceDirectUi()
            }
            uri.isAuthRoute() -> Unit
            else -> view?.post { view.loadUrl(DIRECT_INBOX_URL) }
        }
    }

    private fun String?.isDirectRoute(): Boolean =
        parseUri(this)?.isDirectRoute() == true

    private fun parseUri(raw: String?): Uri? =
        raw?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun Uri.isDirectRoute(): Boolean {
        val hostValue = host?.lowercase(Locale.US) ?: return false
        if (!hostValue.contains("instagram.com")) return false
        val pathValue = (path ?: "").lowercase(Locale.US)
        return pathValue.startsWith("/direct/")
    }

    private fun Uri.isAuthRoute(): Boolean {
        val hostValue = host?.lowercase(Locale.US) ?: return false
        if (!hostValue.contains("instagram.com")) return false
        val pathValue = (path ?: "").lowercase(Locale.US)
        return pathValue.startsWith("/accounts/login") ||
            pathValue.startsWith("/accounts/onetap") ||
            pathValue.startsWith("/challenge/")
    }

    private fun Uri.isSecureHttps(): Boolean =
        scheme?.equals("https", ignoreCase = true) == true

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        ).apply {
            setStatusCodeAndReasonPhrase(200, "OK")
        }

    private fun reinforceDirectUi() {
        val script = """
            (function() {
                const INBOX = 'https://www.instagram.com/direct/inbox/';

                function hideNav() {
                    const selectors = [
                        'a[href="/"]',
                        'a[href="/explore/"]',
                        'header a[role="link"]',
                        'header button',
                        'header [role="button"]',
                        'nav a[role="link"]',
                        'nav button',
                        'button[aria-label="Back"]',
                        'div[role="dialog"] a[role="link"]'
                    ];
                    selectors.forEach(sel => {
                        document.querySelectorAll(sel).forEach(el => {
                            el.style.display = 'none';
                            el.addEventListener('click', function(e) {
                                e.preventDefault();
                                window.location.href = INBOX;
                            }, { once: true, passive: false });
                        });
                    });
                }

                function guardHistory() {
                    if (window.__OnlyDMsHistoryGuard) {
                        return;
                    }
                    const allowedPath = p => p.startsWith('/direct/');
                    ['pushState', 'replaceState'].forEach(fn => {
                        const original = history[fn];
                        history[fn] = function(state, title, url) {
                            try {
                                if (typeof url === 'string') {
                                    const next = new URL(url, window.location.origin);
                                    if (!allowedPath(next.pathname)) {
                                        window.location.href = INBOX;
                                        return;
                                    }
                                }
                            } catch (e) {
                                // ignore parsing errors
                            }
                            return original.apply(this, arguments);
                        };
                    });
                    window.__OnlyDMsHistoryGuard = true;
                }

                function lazyMedia() {
                    document.querySelectorAll('img,video').forEach(el => {
                        try {
                            if (el.tagName === 'IMG') {
                                el.loading = 'lazy';
                                el.decoding = 'async';
                            } else if (el.tagName === 'VIDEO') {
                                el.preload = 'metadata';
                                el.autoplay = false;
                            }
                        } catch (e) {
                            // swallow attribute assignment issues
                        }
                    });
                }

                hideNav();
                lazyMedia();
                guardHistory();
                if (!window.__OnlyDMsDomGuard) {
                    window.__OnlyDMsDomGuard = setInterval(() => {
                        hideNav();
                        lazyMedia();
                    }, 2000);
                    setTimeout(() => {
                        if (window.__OnlyDMsDomGuard) {
                            clearInterval(window.__OnlyDMsDomGuard);
                            window.__OnlyDMsDomGuard = null;
                        }
                    }, 8000);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    companion object {
        private const val DIRECT_INBOX_URL = "https://www.instagram.com/direct/inbox/"
        private const val STATE_IMAGES_UNLOCKED = "state_images_unlocked"
        private val HEAVY_PATTERNS = listOf(
            ".mp4",
            ".m3u8",
            ".ts",
            ".webm",
            ".woff",
            ".woff2",
            ".ttf",
            "/reel/",
            "/stories/",
            "/ads/",
            "doubleclick.net",
            "facebook.net",
            "analytics"
        )
    }
}
