package com.kzplayer.app

import android.content.Context

// v146 : preference "Mode de rendu video" pour PlayerActivity.
//
// Pourquoi : sur beaucoup d'Android TV / boitiers, le SurfaceView par defaut de
// PlayerView reste bloque sur la premiere frame (son OK, image figee). C'est un
// bug de composition GPU cote box : le decodeur materiel decode bien, mais le
// SurfaceView n'est jamais recompose. Passer en TextureView resout ce probleme
// sur tous les boitiers observes, au prix d'un tres leger surcout CPU/GPU
// (non perceptible) et de la perte du DRM materiel securise (non utilise ici).
//
// La valeur par defaut est "texture" pour reparer les boitiers casses. Un
// utilisateur peut basculer sur "surface" depuis Parametres si son boitier
// prefere l'ancien rendu (rare, mais possible).
object SurfacePref {
    private const val PREFS = "kz_player"
    private const val KEY = "video_surface_type"
    const val TEXTURE = "texture"
    const val SURFACE = "surface"

    fun current(ctx: Context): String {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, TEXTURE).orEmpty()
        return if (v == SURFACE) SURFACE else TEXTURE
    }

    fun set(ctx: Context, value: String) {
        val v = if (value == SURFACE) SURFACE else TEXTURE
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()
    }

    fun label(value: String): String = when (value) {
        SURFACE -> "Surface (par d\u00e9faut Android)"
        else -> "Texture (compat. maximale)"
    }

    fun layoutFor(ctx: Context): Int = when (current(ctx)) {
        SURFACE -> R.layout.activity_player
        else -> R.layout.activity_player_texture
    }
}
