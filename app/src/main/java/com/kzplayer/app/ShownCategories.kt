package com.kzplayer.app

import android.content.Context
import org.json.JSONArray

// Liste blanche des categories a AFFICHER, choisie par l'utilisateur, par serveur (playlist)
// et par section (live/movie/series).
// Regle : liste vide = aucune restriction => TOUT est affiche (comportement par defaut).
//         liste non vide = on affiche uniquement ces categories.
// Stocke les NOMS (insensible a la casse).
object ShownCategories {
    private const val PREF = "kz_shown_categories"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun key(playlistId: String, kind: String) = "$playlistId|$kind"

    // true si l'utilisateur a deja configure une liste pour ce serveur+section (meme vide).
    fun has(ctx: Context, playlistId: String, kind: String): Boolean =
        prefs(ctx).contains(key(playlistId, kind))

    fun shownNames(ctx: Context, playlistId: String, kind: String): Set<String> {
        val raw = prefs(ctx).getString(key(playlistId, kind), "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = HashSet<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).lowercase().trim()
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    fun setShown(ctx: Context, playlistId: String, kind: String, names: List<String>) {
        val arr = JSONArray()
        for (n in names) if (n.isNotBlank()) arr.put(n)
        prefs(ctx).edit().putString(key(playlistId, kind), arr.toString()).apply()
    }
}
