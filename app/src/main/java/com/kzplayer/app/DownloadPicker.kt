package com.kzplayer.app

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

// v360 : choix de ce que l on telecharge dans une serie.
// 1) on choisit la saison, 2) on coche les episodes voulus (ou toute la saison).
object DownloadPicker {

    // Regroupe les lignes d une serie en saisons : les entetes "header" font la separation.
    fun groupSeasons(rows: List<Item>): List<Pair<String, List<Item>>> {
        val out = ArrayList<Pair<String, List<Item>>>()
        var curName = ""
        var cur = ArrayList<Item>()
        for (r in rows) {
            if (r.kind == "header") {
                if (cur.isNotEmpty()) {
                    out.add((if (curName.isBlank()) "Saison" else curName) to cur.toList())
                    cur = ArrayList()
                }
                curName = r.name
            } else if (r.kind == "episode" || r.directUrl != null || r.cmd != null) {
                cur.add(r)
            }
        }
        if (cur.isNotEmpty()) out.add((if (curName.isBlank()) "\u00c9pisodes" else curName) to cur.toList())
        if (out.isEmpty()) {
            val eps = rows.filter { it.kind != "header" }
            if (eps.isNotEmpty()) out.add("\u00c9pisodes" to eps)
        }
        return out
    }

    fun show(act: Activity, seasons: List<Pair<String, List<Item>>>) {
        if (seasons.isEmpty()) {
            Toast.makeText(act, "Aucun \u00e9pisode \u00e0 t\u00e9l\u00e9charger.", Toast.LENGTH_SHORT).show()
            return
        }
        if (seasons.size == 1) { showEpisodes(act, seasons[0]); return }
        val labels = seasons.map {
            it.first + "   (" + it.second.size + " \u00e9pisodes)"
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(act)
            .setTitle("T\u00e9l\u00e9charger : choisis la saison")
            .setItems(labels) { d, which ->
                d.dismiss()
                showEpisodes(act, seasons[which])
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showEpisodes(act: Activity, season: Pair<String, List<Item>>) {
        val eps = season.second
        if (eps.isEmpty()) {
            Toast.makeText(act, "Aucun \u00e9pisode dans cette saison.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = eps.mapIndexed { i, e ->
            "\u00c9p " + (i + 1) + "  -  " + e.name
        }.toTypedArray<CharSequence>()
        val checked = BooleanArray(eps.size)
        AlertDialog.Builder(act)
            .setTitle(season.first + " : coche les \u00e9pisodes")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("T\u00e9l\u00e9charger") { d, _ ->
                d.dismiss()
                start(act, eps.filterIndexed { i, _ -> checked[i] })
            }
            .setNeutralButton("Toute la saison") { d, _ ->
                d.dismiss()
                start(act, eps)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun start(act: Activity, list: List<Item>) {
        if (list.isEmpty()) {
            Toast.makeText(act, "Aucun \u00e9pisode s\u00e9lectionn\u00e9.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            act,
            "T\u00e9l\u00e9chargement de " + list.size + " \u00e9pisode(s) lanc\u00e9...",
            Toast.LENGTH_LONG
        ).show()
        val quiet = list.size > 3
        for (e in list) Downloads.requestItem(act, e, !quiet)
    }
}
