package com.kzplayer.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TrailerPlayerActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trailer_player)

        val rawUrl = intent.getStringExtra("url").orEmpty()
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "Bande-annonce" }
        findViewById<TextView>(R.id.titleTv).text = title
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        web = findViewById(R.id.trailerWeb)
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus()

        val embed = toEmbedUrl(rawUrl)
        if (embed.isBlank()) { finish(); return }
        web.loadUrl(embed)
    }

    private fun toEmbedUrl(url: String): String {
        val u = url.trim()
        if (u.isBlank()) return ""
        val id = when {
            u.contains("youtube.com/watch") -> Regex("[?&]v=([^&]+)").find(u)?.groupValues?.getOrNull(1).orEmpty()
            u.contains("youtu.be/") -> u.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
            u.contains("youtube.com/embed/") -> u.substringAfter("/embed/").substringBefore("?").substringBefore("&")
            else -> ""
        }
        return if (id.isBlank()) u else "https://www.youtube.com/embed/$id?autoplay=1&rel=0&modestbranding=1&playsinline=0"
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
