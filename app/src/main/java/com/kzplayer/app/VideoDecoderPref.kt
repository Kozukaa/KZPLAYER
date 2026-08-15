package com.kzplayer.app

import android.content.Context

// v147 : preference du decodeur video.
//
// Constat : sur beaucoup d'Android TV / boitiers IPTV, le decodeur video
// MATERIEL (MediaCodec vendor) decode la premiere frame puis se bloque
// silencieusement. Le son continue mais l'image reste figee. Ce n'est pas un
// probleme de surface (deja teste), c'est le decodeur video du firmware.
//
// Solution : donner la priorite au decodeur video LOGICIEL (c2.android.avc.decoder
// / c2.android.hevc.decoder, presents sur tous les Android). Il decode
// parfaitement les flux IPTV live/VOD jusqu'a 1080p sans se figer. Un utilisateur
// avec une box costaud peut basculer sur "Materiel" pour retrouver l'accel HW.
object VideoDecoderPref {
    private const val PREFS = "kz_player"
    private const val KEY = "video_decoder_mode"
    const val AUTO = "auto"           // Logiciel prioritaire, materiel en secours
    const val SOFTWARE = "software"   // Force le decodeur logiciel
    const val HARDWARE = "hardware"   // Force le decodeur materiel (accel HW)

    fun current(ctx: Context): String {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO).orEmpty()
        return when (v) { SOFTWARE, HARDWARE -> v; else -> AUTO }
    }

    fun set(ctx: Context, value: String) {
        val v = when (value) { SOFTWARE, HARDWARE -> value; else -> AUTO }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()
    }

    fun label(value: String): String = when (value) {
        SOFTWARE -> "Logiciel (compatibilit\u00e9 maximale)"
        HARDWARE -> "Mat\u00e9riel (acc\u00e9l\u00e9ration GPU)"
        else -> "Auto (logiciel prioritaire)"
    }

    fun description(value: String): String = when (value) {
        SOFTWARE -> "Force le d\u00e9codeur logiciel. Idem si l'image reste fig\u00e9e."
        HARDWARE -> "Force le d\u00e9codeur mat\u00e9riel. Rapide mais peut figer sur certaines box."
        else -> "Logiciel prioritaire, mat\u00e9riel en secours. Recommand\u00e9."
    }
}
