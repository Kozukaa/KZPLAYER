package com.kzplayer.app

import android.content.Context

// Choix du theme d'interface, persiste sur l'appareil.
// classic = interface actuelle (intacte) / newtivi = interface guide TV /
// netflix = accueil facon Netflix (banniere + rangees).
object ThemePref {
    private const val PREF = "kz_prefs_theme"
    private const val KEY = "theme"
    const val CLASSIC = "classic"
    const val NEWTIVI = "newtivi"
    const val NETFLIX = "netflix"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun get(ctx: Context): String = prefs(ctx).getString(KEY, CLASSIC) ?: CLASSIC
    fun set(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY, v).apply() }
    fun isNew(ctx: Context): Boolean = get(ctx) == NEWTIVI
    fun isNetflix(ctx: Context): Boolean = get(ctx) == NETFLIX

    // Ecran d'accueil correspondant au theme choisi (routage centralise).
    // N'affecte que la navigation ; lecteur / flux / licence inchanges.
    fun homeClass(ctx: Context): Class<*> = when (get(ctx)) {
        NETFLIX -> NetflixHomeActivity::class.java
        NEWTIVI -> NewLiveActivity::class.java
        else -> HomeActivity::class.java
    }
}
