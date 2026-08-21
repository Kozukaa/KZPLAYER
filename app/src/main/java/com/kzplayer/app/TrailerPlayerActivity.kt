package com.kzplayer.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * v340 : lecteur de bande-annonce INTERNE.
 *
 * Avant : l'iframe YouTube renvoyait souvent "Regarder sur YouTube" / erreur 503 quand la
 * video interdit l'integration, et l'utilisateur sortait de l'application.
 *
 * Maintenant :
 *  - user-agent bureau + iframe injectee avec une base https (bien mieux accepte par YouTube),
 *  - TOUTE navigation reste dans l'application (aucune ouverture de l'app YouTube),
 *  - si l'integration est refusee ou en erreur, bascule automatique sur la page video mobile,
 *  - bouton "Autre lecteur" pour forcer cette bascule a la main.
 */
class TrailerPlayerActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var videoId = ""
    private var rawUrl = ""
    private var usedFallback = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trailer_player)

        rawUrl = intent.getStringExtra("url").orEmpty()
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "Bande-annonce" }
        findViewById<TextView>(R.id.titleTv).text = title
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView?>(R.id.altBtn)?.setOnClickListener { loadFallback() }

        web = findViewById(R.id.trailerWeb)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            // Rien ne sort de l'application : on recharge tout dans cette WebView.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString().orEmpty()
                if (u.isBlank()) return true
                if (u.startsWith("intent:") || u.startsWith("vnd.youtube") || u.startsWith("market:")) {
                    loadFallback()
                    return true
                }
                view?.loadUrl(u)
                return true
            }
            @Deprecated("compat")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = url.orEmpty()
                if (u.isBlank()) return true
                if (u.startsWith("intent:") || u.startsWith("vnd.youtube") || u.startsWith("market:")) {
                    loadFallback()
                    return true
                }
                view?.loadUrl(u)
                return true
            }
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) loadFallback()
            }
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) loadFallback()
            }
        }
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus()

        videoId = extractId(rawUrl)
        if (videoId.isBlank() && rawUrl.isBlank()) { finish(); return }
        if (videoId.isBlank()) web.loadUrl(rawUrl) else loadEmbed()
    }

    private fun loadEmbed() {
        val src = "https://www.youtube.com/embed/" + videoId +
            "?autoplay=1&rel=0&modestbranding=1&playsinline=1&fs=1"
        val html = "<!DOCTYPE html><html><head><meta name='viewport' " +
            "content='width=device-width, initial-scale=1.0, user-scalable=no'>" +
            "<style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}" +
            "iframe{border:0;width:100%;height:100%}</style></head><body>" +
            "<iframe src='" + src + "' allow='autoplay; encrypted-media; fullscreen' " +
            "allowfullscreen></iframe></body></html>"
        web.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
    }

    // Page video mobile YouTube, chargee DANS l'application : fonctionne meme quand
    // l'integration (iframe) est interdite par le proprietaire de la video.
    private fun loadFallback() {
        if (usedFallback) return
        usedFallback = true
        val u = if (videoId.isNotBlank()) "https://m.youtube.com/watch?v=" + videoId else rawUrl
        if (u.isBlank()) return
        web.post { web.loadUrl(u) }
    }

    private fun extractId(url: String): String {
        val u = url.trim()
        if (u.isBlank()) return ""
        return when {
            u.contains("youtube.com/watch") ->
                Regex("[?&]v=([^&]+)").find(u)?.groupValues?.getOrNull(1).orEmpty()
            u.contains("youtu.be/") -> u.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
            u.contains("/embed/") -> u.substringAfter("/embed/").substringBefore("?").substringBefore("&")
            else -> ""
        }
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }
}
