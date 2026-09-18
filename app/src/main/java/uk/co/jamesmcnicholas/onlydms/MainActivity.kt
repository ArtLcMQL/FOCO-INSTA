package uk.co.jamesmcnicholas.onlydms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.PermissionRequest
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var imagesUnlocked = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    /** The page's microphone request, held while the system permission dialog is up. */
    private var pendingPermission: PermissionRequest? = null

    private val microphoneLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingPermission ?: return@registerForActivityResult
        pendingPermission = null
        if (granted) request.grant(request.resources) else request.deny()
    }

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
        // targetSdk 35+ draws edge to edge, so the page's message bar would sit under
        // the navigation bar and the keyboard would cover the input. The insets are
        // applied as padding on a container rather than on the WebView itself, which
        // does not honour padding.
        // The bars are transparent under edge to edge, so the container shows
        // through behind them. Its colour and the icon shade follow the system theme,
        // matching the page, which the WebView darkens along with it.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val root = FrameLayout(this).apply {
            setBackgroundColor(if (night) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            addView(webView)
        }
        setContentView(root)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
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
            // Voice notes are started by the page after the tap, not during it, and
            // the stricter setting refused them.
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = imagesUnlocked

            // The stock string carries "; wv", which marks the client as an embedded
            // WebView, and Instagram serves embedded browsers a reduced experience in
            // some flows. Presented as plain Chrome it gets the same page Chrome gets.
            val defaultUserAgent = userAgentString.orEmpty()
            userAgentString = defaultUserAgent
                .replace("; wv)", ")")
                .replace(" OnlyDMs/1.0", "")
                .trim()
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
                // Only network schemes are filtered. blob: and data: URLs are created by
                // the page itself - the voice-note player hands its audio to <audio> as a
                // blob - and answering them with an empty body silently broke playback.
                val scheme = uri.scheme.orEmpty().lowercase(Locale.US)
                if (scheme != "http" && scheme != "https") {
                    return super.shouldInterceptRequest(view, request)
                }
                if (!uri.isSecureHttps()) {
                    return emptyResponse()
                }

                val host = uri.host.orEmpty().lowercase(Locale.US)
                val allowedHost = host.contains("instagram.com") ||
                    host.contains("cdninstagram") ||
                    host.contains("fbcdn.net") ||
                    host.contains("fbsbx.com")

                // A media file is never a tracker, so it is let through on its extension
                // alone. The voice-note .ogg files come from cdn.fbsbx.com, a host the
                // allowlist did not know, which is how every audio clip was refused.
                val pathValue = uri.path.orEmpty().lowercase(Locale.US)
                val isMediaFile = MEDIA_EXTENSIONS.any { ext -> pathValue.endsWith(ext) }
                if (isMediaFile) {
                    return super.shouldInterceptRequest(view, request)
                }

                val url = uri.toString().lowercase(Locale.US)
                val heavyMatch = HEAVY_PATTERNS.any { pattern -> url.contains(pattern) }

                return if (!allowedHost || heavyMatch) {
                    reportBlocked(uri)
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
            /**
             * Recording a voice note makes the page ask for the microphone. WebView
             * denies every such request unless the app answers it, and answering means
             * holding the system permission first; the request is parked while the
             * dialog is up and settled in [microphoneLauncher].
             */
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsMic) {
                    request.deny()
                    return
                }
                val held = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (held) {
                    request.grant(request.resources)
                } else {
                    pendingPermission?.deny()
                    pendingPermission = request
                    microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

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
                reportDiag("NAV bloqueada -> inbox: " + uri.toString().take(60))
                view?.post { view.loadUrl(MESSAGES_URL) }
                true
            }
        }
    }

    /** DIAGNOSTICO TEMPORARIO. */
    private fun reportDiag(line: String) {
        val safe = line.replace("\\", "").replace("'", "")
        webView.post {
            webView.evaluateJavascript(
                "window.__onlydmsDiag && window.__onlydmsDiag('$safe')", null
            )
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

    /**
     * DIAGNOSTICO TEMPORARIO. Hands a blocked URL to the page's on-screen panel so
     * a screenshot shows what the network filter refused. Runs on the WebView's
     * network thread, hence the post to the main thread.
     */
    private fun reportBlocked(uri: Uri) {
        val label = (uri.host.orEmpty().take(34) + " " + uri.path.orEmpty().takeLast(36))
            .replace("\\", "").replace("'", "")
        webView.post {
            webView.evaluateJavascript(
                "window.__onlydmsDiag && window.__onlydmsDiag('REDE barrou: $label')", null
            )
        }
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
                                    if (window.__onlydmsDiag) {
                                        window.__onlydmsDiag('HIST ' + fn + ' ' + next.pathname.slice(0, 50));
                                    }
                                    if (!allowedPath(next.pathname)) {
                                        if (window.__onlydmsDiag) {
                                            window.__onlydmsDiag('HIST redirecionado -> inbox');
                                        }
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
                        'img:not([data-onlydms-ok="1"]),' +
                        'video:not([data-onlydms-ok="1"]){visibility:hidden !important;}';
                    host.appendChild(style);
                }

                // A media element that fills the viewport belongs to a viewer, not to
                // the thread. Either dimension is enough: a landscape photo in the
                // viewer is short but spans the full width.
                function isOverlay(r) {
                    return r.height > window.innerHeight * 0.75 ||
                        r.width > window.innerWidth * 0.9;
                }

                // Climbs from the media to the bubble. Width is the discriminator: the
                // bubble hugs the media horizontally, while the message row is wider by
                // the sender's avatar - and the row must never be in scope, or its avatar
                // and "Seen" text would mark a plain photo as a share. Height stays
                // loose because a share's header and caption sit above and below the
                // thumbnail inside the bubble.
                //
                // Returns the highest in-bounds ancestor as the scope to classify, and
                // as the owner to neutralise the first tap target found on the way up,
                // falling back to the scope itself. The scope does not depend on a tap
                // target existing: a share with no role or tabindex on its bubble must
                // still be classified with its header in view.
                function findScope(media) {
                    const base = media.getBoundingClientRect();
                    let scope = media.parentElement || media;
                    let owner = null;
                    let node = media.parentElement;
                    for (let up = 0; up < 7 && node && node !== document.body; up += 1) {
                        const r = node.getBoundingClientRect();
                        if (r.width > base.width + 24 || r.height > base.height + 220) {
                            break;
                        }
                        scope = node;
                        if (!owner) {
                            const role = node.getAttribute('role');
                            if (node.tagName === 'A' || role === 'button' || role === 'link' ||
                                node.hasAttribute('tabindex')) {
                                owner = node;
                            }
                        }
                        node = node.parentElement;
                    }
                    return { scope: scope, owner: owner || scope };
                }

                // Returns the reason the bubble counts as a share, or null when it is
                // bare media. The reason is shown in the placeholder for now, so a
                // wrongly blocked message explains itself in a screenshot.
                // Applies to <video> too: a shared reel can arrive as a <video> with a
                // preview, and what distinguishes it from a recorded video is the same
                // author header. A recorded video's bubble holds no letters and no
                // avatar-sized image, so it passes.
                function shareReason(media, scope) {
                    // The author's name is the one signal a share always carries and
                    // recorded media never does. Counting extra images was tried and
                    // dropped: Instagram draws emoji reactions as tiny <img> elements, so
                    // a heart on a recorded video read as an author avatar and the whole
                    // bubble was neutralised - which is what made videos untappable.
                    const text = (scope.textContent || '').replace(/\s+/g, ' ').trim();
                    const letters = text.replace(/[^A-Za-z\u00C0-\u024F]/g, '').length;
                    if (letters >= 3) {
                        return 'txt:' + text.slice(0, 40);
                    }
                    return null;
                }

                function swallowTap(e) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                }

                const TAP_EVENTS = ['click', 'pointerdown', 'pointerup', 'mousedown',
                    'mouseup', 'touchstart', 'touchend'];

                function neutralize(media, card, reason) {
                    if (card.dataset[MARK] === '1') {
                        return;
                    }
                    card.dataset[MARK] = '1';
                    ['href', 'role', 'tabindex'].forEach(a => card.removeAttribute(a));
                    TAP_EVENTS.forEach(t => {
                        card.addEventListener(t, swallowTap, { capture: true, passive: false });
                    });
                    const note = document.createElement('div');
                    note.textContent = 'Conteudo do feed (bloqueado) [' + (reason || '?') + ']';
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
                        const found = findScope(media);
                        const reason = shareReason(media, found.scope);
                        if (!reason) {
                            media.dataset[OK] = '1';
                            return;
                        }
                        delete media.dataset[OK];
                        neutralize(media, found.owner, reason);
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

                // DIAGNOSTICO TEMPORARIO: painel no topo com o que a rede barrou e o
                // que os elementos de midia reportaram. Nao intercepta toques.
                const diagLines = [];
                function diagPanel() {
                    let box = document.getElementById('onlydms-diag');
                    if (!box && document.body) {
                        box = document.createElement('div');
                        box.id = 'onlydms-diag';
                        box.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;' +
                            'background:rgba(0,0,0,0.82);color:#7f7;font:10px/1.35 monospace;' +
                            'padding:4px 6px;pointer-events:none;white-space:pre-wrap;' +
                            'max-height:38vh;overflow:hidden;';
                        document.body.appendChild(box);
                    }
                    return box;
                }
                window.__onlydmsDiag = function(line) {
                    const stamp = new Date().toTimeString().slice(3, 8);
                    diagLines.push(stamp + ' ' + line);
                    while (diagLines.length > 9) {
                        diagLines.shift();
                    }
                    const box = diagPanel();
                    if (box) {
                        box.textContent = diagLines.join('\n') || '(sem eventos)';
                    }
                };
                const MEDIA_ERR = ['', 'ABORTED', 'NETWORK', 'DECODE', 'SRC_NOT_SUPPORTED'];
                function describeMedia(el) {
                    const src = (el.currentSrc || el.src || '');
                    const scheme = src.split(':')[0] || '(sem src)';
                    return el.tagName + ' src=' + scheme + ' rede=' + el.networkState +
                        ' ready=' + el.readyState;
                }
                document.addEventListener('error', function(e) {
                    const el = e.target;
                    if (!el || (el.tagName !== 'VIDEO' && el.tagName !== 'AUDIO')) {
                        return;
                    }
                    const code = el.error ? el.error.code : 0;
                    const msg = el.error && el.error.message ? ' ' + el.error.message.slice(0, 50) : '';
                    window.__onlydmsDiag('ERRO ' + (MEDIA_ERR[code] || code) + msg + ' | ' + describeMedia(el));
                }, true);
                ['play', 'playing', 'stalled', 'suspend', 'abort', 'emptied'].forEach(function(name) {
                    document.addEventListener(name, function(e) {
                        const el = e.target;
                        if (!el || (el.tagName !== 'VIDEO' && el.tagName !== 'AUDIO')) {
                            return;
                        }
                        window.__onlydmsDiag(name.toUpperCase() + ' ' + describeMedia(el));
                    }, true);
                });
                setTimeout(function() { window.__onlydmsDiag('painel ativo ' + location.pathname.slice(0, 30)); }, 1500);
                // Toques perto de um video: mostra a cadeia de tags do alvo, para ver
                // o que recebe o toque e se ele chega ao player.
                document.addEventListener('click', function(e) {
                    const el = e.target;
                    if (!el || !el.closest) { return; }
                    const bubble = el.closest('[role="button"],a,[tabindex]') || el.parentElement;
                    if (!bubble || !bubble.querySelector('video')) { return; }
                    let chain = el.tagName;
                    let n = el.parentElement;
                    for (let i = 0; i < 3 && n; i += 1) {
                        chain += '<' + n.tagName + (n.getAttribute('role') ? '.' + n.getAttribute('role') : '');
                        n = n.parentElement;
                    }
                    window.__onlydmsDiag('TOQUE ' + chain + (e.defaultPrevented ? ' (impedido)' : ''));
                }, true);

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
        private val MEDIA_EXTENSIONS = listOf(
            ".ogg", ".oga", ".opus", ".m4a", ".aac", ".mp3", ".wav",
            ".mp4", ".webm", ".m3u8", ".mpd", ".ts",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic"
        )

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
