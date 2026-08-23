package com.kzplayer.app

import android.content.Context

// v376 : enregistreur d erreur.
// Quand l application se ferme d un coup ("paf") on ne sait pas pourquoi : l ecran
// revient a la liste des chaines et l erreur est perdue. Ici on capture l erreur
// juste avant la fermeture et on la garde sur l appareil. Au prochain lancement,
// la liste des chaines l affiche dans une fenetre : plus besoin d un cable PC.
object CrashLog {
    private const val PREFS = "kz_crash"
    private const val KEY = "last"

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val txt = buildString {
                    append(
                        java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.FRANCE)
                            .format(java.util.Date())
                    )
                    append("  [").append(t.name).append("]\n")
                    append(sw.toString())
                }.take(4000)
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, txt).apply()
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun take(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = p.getString(KEY, null)
        if (s != null) p.edit().remove(KEY).apply()
        return s
    }
}
