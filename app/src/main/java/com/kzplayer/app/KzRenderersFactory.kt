package com.kzplayer.app

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

// v147 : usine de renderers ExoPlayer specifique KZ Player.
//
// But : reparer les box TV ou l'image reste figee sur la premiere frame
// (son OK) en donnant la priorite au decodeur video LOGICIEL. Le decodeur
// logiciel systeme (c2.android.avc.decoder / c2.android.hevc.decoder) est
// present sur tous les Android depuis Lollipop et decode les flux IPTV
// live/VOD sans se figer, contrairement aux decodeurs materiels vendor
// qui plantent silencieusement sur certains firmwares.
//
// Le mode est controle par VideoDecoderPref (AUTO / SOFTWARE / HARDWARE) :
// - AUTO       : logiciel prioritaire, materiel en secours (defaut).
// - SOFTWARE   : force le logiciel (peut demander un peu plus de CPU).
// - HARDWARE   : force le materiel (rapide, mais peut figer).
//
// Notes :
// - N'affecte QUE la video. L'audio reste inchange (FFmpeg extension pour
//   AC3/EAC3/DTS toujours preferee sur le live via EXTENSION_RENDERER_MODE_PREFER).
// - Ne casse PAS ExoPlayer : on garde tout le pipeline standard
//   (extracteurs TS Stalker, MediaCodec renderer, DefaultTrackSelector, etc.),
//   on change seulement l'ORDRE de selection des decodeurs video.
// - setEnableDecoderFallback(true) est conserve : si le decodeur choisi
//   echoue a l'init, ExoPlayer bascule automatiquement sur le suivant.
class KzRenderersFactory(private val ctx: Context) : DefaultRenderersFactory(ctx) {

    init {
        // Repli automatique sur un autre decodeur en cas d'echec d'init.
        setEnableDecoderFallback(true)

        // Selecteur de decodeur video base sur VideoDecoderPref.
        // Pour l'audio, on renvoie la liste par defaut sans reordonner (pour ne
        // pas casser la preference FFmpeg AC3/EAC3/DTS cote live/VOD).
        setMediaCodecSelector(object : MediaCodecSelector {
            @Throws(MediaCodecUtil.DecoderQueryException::class)
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean
            ): List<MediaCodecInfo> {
                val all = try {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                } catch (_: Throwable) { emptyList() }

                // Audio ou aucun decodeur : on garde l'ordre par defaut.
                if (!mimeType.startsWith("video/") || all.isEmpty()) return all

                val sw = all.filter { it.softwareOnly }
                val hw = all.filter { !it.softwareOnly }

                return when (VideoDecoderPref.current(ctx)) {
                    VideoDecoderPref.HARDWARE -> {
                        // Materiel d'abord, logiciel en secours si le HW echoue.
                        if (hw.isNotEmpty()) hw + sw else all
                    }
                    VideoDecoderPref.SOFTWARE -> {
                        // On force le logiciel : si aucun logiciel dispo (rare),
                        // on rebascule sur la liste complete pour ne pas casser la lecture.
                        if (sw.isNotEmpty()) sw else all
                    }
                    else -> {
                        // AUTO : logiciel prioritaire, materiel en secours.
                        // C'est ce qui repare les box dont le decodeur HW se fige.
                        if (sw.isNotEmpty()) sw + hw else all
                    }
                }
            }
        })
    }
}
