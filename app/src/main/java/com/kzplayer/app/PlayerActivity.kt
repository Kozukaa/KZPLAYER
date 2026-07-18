package com.kzplayer.app

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var candidates: List<String> = emptyList()
    private var candIdx = 0
    private var watchUrl: String = ""
    private var watchTitle: String = ""
    private var watchLogo: String = ""
    private var watchKind: String = "live"
    private var watchSeriesName: String = ""
    private var watchSeriesLogo: String = ""
    private var watchSeriesId: String = ""
    private var watchSeriesCmd: String = ""
    private var watchSourceCmd: String = ""
    private var watchSourceStreamId: String = ""
    private var watchSourceContainerExt: String = ""
    private var didRestorePosition: Boolean = false
    private var isLiveMode: Boolean = false
    // Sous-titres externes (OpenSubtitles) charges par-dessus la video en cours.
    private var currentSubUrl: String? = null
    private var currentSubLang: String = ""
    private var currentSubFormat: String = "srt"

    // Barre du haut (titre + bouton X) : masquee automatiquement en direct.
    private var topBar: View? = null
    private val barHandler = Handler(Looper.getMainLooper())
    private val hideBarRunnable = Runnable { topBar?.visibility = View.GONE }
    // Recuperation audio VOD : si des pistes audio existent mais qu'aucune n'est selectionnee
    // (souvent une piste multicanal exclue par la limite stereo), on relache la limite une fois.
    private var audioRecoveryDone = false

    // Reconnexion automatique du direct (chaine qui se fige / coupe apres quelques minutes).
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var lastPos = -1L
    private var lastProgressTs = 0L
    private var liveRetries = 0
    private var workingCandIdx = 0
    private var lastReconnectTs = 0L
    private val stallWatchdog = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && isLiveMode && p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
                val now = SystemClock.elapsedRealtime()
                val pos = p.currentPosition
                if (pos != lastPos) {
                    lastPos = pos
                    lastProgressTs = now
                    liveRetries = 0
                } else {
                    val stalled = now - lastProgressTs
                    val buffering = p.playbackState == Player.STATE_BUFFERING
                    // Image figee / son coupe : la lecture n'avance plus depuis trop longtemps.
                    if (lastProgressTs > 0L && ((buffering && stalled > 12000L) || stalled > 20000L)) {
                        reconnectLive()
                    }
                }
            }
            recoveryHandler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Plein ecran paysage
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        hideSystemBars()

        playerView = findViewById(R.id.playerView)
        // Navigation telecommande : le controleur reste affiche un peu plus longtemps
        playerView.controllerShowTimeoutMs = 5000
        playerView.setControllerHideOnTouch(true)
        playerView.isFocusable = true
        playerView.isFocusableInTouchMode = true
        playerView.requestFocus()

        // La barre du haut (titre + retour) suit l'affichage des commandes :
        // elle disparait en meme temps que le "menu" lecture/avance.
        topBar = findViewById<View>(R.id.topBar)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> topBar?.visibility = visibility }
        )

        val title = intent.getStringExtra("title") ?: ""
        val url = intent.getStringExtra("url") ?: ""
        watchUrl = url
        watchTitle = title
        watchLogo = intent.getStringExtra("logo") ?: ""
        watchKind = intent.getStringExtra("historyKind") ?: "live"
        watchSeriesName = intent.getStringExtra("seriesName") ?: ""
        watchSeriesLogo = intent.getStringExtra("seriesLogo") ?: ""
        watchSeriesId = intent.getStringExtra("seriesId") ?: ""
        watchSeriesCmd = intent.getStringExtra("seriesCmd") ?: ""
        watchSourceCmd = intent.getStringExtra("historySourceCmd") ?: ""
        watchSourceStreamId = intent.getStringExtra("historySourceStreamId") ?: ""
        watchSourceContainerExt = intent.getStringExtra("historySourceContainerExt") ?: ""
        // Lecture a la suite : on ne conserve la file d'episodes que si on vient d'une liste d'episodes.
        if (!intent.getBooleanExtra("queued", false)) { Session.episodeQueue = emptyList(); Session.episodeIndex = -1 }
        // mode = "live" par defaut ; "vod" pour films/episodes.
        // Securite : on detecte aussi automatiquement les VOD par URL, au cas ou un ecran
        // n'envoie pas l'extra mode=vod (ex: series M3U /series/... ou fichiers mp4/mkv/avi).
        val mode = intent.getStringExtra("mode") ?: "live"
        val lowerUrl = url.lowercase()
        val pathOnly = lowerUrl.substringBefore('?')
        val looksVod = lowerUrl.contains("/series/") || lowerUrl.contains("/movie/") ||
            pathOnly.endsWith(".mp4") || pathOnly.endsWith(".mkv") || pathOnly.endsWith(".avi") ||
            pathOnly.endsWith(".mov") || pathOnly.endsWith(".flv")
        val isVod = mode == "vod" || looksVod
        isLiveMode = !isVod
        if (isLiveMode) {
            // Chaines live : pas de boutons play/pause. On garde juste la barre du haut (titre + retour).
            playerView.useController = false
            // Live : la barre du haut (titre + X) s'affiche puis disparait toute seule apres 4 s.
            showTopBarTemporarily()
        }
        if (watchKind == "movie" || watchKind == "series") {
            WatchHistory.touch(this, watchUrl, watchTitle, watchLogo, watchKind, watchSeriesName, watchSeriesLogo, watchSeriesId, watchSeriesCmd, watchSourceCmd, watchSourceStreamId, watchSourceContainerExt)
        }
        findViewById<TextView>(R.id.titleTv).text = title
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        // Roue dentee (parametres) du lecteur : pour les films/series, on remplace le menu
        // par notre propre menu (Audio + Sous-titres), afin que le bouton sous-titres soit
        // DANS la roue dentee, juste sous "Audio", comme demande.
        if (isVod) {
            findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
                showSettingsMenu()
            }
        }

        if (url.isBlank()) {
            Toast.makeText(this, "Flux introuvable", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        // Pour un portail Stalker/MAG, le flux n'est accessible qu'avec les memes en-tetes que le
        // boitier (User-Agent MAG, Cookie mac=..., Referer, token Bearer). Sinon nginx coupe -> HTTP 444.
        val plCur = Session.current
        if (plCur != null && plCur.type == "stalker") {
            // IMPORTANT (verifie sur le trafic d'un vrai boitier) : le lien renvoye par create_link
            // (ex: http://0connect.top:8080/.../1332) repond en 302 et redirige vers le vrai serveur
            // de streaming avec un token dans l'URL (ex: http://89.x:1935/...?token=...).
            // Pour le FLUX on n'envoie QUE le User-Agent MAG : renvoyer Cookie/Authorization/Referer
            // du portail fait repondre 404/444 (anti-bot) ou casse la redirection.
            val ua = Api.stalkerHeaders(plCur)["User-Agent"]
            if (!ua.isNullOrBlank()) httpFactory.setUserAgent(ua)
        } else {
            // Beaucoup de serveurs Xtream renvoient 401/403 a un User-Agent navigateur.
            // On se presente comme VLC, accepte par la quasi-totalite des panels IPTV.
            httpFactory.setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
        }
        val mediaSourceFactory = if (isVod) {
            // Films / episodes : lecteur VOD standard. Pas de flags TS live, sinon certains VOD
            // chargent la duree mais restent figes sans son.
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory)
        } else {
            // Live IPTV : beaucoup de flux sont du MPEG-TS brut sans IDR/AUD.
            val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                )
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory, extractors)
        }
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            // Garde ExoPlayer, mais active tous les decodeurs disponibles + extensions FFmpeg si presentes.
            // Aide sur AC3/EAC3/DTS et vieux codecs audio Stalker, sans passer sur VLC.
            .setEnableDecoderFallback(true)
            // CORRECTION SYNC SON/IMAGE (v86) :
            // - VOD/series : EXTENSION_RENDERER_MODE_ON => on prefere le decodeur AUDIO MATERIEL
            //   (AAC/H264 parfaitement synchronises). Avant, le mode PREFER forcait FFmpeg logiciel
            //   sur tout l'audio, ce qui faisait deriver le son par rapport a l'image sur les episodes.
            //   FFmpeg reste utilise EN SECOURS pour AC3/EAC3/DTS grace a setEnableDecoderFallback(true)
            //   (si le materiel n'a pas le codec ou le decode mal, ExoPlayer bascule sur FFmpeg).
            // - Live : on garde PREFER (FFmpeg prioritaire) pour ne rien casser cote Stalker/MAG.
            .setExtensionRendererMode(
                if (isVod)
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )

        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
        val loadControl = if (isVod) {
            // VOD/films/series : buffer equilibre.
            // v38 etait tres stable mais un peu lent au demarrage ; ici on garde assez de marge
            // pour l'audio Stalker/FFmpeg tout en reduisant la latence de lancement et de reprise.
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(4000, 30000, 700, 1500)
                .build()
        } else {
            // Live TV / zapping : buffer court pour demarrer vite et reduire la latence.
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 8000, 500, 1000)
                .build()
        }
        playerBuilder.setLoadControl(loadControl)
        val p = playerBuilder.build()
        // Correction audio VOD/series : on force un vrai flux MEDIA + volume max.
        // Certains boitiers Android TV lancent la video mais ne prennent pas le focus audio
        // correctement si les attributs ne sont pas explicites.
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(
                    if (isVod) androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE
                    else androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH
                )
                .build(),
            true
        )
        p.volume = 1.0f
        if (isVod) {
            // Optimisation audio VOD : si plusieurs pistes existent, ExoPlayer prefere une piste compatible
            // avec peu de canaux (stereo) plutot qu'une piste 5.1/DTS que le boitier ne sort pas.
            var vodParams = p.trackSelectionParameters.buildUpon()
                .setMaxAudioChannelCount(2)
            // Series multi-langues : on selectionne automatiquement le francais quand il existe.
            // C'est une PREFERENCE (pas une contrainte) : si le francais est absent, ExoPlayer garde
            // la piste par defaut, donc jamais de perte de son. Codes fr / fra / fre couverts.
            if (watchKind == "series") {
                vodParams = vodParams.setPreferredAudioLanguages("fr", "fra", "fre")
            }
            p.trackSelectionParameters = vodParams.build()
        }
        player = p
        playerView.player = p
        playerView.keepScreenOn = true

        // Certains serveurs IPTV ne servent pas le live en .ts (mais en .m3u8) ou sans extension :
        // on prepare une liste d'URL candidates et on bascule automatiquement si une renvoie une
        // erreur HTTP (ERROR_CODE_IO_BAD_HTTP_STATUS, etc.).
        // Pour un flux Stalker, l'URL de create_link redirige (302) vers le vrai serveur de flux :
        // il ne faut PAS lui coller des variantes .ts/.m3u8 (faux 404 qui masquent la vraie reponse).
        // On la joue telle quelle et on suit la redirection, exactement comme un vrai boitier.
        candidates = when {
            // VOD/episodes : on garde l'URL propre, mais si le portail renvoie du .avi non decodable
            // par Android, on tente automatiquement les containers alternatifs courants.
            isVod -> buildVodCandidates(url)
            plCur?.type == "stalker" -> listOf(url)
            else -> buildCandidates(url)
        }
        candIdx = 0
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Film/serie sans son : il y a des pistes audio mais aucune n'est selectionnee.
                // On relache la limite de canaux (une seule fois) pour qu'une piste soit choisie
                // -> retablit le son sans changer le comportement des fichiers qui ont deja du son.
                if (!isLiveMode && !audioRecoveryDone &&
                    tracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO) &&
                    !tracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_AUDIO)) {
                    audioRecoveryDone = true
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setMaxAudioChannelCount(Int.MAX_VALUE)
                        .build()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Certains VOD/episodes chargent bien la duree (STATE_READY) mais restent figes
                // sans demarrer automatiquement sur Android TV. On force le play au moment READY.
                if (playbackState == Player.STATE_READY && !didRestorePosition && (watchKind == "movie" || watchKind == "series")) {
                    didRestorePosition = true
                    val resume = WatchHistory.positionForTitle(this@PlayerActivity, watchTitle)
                    if (resume > 0L) p.seekTo(resume)
                }
                if (playbackState == Player.STATE_READY && p.playWhenReady && !p.isPlaying) {
                    p.play()
                }
                if (playbackState == Player.STATE_READY && isLiveMode) {
                    // Direct reparti : on memorise la variante qui marche et on remet le compteur
                    // de reconnexions a zero.
                    workingCandIdx = candIdx
                    liveRetries = 0
                    lastPos = -1L
                    lastProgressTs = SystemClock.elapsedRealtime()
                }
                if (playbackState == Player.STATE_ENDED) {
                    saveWatchProgress(forceCompleted = true)
                    playNextEpisodeIfAny()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (candIdx < candidates.size - 1) {
                    candIdx++
                    playCurrent()
                } else if (isLiveMode) {
                    // Coupure du direct (reseau coupe, token expire...) : on se rebranche
                    // automatiquement sans afficher d'erreur, comme un vrai boitier.
                    recoveryHandler.postDelayed({ reconnectLive() }, 1500)
                } else {
                    val c = error.cause
                    val detail = if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                        "HTTP ${c.responseCode}" else error.errorCodeName
                    val u = candidates.getOrNull(candIdx) ?: ""
                    // Fenetre lisible (au lieu d'un toast fugace) avec le diagnostic complet :
                    // l'utilisateur peut lire / photographier l'URL exacte et la reponse du serveur.
                    val diag = if (Session.current?.type == "stalker" && Api.lastStreamLog.isNotBlank())
                        "\n\n--- Diagnostic ---\n${Api.lastStreamLog}" else ""
                    androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Lecture impossible : $detail")
                        .setMessage("$u$diag")
                        .setPositiveButton("OK") { d, _ -> d.dismiss() }
                        .show()
                }
            }
        })
        playCurrent()
        lastProgressTs = SystemClock.elapsedRealtime()
        if (isLiveMode) recoveryHandler.postDelayed(stallWatchdog, 3000)
    }

    private fun playCurrent() {
        val p = player ?: return
        val u = candidates.getOrNull(candIdx) ?: return
        // On laisse ExoPlayer auto-detecter le format (extension + Content-Type + redirections).
        // Forcer le MIME pouvait casser un .ts qui redirige en realite vers du HLS.
        p.setMediaItem(buildMediaItem(u))
        p.playWhenReady = true
        p.prepare()
        p.play()
    }

    // Lecture a la suite : a la fin d'un episode, on enchaine automatiquement sur le suivant
    // de la file (Session.episodeQueue) sans repasser par la fiche serie. Marche sur les 2 themes.
    private fun playNextEpisodeIfAny() {
        if (watchKind != "series") return
        val q = Session.episodeQueue
        val nextIdx = Session.episodeIndex + 1
        if (q.isEmpty() || nextIdx < 0 || nextIdx >= q.size) return
        Session.episodeIndex = nextIdx
        val ep = q[nextIdx]
        val series = Session.seriesItem
        val epTitle = if (series != null) "${series.name} - ${ep.name}" else ep.name
        val direct = ep.directUrl
        if (!direct.isNullOrBlank()) { switchToVod(direct, epTitle, ep.logo); return }
        val cmd = ep.cmd
        val pl = Session.current
        if (cmd.isNullOrBlank() || pl == null) return
        lifecycleScope.launch {
            val link = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
            if (!link.isNullOrBlank()) switchToVod(link, epTitle, ep.logo)
        }
    }

    // Bascule le lecteur en cours sur un nouveau flux VOD (episode suivant) sans recreer l'activite.
    private fun switchToVod(url: String, title: String, logo: String) {
        val p = player ?: return
        watchUrl = url
        watchTitle = title
        if (logo.isNotBlank()) watchLogo = logo
        findViewById<TextView>(R.id.titleTv).text = title
        WatchHistory.touch(this, watchUrl, watchTitle, watchLogo, "series", watchSeriesName, watchSeriesLogo, watchSeriesId, watchSeriesCmd, watchSourceCmd, watchSourceStreamId, watchSourceContainerExt)
        // Nouvel episode : on ne reprend pas une position sauvegardee, et on reactive la recup audio.
        didRestorePosition = true
        audioRecoveryDone = false
        currentSubUrl = null
        candidates = buildVodCandidates(url)
        candIdx = 0
        playCurrent()
    }

    // ---- Sous-titres externes multilangues (OpenSubtitles) ----
    // Construit le MediaItem en y greffant, si demande, une piste de sous-titres externe.
    private fun buildMediaItem(u: String): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(u))
        val sub = currentSubUrl
        if (!sub.isNullOrBlank()) {
            val mime = when (currentSubFormat.lowercase()) {
                "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
                "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
                else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
            }
            val subCfg = MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                .setMimeType(mime)
                .setLanguage(currentSubLang.ifBlank { "und" })
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .build()
            b.setSubtitleConfigurations(listOf(subCfg))
        }
        return b.build()
    }

    // Menu de la roue dentee : Audio puis Sous-titres (comme demande).
    private fun showSettingsMenu() {
        val items = arrayOf("Audio", "Sous-titres")
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_KZ_Dialog)
            .setTitle("Param\u00e8tres")
            .setItems(items) { d, which ->
                d.dismiss()
                if (which == 0) showAudioMenu() else showSubtitleMenu()
            }
            .show()
    }

    // Choix de la piste audio presente dans le flux.
    private fun showAudioMenu() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) {
            Toast.makeText(this, "Aucune piste audio.", Toast.LENGTH_SHORT).show(); return
        }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        groups.forEachIndexed { gi, g ->
            for (ti in 0 until g.length) {
                val fmt = g.getTrackFormat(ti)
                val base = langName(fmt.language ?: "").ifBlank { "Piste ${gi + 1}" }
                val ch = if (fmt.channelCount > 0) " (${fmt.channelCount}ch)" else ""
                labels.add((if (g.isTrackSelected(ti)) "\u25cf " else "") + base + ch)
                actions.add { selectTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, g, ti) }
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_KZ_Dialog)
            .setTitle("Audio")
            .setItems(labels.toTypedArray()) { d, which -> actions[which](); d.dismiss() }
            .show()
    }

    // Menu Sous-titres : d'abord les pistes integrees au flux (comme TiViMate),
    // puis la recherche en ligne (OpenSubtitles par IP) pour tout le reste.
    private fun showSubtitleMenu() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("D\u00e9sactiver"); actions.add { disableSubtitles() }
        groups.forEach { g ->
            for (ti in 0 until g.length) {
                val fmt = g.getTrackFormat(ti)
                val name = fmt.label ?: langName(fmt.language ?: "").ifBlank { "Piste" }
                labels.add((if (g.isTrackSelected(ti)) "\u25cf " else "") + name + "  (flux)")
                actions.add { selectTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, g, ti) }
            }
        }
        labels.add("Chercher en ligne\u2026"); actions.add { searchSubtitlesOnline() }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_KZ_Dialog)
            .setTitle("Sous-titres")
            .setItems(labels.toTypedArray()) { d, which -> actions[which](); d.dismiss() }
            .show()
    }

    // Selectionne une piste precise (audio ou texte) presente dans le flux.
    private fun selectTrack(type: Int, g: androidx.media3.common.Tracks.Group, trackIndex: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, trackIndex))
            .build()
        if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
            currentSubUrl = null
            Toast.makeText(this, "Sous-titres du flux activ\u00e9s", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchSubtitlesOnline() {
        val query = watchSeriesName.ifBlank { watchTitle }
        if (query.isBlank()) {
            Toast.makeText(this, "Titre inconnu pour la recherche.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Recherche de sous-titres\u2026", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val results = SubtitlesApi.search(query, SubtitlesConfig.SUBLANGUAGE_IDS)
            if (results.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Aucun sous-titre trouv\u00e9 pour ce titre.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = results.map { "${langName(it.lang)}  \u2014  ${it.release.take(45)}" }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity, R.style.Theme_KZ_Dialog)
                .setTitle("Choisir la langue")
                .setItems(labels) { d, which -> applyOnlineSubtitle(results[which]); d.dismiss() }
                .show()
        }
    }

    private fun applyOnlineSubtitle(opt: SubtitlesApi.SubOption) {
        Toast.makeText(this, "T\u00e9l\u00e9chargement des sous-titres\u2026", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val local = SubtitlesApi.downloadToFile(this@PlayerActivity, opt)
            if (local.isNullOrBlank()) {
                Toast.makeText(this@PlayerActivity, "\u00c9chec du t\u00e9l\u00e9chargement des sous-titres.", Toast.LENGTH_LONG).show()
                return@launch
            }
            currentSubUrl = local
            currentSubLang = opt.lang
            currentSubFormat = opt.format
            reloadWithSubtitle()
        }
    }

    private fun reloadWithSubtitle() {
        val p = player ?: return
        val u = candidates.getOrNull(candIdx) ?: watchUrl
        val pos = p.currentPosition
        p.setMediaItem(buildMediaItem(u))
        p.prepare()
        if (pos > 0) p.seekTo(pos)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(currentSubLang.ifBlank { "und" })
            .build()
        p.playWhenReady = true
        p.play()
        Toast.makeText(this, "Sous-titres activ\u00e9s (${langName(currentSubLang)})", Toast.LENGTH_SHORT).show()
    }

    private fun disableSubtitles() {
        currentSubUrl = null
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(null)
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            .build()
        Toast.makeText(this, "Sous-titres d\u00e9sactiv\u00e9s", Toast.LENGTH_SHORT).show()
    }

    private fun langName(code: String): String = when (code.lowercase().take(2)) {
        "fr" -> "Fran\u00e7ais"
        "en" -> "Anglais"
        "es" -> "Espagnol"
        "ar" -> "Arabe"
        "pt" -> "Portugais"
        "de" -> "Allemand"
        "it" -> "Italien"
        "nl" -> "N\u00e9erlandais"
        "ru" -> "Russe"
        "tr" -> "Turc"
        else -> code
    }

    // Rebranche le direct : on rejoue l'URL (pour un portail Stalker, re-hit du lien create_link
    // => nouvelle redirection 302 avec un token frais). N'affecte que le live (isLiveMode).
    private fun reconnectLive() {
        val p = player ?: return
        if (!isLiveMode) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectTs < 5000L) return
        lastReconnectTs = now
        liveRetries++
        if (liveRetries > 12) return
        candIdx = workingCandIdx.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        lastPos = -1L
        lastProgressTs = now
        playCurrent()
    }

    // Variantes VOD/episodes : certains portails Stalker renvoient un container non decodeable
    // (.mkv/.avi) dans le chemin OU dans le parametre stream=1207225.mkv.
    // On tente donc les containers alternatifs sans toucher au token.
    private fun buildVodCandidates(url: String): List<String> {
        val list = LinkedHashSet<String>()
        list.add(url)

        fun replaceExt(from: String, to: String) {
            val re = Regex("(?i)\\.$from(?=(&|\\?|$))")
            val replaced = url.replace(re, ".$to")
            if (replaced != url) list.add(replaced)
        }

        val lower = url.lowercase()
        when {
            lower.contains(".avi&") || lower.contains(".avi?") || lower.endsWith(".avi") -> {
                replaceExt("avi", "mp4"); replaceExt("avi", "mkv"); replaceExt("avi", "ts")
            }
            lower.contains(".mkv&") || lower.contains(".mkv?") || lower.endsWith(".mkv") -> {
                replaceExt("mkv", "mp4"); replaceExt("mkv", "avi"); replaceExt("mkv", "ts")
            }
            lower.contains(".mp4&") || lower.contains(".mp4?") || lower.endsWith(".mp4") -> {
                replaceExt("mp4", "mkv"); replaceExt("mp4", "avi"); replaceExt("mp4", "ts")
            }
        }
        return list.toList()
    }

    // Construit les variantes d'URL a essayer pour un meme flux (live surtout).
    private fun buildCandidates(url: String): List<String> {
        val list = LinkedHashSet<String>()
        fun addVariants(u: String) {
            list.add(u)
            val q = u.indexOf('?')
            val path = if (q >= 0) u.substring(0, q) else u
            val query = if (q >= 0) u.substring(q) else ""
            when {
                path.endsWith(".ts") -> {
                    list.add(path.removeSuffix(".ts") + ".m3u8" + query)
                    list.add(path.removeSuffix(".ts") + query)
                }
                path.endsWith(".m3u8") -> {
                    list.add(path.removeSuffix(".m3u8") + ".ts" + query)
                    list.add(path.removeSuffix(".m3u8") + query)
                }
                else -> {
                    // URL sans extension (ex: .../user/pass/12345) -> on tente .ts puis .m3u8
                    val lastSeg = path.substringAfterLast('/')
                    if (!lastSeg.contains('.')) {
                        list.add(path + ".ts" + query)
                        list.add(path + ".m3u8" + query)
                    }
                }
            }
        }
        addVariants(url)
        // Format "legacy" Xtream pour le live : http://host/USER/PASS/ID(.ext) sans le segment /live/
        if (url.contains("/live/")) addVariants(url.replace("/live/", "/"))
        return list.toList()
    }

    // Pilotage a la telecommande (boitier / TV Android, sans ecran tactile)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val controllerVisible = playerView.isControllerFullyVisible
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isLiveMode) {
                // En direct, n'importe quelle touche reaffiche brievement la barre du haut.
                showTopBarTemporarily()
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> return super.dispatchKeyEvent(event)
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> return true
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    // 1er retour : si le menu est affiche, on le masque (sans quitter)
                    if (controllerVisible) { playerView.hideController(); return true }
                    // 2e retour : menu deja cache -> on quitte la chaine (comportement par defaut)
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30000); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10000); playerView.showController(); return true }
                KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    showSubtitleMenu(); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Si le controleur n'est pas affiche : OK = lecture/pause + affiche les boutons
                    if (!controllerVisible) { togglePlay(); playerView.showController(); return true }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!controllerVisible) { seekBy(-10000); playerView.showController(); return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controllerVisible) { seekBy(30000); playerView.showController(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Affiche la barre du haut puis la masque apres 4 s (utilise en direct).
    private fun showTopBarTemporarily() {
        val bar = topBar ?: return
        bar.visibility = View.VISIBLE
        barHandler.removeCallbacks(hideBarRunnable)
        barHandler.postDelayed(hideBarRunnable, 4000)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val target = (p.currentPosition + ms).coerceAtLeast(0)
        val dur = p.duration
        p.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onStop() {
        saveWatchProgress()
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        recoveryHandler.removeCallbacksAndMessages(null)
        barHandler.removeCallbacksAndMessages(null)
        saveWatchProgress()
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun saveWatchProgress(forceCompleted: Boolean = false) {
        if (watchKind != "movie" && watchKind != "series") return
        val p = player ?: return
        val dur = p.duration
        val pos = if (forceCompleted && dur > 0L) dur else p.currentPosition
        if (pos < 3000L || dur <= 0L) return
        WatchHistory.save(this, watchUrl, watchTitle, watchLogo, watchKind, pos, dur, watchSeriesName, watchSeriesLogo, watchSeriesId, watchSeriesCmd, watchSourceCmd, watchSourceStreamId, watchSourceContainerExt)
    }
}
