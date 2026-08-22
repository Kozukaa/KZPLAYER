package com.kzplayer.app

import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.TextView

/**
 * v348 : lecture de la bande-annonce en plein ecran, presentee comme le lecteur KZ.
 *
 * L extraction directe du flux est desormais bloquee par YouTube (LOGIN_REQUIRED,
 * UNPLAYABLE...). On utilise donc le lecteur officiel integre, mais SANS aucun
 * habillage : pas de barre, pas de titre, pas de logo, pas de bouton externe.
 * Une couche transparente couvre toute la surface pour qu aucun clic ne puisse
 * ouvrir YouTube. Fond noir et bouton retour KZ, comme le lecteur habituel.
 */
class TrailerActivity : BaseActivity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trailer)

        val vid = intent.getStringExtra("videoId") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        findViewById<TextView>(R.id.trailerTitle).text = title
        val backBtn = findViewById<View>(R.id.trailerBack)
        backBtn.setOnClickListener { finish() }
        backBtn.requestFocus()

        if (vid.isBlank()) { finish(); return }

        val w = findViewById<WebView>(R.id.trailerWebView)
        web = w
        w.setBackgroundColor(android.graphics.Color.BLACK)
        val st = w.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.mediaPlaybackRequiresUserGesture = false
        st.loadWithOverviewMode = true
        st.useWideViewPort = true
        st.cacheMode = WebSettings.LOAD_NO_CACHE
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.loadDataWithBaseURL("https://www.youtube.com", html(vid), "text/html", "utf-8", null)
    }

    /** Page minimale : une seule video, aucun element d interface visible. */
    private fun html(vid: String): String {
        val src = "https://www.youtube.com/embed/" + vid +
            "?autoplay=1&controls=0&modestbranding=1&rel=0&fs=0&disablekb=1" +
            "&iv_load_policy=3&playsinline=1&showinfo=0&color=white"
        val sb = StringBuilder()
        sb.append("<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<style>html,body{margin:0;padding:0;background:#000;overflow:hidden;height:100%}")
        sb.append("iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}")
        sb.append(".mask{position:absolute;top:0;left:0;width:100%;height:100%;background:transparent}")
        sb.append("</style></head><body>")
        sb.append("<iframe src='" + src + "' allow='autoplay; encrypted-media' allowfullscreen></iframe>")
        sb.append("<div class='mask'></div>")
        sb.append("</body></html>")
        return sb.toString()
    }

    override fun onPause() {
        super.onPause()
        // On coupe le son et la lecture des qu on quitte l ecran.
        try { web?.onPause() } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try { web?.onResume() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { web?.loadUrl("about:blank") } catch (_: Exception) {}
        try { web?.destroy() } catch (_: Exception) {}
        web = null
        super.onDestroy()
    }
}
