package com.example.onlydms

import android.app.Application
import android.os.Process
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class OnlyDMsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        warmUpWebView()
        preconnectInstagram()
    }

    private fun warmUpWebView() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
                WebViewCompat.startSafeBrowsing(this) { /* no-op */ }
            }
            WebView(this).apply {
                settings.javaScriptEnabled = false
                setWillNotDraw(true)
                destroy()
            }
        } catch (_: Throwable) {
            // fall back to lazy init
        }
    }

    private fun preconnectInstagram() {
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val connection = URL(PREFETCH_URL).openConnection() as HttpsURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("User-Agent", PREFETCH_UA)
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(512)
                    while (stream.read(buffer) != -1) {
                        // drain response
                    }
                }
                connection.disconnect()
            } catch (_: Exception) {
                // Prefetch is opportunistic; ignore issues.
            }
        }.start()
    }

    companion object {
        private const val PREFETCH_URL = "https://www.instagram.com/robots.txt"
        private const val PREFETCH_UA = "Mozilla/5.0 (Android) OnlyDMs/1.0 Prefetch"
    }
}
