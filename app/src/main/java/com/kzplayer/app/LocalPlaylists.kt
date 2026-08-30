package com.kzplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// v391 : listes de lecture ajoutees A LA MAIN depuis l application.
// Elles sont stockees sur l appareil et fusionnees avec les listes du panel,
// donc elles ne disparaissent jamais quand la licence est rechargee.
object LocalPlaylists {
    private const val PREFS = "kz_local_playlists"
    private const val KEY = "json"
    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLocal(id: String): Boolean = id.startsWith("loc_")

    fun newId(): String = "loc_" + System.currentTimeMillis().toString()

    fun all(ctx: Context): List<Playlist> {
        return try {
            val raw = prefs(ctx).getString(KEY, "").orEmpty()
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            val out = ArrayList<Playlist>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(fromJson(o))
            }
            out
        } catch (e: Throwable) { emptyList() }
    }

    fun add(ctx: Context, pl: Playlist) {
        val list = ArrayList(all(ctx))
        list.removeAll { it.id == pl.id }
        list.add(pl)
        save(ctx, list)
    }

    fun remove(ctx: Context, id: String) {
        val list = ArrayList(all(ctx))
        list.removeAll { it.id == id }
        save(ctx, list)
    }

    private fun save(ctx: Context, list: List<Playlist>) {
        try {
            val arr = JSONArray()
            for (p in list) arr.put(toJson(p))
            prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        } catch (e: Throwable) {}
    }

    // Fusion : listes du panel + listes ajoutees a la main sur cet appareil.
    fun merge(ctx: Context, remote: List<Playlist>): List<Playlist> {
        val locals = all(ctx)
        if (locals.isEmpty()) return remote
        val out = ArrayList<Playlist>(remote)
        for (p in locals) if (out.none { it.id == p.id }) out.add(p)
        return out
    }

    fun merge(remote: List<Playlist>): List<Playlist> {
        val c = app ?: return remote
        return merge(c, remote)
    }

    private fun toJson(p: Playlist): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("type", p.type)
        o.put("nom", p.nom)
        o.put("serverUrl", p.serverUrl)
        o.put("username", p.username)
        o.put("password", p.password)
        o.put("mac", p.mac)
        o.put("m3uUrl", p.m3uUrl)
        return o
    }

    private fun fromJson(o: JSONObject): Playlist = Playlist(
        id = o.optString("id", ""),
        type = o.optString("type", "xtream"),
        nom = o.optString("nom", ""),
        serverUrl = o.optString("serverUrl", ""),
        username = o.optString("username", ""),
        password = o.optString("password", ""),
        mac = o.optString("mac", ""),
        m3uUrl = o.optString("m3uUrl", "")
    )
}
