package com.kzplayer.app

import android.content.Context
import org.json.JSONArray

// Categories masquees par l'utilisateur, par serveur (playlist) et par section (live/movie/series).
// On stocke les NOMS de categories (insensible a la casse). Compatible avec le panel qui peut
// aussi imposer des masquages via le champ "hidden_categories" (liste de noms).
object HiddenCategories {
    private const val PREF = "kz_hidden_categories"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun key(playlistId: String, kind: String) = "$playlistId|$kind"

    fun hiddenNames(ctx: Context, playlistId: String, kind: String): Set<String> {
        val raw = prefs(ctx).getString(key(playlistId, kind), "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = HashSet<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).lowercase().trim()
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    fun isHidden(ctx: Context, playlistId: String, kind: String, cat: Category): Boolean =
        hiddenNames(ctx, playlistId, kind).contains(cat.name.lowercase().trim())

    fun setHidden(ctx: Context, playlistId: String, kind: String, hidden: List<Category>) {
        val arr = JSONArray()
        for (c in hidden) arr.put(c.name)
        prefs(ctx).edit().putString(key(playlistId, kind), arr.toString()).apply()
    }
}
