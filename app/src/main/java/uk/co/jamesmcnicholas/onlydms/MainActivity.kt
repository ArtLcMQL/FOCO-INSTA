package uk.co.jamesmcnicholas.onlydms

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
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
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
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        imagesUnlocked = savedInstanceState?.getBoolean(STATE_IMAGES_UNLOCKED, false) ?: false
        configureWebView()
        installEarlyGuard()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(MESSAGES_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = imagesUnlocked

            val defaultUserAgent = userAgentString.orEmpty()
            if (!defaultUserAgent.contains("OnlyDMs/1.0")) {
                userAgentString = "$defaultUserAgent OnlyDMs/1.0".trim()
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
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

    private fun handleUrlOverride(view: WebView?, uri: Uri?): Boolean {
        if (uri == null) return true
        return when {
            uri.isDirectRoute() -> false
            uri.isAuthRoute() -> false
            else -> {
                view?.post { view.loadUrl(MESSAGES_URL) }
                true
            }
        }
    }

    private fun enforceRouteGuards(view: WebView?, url: String?) {
        val uri = parseUri(url) ?: return
        when {
            uri.isDirectRoute() -> {
                if (!imagesUnlocked) enableImages()
                reinforceDirectUi()
            }
            uri.isAuthRoute() -> {
                if (!imagesUnlocked) enableImages()
            }
            else -> view?.post { view.loadUrl(MESSAGES_URL) }
        }
    }

    private fun parseUri(raw: String?): Uri? =
        raw?.let { runCatching { it.toUri() }.getOrNull() }

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

    private fun enableImages() {
        imagesUnlocked = true
        webView.settings.loadsImagesAutomatically = true
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

    /**
     * Registers the media guard to run before Instagram's own scripts on every full
     * navigation, so its stylesheet is in place before the first paint. Without this
     * the guard would only start at page-finished, after the page has already been
     * drawn once with every reel visible.
     *
     * The script is also evaluated from [reinforceDirectUi] as a fallback for WebView
     * builds without document-start support; its install flag makes that idempotent.
     */
    private fun installEarlyGuard() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            EARLY_GUARD_SCRIPT,
            setOf("https://www.instagram.com", "https://instagram.com")
        )
    }

    private fun reinforceDirectUi() {
        webView.evaluateJavascript(EARLY_GUARD_SCRIPT, null)
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
        private const val MESSAGES_URL = "https://www.instagram.com/direct/inbox/"

        /**
         * Replaces shared reels and posts in a thread with a text placeholder and
         * keeps them from opening - with no frame in which the reel is visible.
         *
         * ### Identification by shape
         * Instagram obfuscates its class names and the card is not a link, so the one
         * thing that reliably distinguishes it from the rest of a thread is size: a
         * media element far larger than an avatar or an emoji.
         *
         * ### Why nothing flashes
         * Two things together. A stylesheet hides every video outright and every image
         * until it has been checked, so nothing unverified ever reaches the screen.
         * And the MutationObserver is unthrottled: its callback runs in the same task
         * that inserted the card, before the browser paints, so a large card is
         * replaced before it can be drawn and a small avatar is released in the same
         * breath. The earlier throttled version left a window of up to 250ms, which
         * is exactly the flash that was visible while scrolling.
         *
         * Scoped to /direct/ so the login page keeps its images.
         */
        private val EARLY_GUARD_SCRIPT = """
            (function() {
                if (window.__OnlyDMsGuard) {
                    return;
                }
                window.__OnlyDMsGuard = true;

                const MIN_H = 140;
                const MIN_W = 90;
                const OK = 'onlydmsOk';
                const MARK = 'onlydmsBlocked';

                function onDirect() {
                    return window.location.pathname.indexOf('/direct/') === 0;
                }

                function installStyle() {
                    if (!onDirect() || document.getElementById('onlydms-guard-style')) {
                        return;
                    }
                    const host = document.head || document.documentElement;
                    if (!host) {
                        return;
                    }
                    const style = document.createElement('style');
                    style.id = 'onlydms-guard-style';
                    style.textContent =
                        'video{display:none !important;}' +
                        'img:not([data-onlydms-ok="1"]){visibility:hidden !important;}';
                    host.appendChild(style);
                }

                function isOverlay(r) {
                    return r.height > window.innerHeight * 0.75;
                }

                // The element that owns the tap on a message: a link, a role=button, or
                // anything with a tabindex, within a few levels. Instagram makes the
                // whole bubble the tap target, so this is the bubble. It must stay
                // bubble-sized - the check keeps a mis-detected page-level container
                // from being treated as one.
                // Width is the discriminator: the bubble hugs the media horizontally,
                // while the message row is wider by the sender's avatar - and the row
                // must never be the scope, or its avatar and "Seen" text would mark a
                // plain photo as a share. Height stays loose because a share's header
                // and caption sit above and below the thumbnail inside the bubble.
                function findOwner(media) {
                    const base = media.getBoundingClientRect();
                    let node = media.parentElement;
                    for (let up = 0; up < 7 && node && node !== document.body; up += 1) {
                        const r = node.getBoundingClientRect();
                        if (r.width > base.width + 24 || r.height > base.height + 140) {
                            return null;
                        }
                        const role = node.getAttribute('role');
                        if (node.tagName === 'A' || role === 'button' || role === 'link' ||
                            node.hasAttribute('tabindex')) {
                            return node;
                        }
                        node = node.parentElement;
                    }
                    return null;
                }

                // Media someone recorded and sent - a photo or a video - is bare: nothing
                // in the bubble but the media itself, at most a duration badge. Content
                // shared from the feed is never bare: it carries the author's avatar and
                // name, usually a caption. So the bubble is a share when it holds more
                // than one image, or any text with letters in it. A play badge or a
                // <video> element proves nothing either way, since a recorded video has
                // both, and is deliberately not a signal.
                function isShare(media, owner) {
                    const scope = owner || media.parentElement || media;
                    if (scope.querySelectorAll('img').length > 1) {
                        return true;
                    }
                    const text = (scope.textContent || '').replace(/\s+/g, ' ').trim();
                    const letters = text.replace(/[^A-Za-z\u00C0-\u024F]/g, '').length;
                    return letters >= 3;
                }

                function swallowTap(e) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                }

                const TAP_EVENTS = ['click', 'pointerdown', 'pointerup', 'mousedown',
                    'mouseup', 'touchstart', 'touchend'];

                function neutralize(media, owner) {
                    // Without a tap owner, fall back to the wrapper that hugs the media.
                    let card = owner;
                    if (!card) {
                        const base = media.getBoundingClientRect();
                        card = media;
                        let node = media.parentElement;
                        for (let up = 0; up < 6 && node && node !== document.body; up += 1) {
                            const r = node.getBoundingClientRect();
                            if (r.height > base.height + 24 || r.width > base.width + 24) {
                                break;
                            }
                            card = node;
                            node = node.parentElement;
                        }
                    }
                    if (card.dataset[MARK] === '1') {
                        return;
                    }
                    card.dataset[MARK] = '1';
                    ['href', 'role', 'tabindex'].forEach(a => card.removeAttribute(a));
                    TAP_EVENTS.forEach(t => {
                        card.addEventListener(t, swallowTap, { capture: true, passive: false });
                    });
                    const note = document.createElement('div');
                    note.textContent = 'Conteudo do feed (bloqueado)';
                    note.style.cssText = 'display:inline-block;padding:10px 14px;' +
                        'border-radius:16px;background:rgba(127,127,127,0.18);' +
                        'color:#8e8e8e;font-size:14px;line-height:1.3;';
                    card.replaceChildren(note);
                    card.style.cssText += ';height:auto !important;width:auto !important;' +
                        'min-height:0 !important;aspect-ratio:auto !important;' +
                        'padding:0 !important;pointer-events:none !important;';
                }

                // Decides one media element: release it if small or a plain photo,
                // replace it if it is shared content, leave it hidden if it has no
                // size yet (the next pass will decide).
                function evaluate(media) {
                    try {
                        if (!media.isConnected) {
                            return;
                        }
                        const r = media.getBoundingClientRect();
                        if (r.width === 0 && r.height === 0) {
                            return;
                        }
                        if (r.height < MIN_H || r.width < MIN_W || isOverlay(r)) {
                            media.dataset[OK] = '1';
                            return;
                        }
                        const owner = findOwner(media);
                        if (!isShare(media, owner)) {
                            media.dataset[OK] = '1';
                            return;
                        }
                        delete media.dataset[OK];
                        neutralize(media, owner);
                    } catch (e) {
                        // never break the thread
                    }
                }

                function evaluateWithin(root) {
                    if (!root || root.nodeType !== 1) {
                        return;
                    }
                    const tag = root.tagName;
                    if (tag === 'IMG' || tag === 'VIDEO') {
                        evaluate(root);
                    }
                    root.querySelectorAll('img,video').forEach(evaluate);
                }

                function sweep() {
                    if (!onDirect()) {
                        return;
                    }
                    installStyle();
                    evaluateWithin(document.body);
                }

                function watch() {
                    const observer = new MutationObserver(function(records) {
                        if (!onDirect()) {
                            return;
                        }
                        installStyle();
                        for (const rec of records) {
                            if (rec.type === 'attributes') {
                                evaluate(rec.target);
                                continue;
                            }
                            rec.addedNodes.forEach(evaluateWithin);
                            // A share header can arrive after its thumbnail. Re-check the
                            // media around the insertion point, bounded to bubble-sized
                            // containers so a page-level container is never rescanned.
                            const t = rec.target;
                            if (t && t.nodeType === 1 && t.getBoundingClientRect().height < 900) {
                                evaluateWithin(t);
                            }
                        }
                    });
                    observer.observe(document.documentElement, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['src', 'poster']
                    });
                    // An image acquires its size when it loads; loading is not a
                    // mutation, so it is caught here instead.
                    document.addEventListener('load', function(e) {
                        if (e.target && e.target.tagName === 'IMG') {
                            evaluate(e.target);
                        }
                    }, true);
                    setInterval(sweep, 500);
                }

                installStyle();
                watch();
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', sweep, { once: true });
                } else {
                    sweep();
                }
            })();
        """.trimIndent()

        private const val STATE_IMAGES_UNLOCKED = "state_images_unlocked"
        // Video formats were on this list in the original app, which is why no video
        // ever played. They are off it now so recorded videos sent in a thread work;
        // shared reels are handled in the DOM by the early guard instead.
        private val HEAVY_PATTERNS = listOf(
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
