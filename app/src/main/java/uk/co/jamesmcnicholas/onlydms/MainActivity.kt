package uk.co.jamesmcnicholas.onlydms

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
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

        // Fires when the page hands the WebView a file rather than a document.
        webView.setDownloadListener { url, _, _, _, _ -> startDownload(url) }

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
        // The page asks for a download by navigating to this scheme (see the long-press
        // handler in the guard script). It is a request, not a navigation.
        if (uri.scheme == DOWNLOAD_SCHEME) {
            uri.getQueryParameter("u")?.let { startDownload(it) }
            return true
        }
        // Instagram's own save button opens the media file's URL. Left alone, the route
        // guard would treat that as leaving the inbox and bounce back; it is a download.
        if (uri.isMediaFile()) {
            startDownload(uri.toString())
            return true
        }
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

    /** Saves a media file to the Downloads folder through the system downloader. */
    private fun startDownload(url: String) {
        val uri = url.toUri()
        if (!uri.isSecureHttps()) {
            Toast.makeText(this, "Este item nao pode ser salvo", Toast.LENGTH_SHORT).show()
            return
        }
        val fromPath = uri.lastPathSegment.orEmpty().substringBefore('?')
        val name = if (fromPath.contains('.')) fromPath else "instagram_" + System.currentTimeMillis()
        try {
            val request = DownloadManager.Request(uri)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .addRequestHeader("User-Agent", webView.settings.userAgentString)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Salvando em Downloads", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Nao foi possivel salvar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun Uri.isMediaFile(): Boolean {
        if (!isSecureHttps()) return false
        val pathValue = path.orEmpty().lowercase(Locale.US)
        return MEDIA_EXTENSIONS.any { ext -> pathValue.endsWith(ext) }
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
        private const val DOWNLOAD_SCHEME = "onlydms-download"

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
                // A playing video's src is a blob (MediaSource) that cannot be saved.
                // Its https file URL is visible before playback starts, so it is noted
                // whenever it is seen and used for the download later.
                function rememberSource(media) {
                    if (media.tagName !== 'VIDEO') {
                        return;
                    }
                    let src = media.currentSrc || media.src || '';
                    if (src.indexOf('https:') !== 0) {
                        const source = media.querySelector('source[src^="https:"]');
                        src = source ? source.getAttribute('src') : '';
                    }
                    if (src.indexOf('https:') === 0) {
                        media.dataset.onlydmsSrc = src;
                    }
                }

                function evaluate(media) {
                    try {
                        if (!media.isConnected) {
                            return;
                        }
                        rememberSource(media);
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

                // Long press on a released photo or video saves it. The request reaches
                // the app as a navigation to a private scheme, which the app cancels and
                // turns into a download - no JavaScript bridge is exposed to the page.
                const PRESS_MS = 600;
                let pressTimer = null;
                let pressStart = null;

                function cancelPress() {
                    if (pressTimer) {
                        clearTimeout(pressTimer);
                        pressTimer = null;
                    }
                    pressStart = null;
                }

                function downloadUrlFor(el) {
                    if (el.tagName === 'VIDEO') {
                        return el.dataset.onlydmsSrc || '';
                    }
                    const src = el.currentSrc || el.src || '';
                    return src.indexOf('https:') === 0 ? src : '';
                }

                function requestDownload(el) {
                    const url = downloadUrlFor(el);
                    if (!url) {
                        return;
                    }
                    window.location.href = 'onlydms-download://save?u=' + encodeURIComponent(url);
                }

                document.addEventListener('touchstart', function(e) {
                    const target = e.target && e.target.closest ? e.target.closest('img,video') : null;
                    cancelPress();
                    if (!target || target.dataset[OK] !== '1') {
                        return;
                    }
                    const r = target.getBoundingClientRect();
                    if (r.height < MIN_H || r.width < MIN_W) {
                        return;
                    }
                    const touch = e.touches && e.touches[0];
                    pressStart = touch ? { x: touch.clientX, y: touch.clientY } : null;
                    pressTimer = setTimeout(function() {
                        pressTimer = null;
                        requestDownload(target);
                    }, PRESS_MS);
                }, { capture: true, passive: true });

                document.addEventListener('touchmove', function(e) {
                    if (!pressStart) {
                        return;
                    }
                    const touch = e.touches && e.touches[0];
                    if (touch && (Math.abs(touch.clientX - pressStart.x) > 12 ||
                        Math.abs(touch.clientY - pressStart.y) > 12)) {
                        cancelPress();
                    }
                }, { capture: true, passive: true });

                document.addEventListener('touchend', cancelPress, { capture: true, passive: true });
                document.addEventListener('touchcancel', cancelPress, { capture: true, passive: true });

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
