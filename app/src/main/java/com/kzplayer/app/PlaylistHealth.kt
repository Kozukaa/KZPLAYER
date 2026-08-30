package com.kzplayer.app

import android.content.Context

// v391 : etat de sante des listes de lecture (active / expiree / ne repond plus).
// Retenu sur l appareil pour affichage immediat, et signale au panel utilisateur.
object PlaylistHealth {
    const val OK = "ok"
    const val EXPIRED = "expired"
    const val DOWN = "down"
    private const val PREFS = "kz_pl_health"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun set(ctx: Context, id: String, status: String, message: String) {
        try {
            prefs(ctx).edit()
                .putString("s_" + id, status)
                .putString("m_" + id, message)
                .putLong("t_" + id, System.currentTimeMillis())
                .apply()
        } catch (e: Throwable) {}
    }

    fun status(ctx: Context, id: String): String =
        try { prefs(ctx).getString("s_" + id, "").orEmpty() } catch (e: Throwable) { "" }

    fun message(ctx: Context, id: String): String =
        try { prefs(ctx).getString("m_" + id, "").orEmpty() } catch (e: Throwable) { "" }

    fun isProblem(ctx: Context, id: String): Boolean {
        val s = status(ctx, id)
        return s == EXPIRED || s == DOWN
    }

    // Texte court affiche sous le nom de la liste.
    fun label(ctx: Context, id: String): String {
        val msg = message(ctx, id)
        val suffix = if (msg.isBlank()) "" else "  \u2022  " + msg
        return when (status(ctx, id)) {
            OK -> "Liste active" + suffix
            EXPIRED -> "Liste expir\u00e9e" + suffix
            DOWN -> "Ne r\u00e9pond plus" + suffix
            else -> ""
        }
    }
}
