package com.kzplayer.app

import android.content.Context

// Choix du theme d'interface, persiste sur l'appareil.
// classic = interface actuelle (intacte) / newtivi = nouvelle interface.
object ThemePref {
    private const val PREF = "kz_prefs_theme"
    private const val KEY = "theme"
    const val CLASSIC = "classic"
    const val NEWTIVI = "newtivi"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun get(ctx: Context): String = prefs(ctx).getString(KEY, CLASSIC) ?: CLASSIC
    fun set(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY, v).apply() }
    fun isNew(ctx: Context): Boolean = get(ctx) == NEWTIVI
}
