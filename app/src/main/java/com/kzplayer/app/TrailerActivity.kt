package com.kzplayer.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView

/**
 * v349 : lecture de la bande-annonce en plein ecran, presentee comme le lecteur KZ.
 *
 * YouTube bloque l extraction du flux (UNPLAYABLE) et certaines bandes-annonces
 * refusent la lecture hors de leur site (erreur 152). On essaie donc plusieurs
 * videos a la suite : des qu une video est refusee ou ne demarre pas, on passe
 * automatiquement a la suivante. En dernier recours, la page YouTube mobile est
 * chargee puis entierement deshabillee par du style injecte.
 *
 * Dans tous les cas : fond noir, aucun logo, aucune barre, aucun bouton externe,
 * et une couche invisible empeche tout clic de sortir de l application.
 */
class TrailerActivity : BaseActivity() {

    private var web: WebView? = null
    private val ids: MutableList<String> = ArrayList()
    private var index = 0
    private var started = false
    private var fallbackUsed = false
    private val ui = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null
    private var msg: TextView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trailer)

        findViewById<TextView>(R.id.trailerTitle).text = intent.getStringExtra("title") ?: ""
        msg = findViewById(R.id.trailerMsg)
        val back = findViewById<View>(R.id.trailerBack)
        back.setOnClickListener { finish() }
        back.requestFocus()

        intent.getStringArrayListExtra("videoIds")?.let { ids.addAll(it) }
        val single = intent.getStringExtra("videoId") ?: ""
        if (ids.isEmpty() && single.isNotBlank()) ids.add(single)
        if (ids.isEmpty()) { finish(); return }

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
        w.addJavascriptInterface(Bridge(), "KZ")
        w.webViewClient = object : WebViewClient() {
            // Aucune navigation : impossible de partir vers YouTube.
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean = true
            override fun onPageFinished(v: WebView?, url: String?) {
                if (fallbackUsed) injectSkin()
            }
        }
        playCurrent()
    }

    private fun playCurrent() {
        started = false
        val w = web ?: return
        w.loadDataWithBaseURL(BASE, html(ids[index]), "text/html", "utf-8", null)
        arm(9000L)
    }

    /** Si rien ne demarre dans le delai imparti, on considere la video refusee. */
    private fun arm(delay: Long) {
        watchdog?.let { ui.removeCallbacks(it) }
        val r = Runnable { if (!started) next() }
        watchdog = r
        ui.postDelayed(r, delay)
    }

    private fun next() {
        watchdog?.let { ui.removeCallbacks(it) }
        if (fallbackUsed) { showError(); return }
        index += 1
        if (index < ids.size) playCurrent() else fallback()
    }

    /** Dernier recours : la vraie page YouTube mobile, entierement deshabillee. */
    private fun fallback() {
        val w = web ?: return
        fallbackUsed = true
        started = false
        w.settings.userAgentString = UA_MOBILE
        w.loadUrl("https://m.youtube.com/watch?v=" + ids[0])
        arm(15000L)
    }

    private fun showError() {
        msg?.visibility = View.VISIBLE
        msg?.text = "Bande-annonce indisponible pour ce titre."
    }

    /** Masque tout l habillage YouTube et met la video en plein ecran. */
    private fun injectSkin() {
        val css = StringBuilder()
        css.append("header,ytm-mobile-topbar-renderer,.mobile-topbar-header,ytm-pivot-bar-renderer,")
        css.append("ytm-item-section-renderer,ytm-single-column-watch-next-results-renderer,")
        css.append("ytm-slim-video-metadata-section-renderer,ytm-companion-slot,#comments,")
        css.append(".player-controls-background,.ytp-chrome-top,.ytp-chrome-bottom,.ytp-watermark,")
        css.append(".ytp-show-cards-title,.ytp-pause-overlay,.ytp-gradient-top,.ytp-gradient-bottom")
        css.append("{display:none!important}")
        css.append("html,body{background:#000!important;overflow:hidden!important;margin:0!important}")
        css.append("#player,.player-container,ytm-app{position:fixed!important;top:0!important;")
        css.append("left:0!important;width:100vw!important;height:100vh!important;margin:0!important}")
        css.append("video{width:100vw!important;height:100vh!important;object-fit:contain!important;")
        css.append("background:#000!important}")
        val js = StringBuilder()
        js.append("(function(){var s=document.createElement('style');s.innerHTML=" + BT + css.toString() + BT + ";")
        js.append("document.head.appendChild(s);")
        js.append("var v=document.querySelector('video');")
        js.append("if(v){v.muted=true;v.play();v.onplaying=function(){v.muted=false;KZ.onPlaying();};}")
        js.append("})()")
        try { web?.evaluateJavascript(js.toString(), null) } catch (_: Exception) {}
    }

    /** Page minimale : la video seule, aucun element d interface. */
    private fun html(vid: String): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>")
        sb.append("<style>html,body{margin:0;padding:0;background:#000;overflow:hidden;height:100%}")
        sb.append("#p,iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0}")
        sb.append(".mask{position:absolute;top:0;left:0;width:100%;height:100%;background:transparent}")
        sb.append("</style></head><body><div id='p'></div><div class='mask'></div>")
        sb.append("<script src='https://www.youtube.com/iframe_api'></script><script>")
        sb.append("var pl;function onYouTubeIframeAPIReady(){pl=new YT.Player('p',{videoId:'" + vid + "',")
        sb.append("playerVars:{autoplay:1,controls:0,modestbranding:1,rel:0,fs:0,disablekb:1,")
        sb.append("iv_load_policy:3,playsinline:1,origin:'https://kzplayer.app'},")
        sb.append("events:{onReady:function(e){try{e.target.mute();e.target.playVideo();}catch(x){}},")
        sb.append("onStateChange:function(e){if(e.data==1){try{pl.unMute();}catch(x){}KZ.onPlaying();}")
        sb.append("if(e.data==0){KZ.onEnded();}},")
        sb.append("onError:function(e){KZ.onError(''+e.data);}}});}")
        sb.append("setTimeout(function(){if(!window.YT){KZ.onError('api');}},7000);")
        sb.append("</script></body></html>")
        return sb.toString()
    }

    /** Pont entre le lecteur web et l application. */
    inner class Bridge {
        @JavascriptInterface
        fun onPlaying() { ui.post { started = true; msg?.visibility = View.GONE } }

        @JavascriptInterface
        fun onError(code: String) { ui.post { next() } }

        @JavascriptInterface
        fun onEnded() { ui.post { finish() } }
    }

    override fun onPause() {
        super.onPause()
        try { web?.onPause() } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try { web?.onResume() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        watchdog?.let { ui.removeCallbacks(it) }
        try { web?.loadUrl("about:blank") } catch (_: Exception) {}
        try { web?.destroy() } catch (_: Exception) {}
        web = null
        super.onDestroy()
    }

    companion object {
        private const val BASE = "https://kzplayer.app"
        private const val BT = "`"
        private const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }
}
