package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import coil.load
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

// Fiche serie NewTivi (style photo 3) : grand visuel, boutons, onglets de saisons,
// episodes en cartes horizontales. Reutilise la logique d'expansion des episodes existante.
class NewSeriesDetailActivity : AppCompatActivity() {
    private lateinit var episodeRv: RecyclerView
    private lateinit var seasonTabs: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView
    private val seasons = ArrayList<Pair<String, List<Item>>>()
    private var selectedSeason = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_series_detail)
        val series = Session.seriesItem
        val pl = Session.current
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        val playBtn = findViewById<Button>(R.id.playBtn)
        val favBtn = findViewById<Button>(R.id.favBtn)
        episodeRv = findViewById(R.id.episodeRv)
        seasonTabs = findViewById(R.id.seasonTabs)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        if (series == null || pl == null) { finish(); return }
        findViewById<TextView>(R.id.seriesTitle).text = series.name
        findViewById<TextView>(R.id.heroMeta).text = series.duration
        findViewById<TextView>(R.id.heroDesc).text = if (series.description.isNotBlank()) series.description else series.summary
        findViewById<ImageView>(R.id.heroImg).load(series.logo) { crossfade(true); placeholder(R.drawable.bg_tile); error(R.drawable.ic_movie) }
        fun refreshFav() { favBtn.text = if (Favorites.isFavorite(this, series)) "\u2713 Ma liste" else "\uFF0B Ma liste" }
        refreshFav()
        favBtn.setOnClickListener { Favorites.toggle(this, series); refreshFav() }
        episodeRv.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        playBtn.setOnClickListener { playFirstOfSeason() }

        setLoading(true)
        lifecycleScope.launch {
            try {
                val base = when (pl.type) {
                    "stalker" -> Api.stalkerSeriesExpanded(pl, series.seriesId ?: series.cmd ?: "")
                    "m3u" -> Api.m3uSeriesExpanded(pl, series.seriesId ?: series.name)
                    else -> Api.xtreamSeriesExpanded(pl, series.seriesId ?: "")
                }
                val rows = if (pl.type == "stalker" || pl.type == "m3u") Tmdb.enrich(series.name, base) else base
                buildSeasons(rows)
                renderTabs()
                showSeason(0)
                msgTv.text = if (seasons.isEmpty()) "Aucun \u00e9pisode disponible." else ""
            } catch (e: Exception) { msgTv.text = "Erreur : ${e.message}" }
            setLoading(false)
        }
    }

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun buildSeasons(rows: List<Item>) {
        seasons.clear()
        var curName = ""
        var cur = ArrayList<Item>()
        for (r in rows) {
            if (r.kind == "header") {
                if (cur.isNotEmpty()) { seasons.add((curName.ifBlank { "Saison" }) to cur.toList()); cur = ArrayList() }
                curName = r.name
            } else if (r.kind == "episode" || r.directUrl != null || r.cmd != null) {
                cur.add(r)
            }
        }
        if (cur.isNotEmpty()) seasons.add((curName.ifBlank { "\u00c9pisodes" }) to cur.toList())
        if (seasons.isEmpty()) {
            val eps = rows.filter { it.kind != "header" }
            if (eps.isNotEmpty()) seasons.add("\u00c9pisodes" to eps)
        }
    }

    private fun renderTabs() {
        seasonTabs.removeAllViews()
        val dens = resources.displayMetrics.density
        fun px(v: Int) = (v * dens).toInt()
        seasons.forEachIndexed { idx, pair ->
            val tab = TextView(this).apply {
                text = pair.first
                textSize = 14f
                setPadding(px(18), px(10), px(18), px(10))
                isFocusable = true; isClickable = true
                setBackgroundResource(R.drawable.bg_cat)
                setTextColor(ContextCompat.getColor(this@NewSeriesDetailActivity, if (idx == selectedSeason) R.color.text else R.color.muted))
                isSelected = idx == selectedSeason
                setOnClickListener { showSeason(idx) }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = px(10)
            tab.layoutParams = lp
            seasonTabs.addView(tab)
        }
    }

    private fun showSeason(idx: Int) {
        if (idx < 0 || idx >= seasons.size) return
        selectedSeason = idx
        renderTabs()
        episodeRv.adapter = EpAdapter(seasons[idx].second)
    }

    private fun playFirstOfSeason() {
        val list = seasons.getOrNull(selectedSeason)?.second ?: return
        val ep = list.firstOrNull { it.kind == "episode" || it.directUrl != null || it.cmd != null } ?: return
        playEpisode(ep)
    }

    private fun playEpisode(item: Item) {
        val pl = Session.current ?: return
        val direct = item.directUrl
        if (!direct.isNullOrBlank()) { play(direct, item.name, item.logo); return }
        val cmd = item.cmd
        if (cmd.isNullOrBlank()) { msgTv.text = "Flux indisponible."; return }
        setLoading(true)
        lifecycleScope.launch {
            val link = try { Api.stalkerLink(pl, cmd, "movie") } catch (e: Exception) { null }
            setLoading(false)
            if (!link.isNullOrBlank()) play(link, item.name, item.logo) else msgTv.text = "Impossible d'obtenir le flux."
        }
    }

    private fun play(url: String, title: String, episodeLogo: String) {
        val series = Session.seriesItem
        val fullTitle = if (series != null) "${series.name} - $title" else title
        val seriesName = series?.name ?: ""
        val seriesLogo = series?.logo ?: ""
        val logo = episodeLogo.ifBlank { seriesLogo }
        WatchHistory.touch(this, url, fullTitle, logo, "series", seriesName, seriesLogo, series?.seriesId ?: "", series?.cmd ?: "")
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", url).putExtra("title", fullTitle).putExtra("logo", logo)
                .putExtra("historyKind", "series").putExtra("seriesName", seriesName)
                .putExtra("seriesLogo", seriesLogo).putExtra("seriesId", series?.seriesId ?: "")
                .putExtra("seriesCmd", series?.cmd ?: "").putExtra("mode", "vod")
        )
    }

    inner class EpAdapter(val data: List<Item>) : RecyclerView.Adapter<EpAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.epThumb)
            val code: TextView = v.findViewById(R.id.epCode)
            val title: TextView = v.findViewById(R.id.epTitle)
            val summary: TextView = v.findViewById(R.id.epSummary)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_nt_episode, parent, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.code.text = "\u00c9pisode ${position + 1}"
            holder.title.text = item.name
            holder.summary.text = item.summary
            holder.summary.visibility = if (item.summary.isBlank()) View.GONE else View.VISIBLE
            holder.thumb.load(item.logo) { crossfade(false); placeholder(R.drawable.bg_tile); error(R.drawable.ic_movie) }
            holder.v.setOnFocusChangeListener { _, hasFocus ->
                holder.v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(90).start()
                holder.v.translationZ = if (hasFocus) 14f else 0f
            }
            holder.v.setOnClickListener { playEpisode(item) }
        }
    }
}
