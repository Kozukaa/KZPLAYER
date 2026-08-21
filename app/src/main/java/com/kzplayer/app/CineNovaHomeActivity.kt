package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CineNovaHomeActivity : NtBase() {
    data class Row(val title: String, val items: List<Item>)
    private lateinit var rowsRv: RecyclerView
    private lateinit var heroBg: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView
    private val rows = ArrayList<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cinenova_home)
        rowsRv = findViewById(R.id.cnRowsRv)
        heroBg = findViewById(R.id.cnHeroBg)
        heroTitle = findViewById(R.id.cnHeroTitle)
        heroMeta = findViewById(R.id.cnHeroMeta)
        heroDesc = findViewById(R.id.cnHeroDesc)
        rowsRv.layoutManager = LinearLayoutManager(this)
        buildNav()
        ensureSession { loadRows() }
    }

    override fun onResume() { super.onResume(); loadRows() }

    private fun buildNav() {
        val box = findViewById<LinearLayout>(R.id.cnNavBox)
        box.removeAllViews()
        fun nav(icon: String, label: String, selected: Boolean = false, action: () -> Unit): TextView {
            val tv = TextView(this)
            tv.text = "$icon   $label"
            tv.textSize = 14f
            tv.setTextColor(if (selected) ContextCompat.getColor(this, android.R.color.black) else ContextCompat.getColor(this, R.color.text))
            tv.setTypeface(null, android.graphics.Typeface.BOLD)
            tv.setPadding(14, 10, 10, 10)
            tv.isFocusable = true; tv.isClickable = true
            tv.background = ContextCompat.getDrawable(this, if (selected) R.drawable.bg_cn_nav_selected else android.R.color.transparent)
            tv.setOnClickListener { action() }
            box.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44).apply { bottomMargin = 8 })
            return tv
        }
        nav("⌕", "Rechercher") { startActivity(Intent(this, VoiceActivity::class.java)) }
        nav("▣", "TV en direct") { openLive() }
        nav("▤", "Guide TV") { startActivity(Intent(this, CineNovaGuideActivity::class.java)) }
        nav("↺", "Replay") { startActivity(Intent(this, CineNovaReplayActivity::class.java)) }
        nav("▥", "Films") { startActivity(Intent(this, CineNovaMoviesActivity::class.java)) }
        nav("▭", "Séries") { startActivity(Intent(this, CineNovaSeriesActivity::class.java)) }
        nav("▰", "Ma liste", true) { openBrowse("favorites", "Ma liste") }
        nav("⚙", "Réglages") { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    private fun loadRows() {
        rows.clear()
        val favM = Favorites.forKind(this, "movie")
        val favS = Favorites.forKind(this, "series")
        val rec = (WatchHistory.recentItems(this, "movie") + WatchHistory.recentItems(this, "series")).sortedByDescending { it.added }
        if (rec.isNotEmpty()) rows.add(Row("RECOMMENCER", rec.take(20)))
        if ((favM + favS).isNotEmpty()) rows.add(Row("MA LISTE", (favM + favS).take(20)))
        rowsRv.adapter = RowAdapter(rows)
        val first = rec.firstOrNull() ?: favM.firstOrNull() ?: favS.firstOrNull()
        updateHero(first)
        loadLatestMovies()
    }

    private fun loadLatestMovies() {
        val pl = Session.current ?: return
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { try {
                when (pl.type) { "m3u" -> Api.m3uItems(pl,"movie","__all__"); "stalker" -> Api.stalkerItems(pl,"movie","__all__"); else -> Api.xtreamItems(pl,"movie","__all__") }.take(24)
            } catch (_: Exception) { emptyList() } }
            if (list.isNotEmpty()) { rows.add(0, Row("RECEMMENT AJOUTÉES", list)); rowsRv.adapter?.notifyDataSetChanged(); if (heroTitle.text.isBlank()) updateHero(list.first()) }
        }
    }

    private fun updateHero(item: Item?) {
        heroTitle.text = item?.name ?: "CinéNova"
        heroMeta.text = "2026  •  Drame / Crime  •  ★ 7.2"
        heroDesc.text = item?.description?.ifBlank { item.summary } ?: "Choisis un film ou une série. La TV en direct possède maintenant son propre écran."
        if (!item?.logo.isNullOrBlank()) heroBg.load(item!!.logo) { crossfade(true) }
    }
    private fun openLive() = startActivity(Intent(this, CineNovaLiveActivity::class.java))
    private fun openBrowse(kind:String,title:String){ Session.browseTitle=title; startActivity(Intent(this,BrowseActivity::class.java).putExtra("kind",kind)) }

    inner class RowAdapter(val data: List<Row>): RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(v: View): RecyclerView.ViewHolder(v){ val title:TextView=v.findViewById(R.id.rowTitle); val rv:RecyclerView=v.findViewById(R.id.rowRv) }
        override fun onCreateViewHolder(p:ViewGroup,t:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_cinenova_row,p,false))
        override fun getItemCount()=data.size
        override fun onBindViewHolder(h:VH,pos:Int){ val r=data[pos]; h.title.text=r.title; h.rv.layoutManager=LinearLayoutManager(this@CineNovaHomeActivity,RecyclerView.HORIZONTAL,false); h.rv.adapter=CardAdapter(r.items) }
    }
    inner class CardAdapter(val data: List<Item>): RecyclerView.Adapter<CardAdapter.VH>() {
        inner class VH(v:View):RecyclerView.ViewHolder(v){ val img:ImageView=v.findViewById(R.id.posterIv); val name:TextView=v.findViewById(R.id.nameTv) }
        override fun onCreateViewHolder(p:ViewGroup,t:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_cinenova_card,p,false))
        override fun getItemCount()=data.size
        override fun onBindViewHolder(h:VH,pos:Int){ val item=data[pos]; h.name.text=item.name; h.img.load(item.logo){error(R.drawable.ic_movie)}; h.itemView.setOnFocusChangeListener{_,has-> if(has) updateHero(item); h.itemView.animate().scaleX(if(has)1.06f else 1f).scaleY(if(has)1.06f else 1f).setDuration(90).start()}; h.itemView.setOnClickListener{ openItem(item) } }
    }
    private fun openItem(item:Item){ when(item.kind){ "live" -> openLive(); "series" -> {Session.seriesItem=item; startActivity(Intent(this,NewSeriesDetailActivity::class.java))}; else -> {Session.detailItem=item; startActivity(Intent(this,DetailActivity::class.java))} } }
}
