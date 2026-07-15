package com.kzplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Favorites {
    private const val PREF = "kz_favorites"
    private const val KEY = "items"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Item> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Item(
                    name = o.optString("name"),
                    logo = o.optString("logo"),
                    kind = o.optString("kind", "movie"),
                    directUrl = o.optString("directUrl").ifBlank { null },
                    streamId = o.optString("streamId").ifBlank { null },
                    containerExt = o.optString("containerExt").ifBlank { null },
                    seriesId = o.optString("seriesId").ifBlank { null },
                    cmd = o.optString("cmd").ifBlank { null },
                    description = o.optString("description"),
                    duration = o.optString("duration"),
                    summary = o.optString("summary"),
                    added = o.optLong("added", 0L),
                    season = o.optInt("season", 0),
                    catchup = o.optBoolean("catchup", false)
                )
            )
        }
        return out.filter { it.name.isNotBlank() }
    }

    fun forKind(ctx: Context, kind: String): List<Item> {
        return all(ctx).filter { it.kind == kind }.sortedBy { it.name.lowercase() }
    }

    fun isFavorite(ctx: Context, item: Item): Boolean {
        val key = key(item)
        return all(ctx).any { key(it) == key }
    }

    fun toggle(ctx: Context, item: Item): Boolean {
        val key = key(item)
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { key(it) == key }
        val nowFav = idx < 0
        if (idx >= 0) list.removeAt(idx) else list.add(0, item)
        save(ctx, list)
        return nowFav
    }

    private fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        for (e in items.take(300)) {
            arr.put(JSONObject().apply {
                put("name", e.name)
                put("logo", e.logo)
                put("kind", e.kind)
                put("directUrl", e.directUrl ?: "")
                put("streamId", e.streamId ?: "")
                put("containerExt", e.containerExt ?: "")
                put("seriesId", e.seriesId ?: "")
                put("cmd", e.cmd ?: "")
                put("description", e.description)
                put("duration", e.duration)
                put("summary", e.summary)
                put("added", e.added)
                put("season", e.season)
                put("catchup", e.catchup)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun key(item: Item): String {
        return when {
            item.directUrl != null -> "url:${item.directUrl}"
            item.streamId != null -> "stream:${item.kind}:${item.streamId}"
            item.seriesId != null -> "series:${item.seriesId}"
            item.cmd != null -> "cmd:${item.kind}:${item.cmd}"
            else -> "name:${item.kind}:${item.name}"
        }
    }
}
