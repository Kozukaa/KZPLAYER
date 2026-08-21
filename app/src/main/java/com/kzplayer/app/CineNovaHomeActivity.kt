package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

class CineNovaHomeActivity : NtBase() {
    data class Row(val title: String, val items: List<Item>)

    private lateinit var rowsRv: RecyclerView
    private lateinit var navBox: LinearLayout
    private lateinit var heroBg: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView
    private val rows = ArrayList<Row>()
    private var loaded = false

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cinenova_home)
        rowsRv = findViewById(R.id.cnRowsRv)
        navBox = findViewById(R.id.cnNavBox)
        heroBg = findViewById(R.id.cnHeroBg)
        heroTitle = findViewById(R.id.cnHeroTitle)
        heroMeta = findViewById(R.id.cnHeroMeta)
        heroDesc = findViewById(R.id.cnHeroDesc)
        rowsRv.layoutManager = LinearLayoutManager(this)
        buildNav()
        updateHero(null)
        ensureSession { loadEverything() }
    }

    private fun buildNav() {
        navBox.removeAllViews()
        navBox.isFocusable = false
        var firstItem: TextView? = null
        fun nav(label: String, action: () -> Unit) {
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 15f
            tv.setTypeface(null, android.graphics.Typeface.BOLD)
            tv.gravity = android.view.Gravity.CENTER_VERTICAL
            tv.setPadding(dp(14), 0, dp(12), 0)
            tv.isFocusable = true
            tv.isFocusableInTouchMode = true
            tv.isClickable = true
            tv.setTextColor(ContextCompat.getColor(this, R.color.muted))
            tv.setBackgroundResource(android.R.color.transparent)
            tv.setOnFocusChangeListener { _, has ->
                if (has) {
                    tv.setBackgroundResource(R.drawable.bg_cn_nav_selected)
                    tv.setTextColor(ContextCompat.getColor(this, android.R.color.black))
                } else {
                    tv.setBackgroundResource(android.R.color.transparent)
                    tv.setTextColor(ContextCompat.getColor(this, R.color.muted))
                }
            }
            tv.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
            lp.bottomMargin = dp(6)
            navBox.addView(tv, lp)
            if (firstItem == null) firstItem = tv
        }

        nav("Rechercher") { startActivity(Intent(this, VoiceActivity::class.java)) }
        nav("TV en direct") { startActivity(Intent(this, CineNovaLiveActivity::class.java)) }
        nav("Guide TV") { startActivity(Intent(this, CineNovaGuideActivity::class.java)) }
        nav("Replay") { startActivity(Intent(this, CineNovaReplayActivity::class.java)) }
        nav("Films") { startActivity(Intent(this, CineNovaMoviesActivity::class.java)) }
        nav("Séries") { startActivity(Intent(this, CineNovaSeriesActivity::class.java)) }
        nav("Ma liste") { openBrowse("favorites", "Ma liste") }
        nav("Réglages") { startActivity(Intent(this, SettingsActivity::class.java)) }

        firstItem?.post { firstItem?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        if (!loaded) ensureSession { loadEverything() }
    }

    private fun loadEverything() {
        loaded = true
        rows.clear()
        rowsRv.adapter = RowAdapter(rows)

        val favM = Favorites.forKind(this, "movie")
        val favS = Favorites.forKind(this, "series")
        val rec = (WatchHistory.recentItems(this, "movie") + WatchHistory.recentItems(this, "series"))
            .sortedByDescending { it.added }
        if (rec.isNotEmpty()) addRow(Row("REPRENDRE", rec.take(20)))
        if ((favM + favS).isNotEmpty()) addRow(Row("MA LISTE", (favM + favS).take(20)))

        loadCatalog("movie", "FILMS RÉCEMMENT AJOUTÉS")
        loadCatalog("series", "SÉRIES POPULAIRES")
    }

    private fun addRow(row: Row) {
        rows.add(row)
        rowsRv.adapter?.notifyDataSetChanged()
    }

    private fun loadCatalog(kind: String, title: String) {
        val pl = Session.current ?: return
        lifecycleScope.launch {
            val list = try {
                when (pl.type) {
                    "m3u" -> Api.m3uItems(pl, kind, "__all__")
                    "stalker" -> Api.stalkerItems(pl, kind, "__all__")
                    else -> Api.xtreamItems(pl, kind, "__all__")
                }
            } catch (_: Exception) { emptyList<Item>() }
            if (list.isNotEmpty()) {
                addRow(Row(title, list.take(24)))
                if (heroTitle.text.isNullOrBlank() || heroTitle.text == "CinéNova") updateHero(list.first())
            }
        }
    }

    private fun updateHero(item: Item?) {
        heroTitle.text = if (item == null) "CinéNova" else item.name
        heroMeta.text = if (item == null) "Films  •  Séries  •  TV  •  Replay" else "FILM  •  HD"
        heroDesc.text = when {
            item == null -> "Descends dans le menu à gauche, puis va à droite pour parcourir les affiches."
            item.summary.isNotBlank() -> item.summary
            item.description.isNotBlank() -> item.description
            else -> ""
        }
        if (item != null && item.logo.isNotBlank()) heroBg.load(item.logo) { crossfade(true) }
    }

    private fun openBrowse(kind: String, title: String) {
        Session.browseTitle = title
        startActivity(Intent(this, BrowseActivity::class.java).putExtra("kind", kind))
    }

    private fun openItem(item: Item) {
        when (item.kind) {
            "live" -> startActivity(Intent(this, CineNovaLiveActivity::class.java))
            "series" -> { Session.seriesItem = item; startActivity(Intent(this, NewSeriesDetailActivity::class.java)) }
            else -> { Session.detailItem = item; startActivity(Intent(this, DetailActivity::class.java)) }
        }
    }

    inner class RowAdapter(val data: List<Row>) : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.rowTitle)
            val rv: RecyclerView = v.findViewById(R.id.rowRv)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_cinenova_row, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = data[pos]
            h.title.text = r.title
            h.rv.layoutManager = LinearLayoutManager(this@CineNovaHomeActivity, RecyclerView.HORIZONTAL, false)
            h.rv.adapter = CardAdapter(r.items)
        }
    }

    inner class CardAdapter(val data: List<Item>) : RecyclerView.Adapter<CardAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.posterIv)
            val name: TextView = v.findViewById(R.id.nameTv)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_cinenova_card, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = data[pos]
            h.name.text = item.name
            h.img.load(item.logo) { error(R.drawable.ic_movie) }
            h.itemView.setOnFocusChangeListener { _, has ->
                if (has) updateHero(item)
                h.itemView.animate()
                    .scaleX(if (has) 1.06f else 1f)
                    .scaleY(if (has) 1.06f else 1f)
                    .setDuration(90).start()
            }
            h.itemView.setOnClickListener { openItem(item) }
        }
    }
}
