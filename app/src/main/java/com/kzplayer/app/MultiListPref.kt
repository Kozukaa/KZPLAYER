package com.kzplayer.app

import android.content.Context

// v359 : choix du mode d affichage des listes de lecture.
// - false (defaut) : comportement historique, une seule liste active a la fois.
// - true : toutes les listes actives en meme temps, les categories de chaque liste
//   sont fusionnees dans le menu de gauche (facon TiviMate).
object MultiListPref {
    private const val PREF = "kz_multilist"
    private const val KEY = "all"

    fun isAll(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setAll(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    fun label(ctx: Context): String =
        if (isAll(ctx)) "Toutes les listes en même temps" else "Une seule liste (liste active)"
}
