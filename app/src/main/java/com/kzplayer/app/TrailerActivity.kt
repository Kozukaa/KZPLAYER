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
    // v350 : une fois la lecture lancee, on ne change plus de video.
    private var everPlayed = false
    private var loading = false
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
            override fun onPageCommitVisible(v: WebView?, url: String?) {
                if (fallbackUsed) injectSkin()
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                if (fallbackUsed) injectSkin()
            }
        }
        playCurrent()
    }

    private fun playCurrent() {
        started = false
        loading = false
        val w = web ?: return
        w.loadDataWithBaseURL(BASE, html(ids[index]), "text/html", "utf-8", null)
        arm(14000L)
    }

    /** Si rien ne demarre dans le delai imparti, on considere la video refusee. */
    private fun arm(delay: Long) {
        watchdog?.let { ui.removeCallbacks(it) }
        val r = Runnable { if (!started) next() }
        watchdog = r
        ui.postDelayed(r, delay)
    }

    private fun next() {
        // v350 : securite. Si la video est deja lancee, on ne la coupe pas :
        // c est ce qui faisait  quitter la bande-annonce pour en mettre une autre .
        if (everPlayed) return
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

    /**
     * Masque tout l habillage YouTube. La page YouTube se construit progressivement :
     * un seul passage ne suffisait pas, le titre et la barre du bas revenaient ensuite.
     * On repete donc l operation, et au lieu de viser des noms d elements precis
     * (que YouTube change souvent), on cache tout ce qui n est pas la video elle-meme.
     */
    private fun injectSkin() {
        val js = StringBuilder()
        js.append("(function(){if(window.kzSolo)return;window.kzSolo=1;")
        js.append("var st=document.createElement('style');")
        js.append("st.innerHTML=" + BT)
        js.append("html,body{background:#000!important;overflow:hidden!important;margin:0!important}")
        js.append("video{width:100vw!important;height:100vh!important;object-fit:contain!important;")
        js.append("background:#000!important;position:fixed!important;top:0!important;left:0!important}")
        js.append(BT + ";document.documentElement.appendChild(st);")
        // On remonte de la video jusqu au corps de la page en masquant tous les voisins :
        // barre du haut, titre, controles, commentaires et suggestions disparaissent.
        js.append("function solo(){var v=document.querySelector('video');if(!v)return;")
        js.append("var n=v;while(n&&n!==document.body){var p=n.parentElement;if(!p)break;")
        js.append("for(var i=0;i<p.children.length;i++){var c=p.children[i];")
        js.append("if(c!==n){c.style.setProperty('display','none','important');}}")
        js.append("p.style.setProperty('margin','0','important');")
        js.append("p.style.setProperty('padding','0','important');n=p;}")
        // v351 : coupe toutes les pistes de sous-titres, y compris celles ajoutees en cours de route.
        js.append("try{var tt=v.textTracks;for(var j=0;j<tt.length;j++){tt[j].mode='disabled';}}catch(x){}")
        js.append("if(v.paused){v.muted=true;v.play();}")
        js.append("if(!v.dataset.kz){v.dataset.kz=1;")
        js.append("v.onplaying=function(){v.muted=false;KZ.onPlaying();};")
        js.append("v.onwaiting=function(){KZ.onLoading();};")
        js.append("v.onended=function(){KZ.onEnded();};}}")
        // Repete pendant 30 secondes : suffisant pour couvrir tout le chargement.
        js.append("solo();var k=0;var t=setInterval(function(){solo();k++;")
        js.append("if(k>60){clearInterval(t);}},500);})()")
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
        // v351 : cc_load_policy=0 et cc_lang_pref vide -> aucun sous-titre automatique.
        sb.append("iv_load_policy:3,playsinline:1,cc_load_policy:0,cc_lang_pref:'',")
        sb.append("hl:'fr',origin:'https://kzplayer.app'},")
        sb.append("events:{onReady:function(e){try{e.target.unloadModule('captions');}catch(x){}")
        sb.append("try{e.target.unloadModule('cc');}catch(x){}")
        sb.append("try{e.target.mute();e.target.playVideo();}catch(x){}},")
        sb.append("onStateChange:function(e){if(e.data==1){try{pl.unMute();}catch(x){}")
        sb.append("try{pl.unloadModule('captions');pl.unloadModule('cc');}catch(x){}")
        sb.append("KZ.onPlaying();}")
        sb.append("if(e.data==3){KZ.onLoading();}")
        sb.append("if(e.data==0){KZ.onEnded();}},")
        sb.append("onError:function(e){KZ.onError(''+e.data);}}});}")
        sb.append("setTimeout(function(){if(!window.YT){KZ.onError('api');}},7000);")
        sb.append("</script></body></html>")
        return sb.toString()
    }

    /** Pont entre le lecteur web et l application. */
    inner class Bridge {
        @JavascriptInterface
        fun onPlaying() {
            ui.post {
                started = true
                everPlayed = true
                watchdog?.let { ui.removeCallbacks(it) }
                msg?.visibility = View.GONE
            }
        }

        /** La video est en train de se charger : on laisse plus de temps. */
        @JavascriptInterface
        fun onLoading() {
            ui.post {
                if (everPlayed) return@post
                if (!loading) { loading = true; arm(20000L) }
            }
        }

        @JavascriptInterface
        fun onError(code: String) { ui.post { if (!everPlayed) next() } }

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
