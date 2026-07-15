package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import coil.load
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ecran GUIDE (theme NewTivi) : categories a gauche, grille EPG (chaine + programmes) a droite.
class NewGuideActivity : NtBase() {
    private lateinit var catRv: RecyclerView
    private lateinit var channelRv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var msgTv: TextView
    private lateinit var clockTv: TextView
    private lateinit var heroImg: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroTime: TextView
    private lateinit var heroDesc: TextView

    private var categories: List<Category> = emptyList()
    private var channels: List<Item> = emptyList()
    private var selectedCat: String = ""
    private var catAdapter: CatAdapter? = null
    private var chAdapter: ChannelAdapter? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            clockTv.text = SimpleDateFormat("EEE d MMM  HH:mm", Locale.getDefault()).format(Date())
            clockHandler.postDelayed(this, 30000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_guide)
        NavHelper.setup(this, "guide")
        catRv = findViewById(R.id.catRv)
        channelRv = findViewById(R.id.channelRv)
        progress = findViewById(R.id.progress)
        msgTv = findViewById(R.id.msgTv)
        clockTv = findViewById(R.id.clockTv)
        heroImg = findViewById(R.id.heroImg)
        heroTitle = findViewById(R.id.heroTitle)
        heroTime = findViewById(R.id.heroTime)
        heroDesc = findViewById(R.id.heroDesc)
        catRv.layoutManager = LinearLayoutManager(this)
        channelRv.layoutManager = LinearLayoutManager(this)
        channelRv.setItemViewCacheSize(20)
        clockRunnable.run()
        ensureSession { loadCategories() }
    }

    override fun onDestroy() { super.onDestroy(); clockHandler.removeCallbacks(clockRunnable) }

    private fun setLoading(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun loadCategories() {
        val pl = Session.current ?: run { msgTv.text = "Serveurs indisponibles."; return }
        setLoading(true); msgTv.text = ""
        lifecycleScope.launch {
            try {
                val base = when (pl.type) {
                    "m3u" -> Api.m3uCategories(pl, "live")
                    "stalker" -> Api.stalkerCategories(pl, "live")
                    else -> Api.xtreamCategories(pl, "live")
                }
                categories = listOf(Category("__favorites__", "Favoris")) + base.filter { !it.id.startsWith("__") }
                catAdapter = CatAdapter(categories) { selectCategory(it) }
                catRv.adapter = catAdapter
                setLoading(false)
                val firstReal = categories.firstOrNull { !it.id.startsWith("__") } ?: categories.firstOrNull()
                if (firstReal != null) selectCategory(firstReal)
            } catch (e: Exception) {
                setLoading(false); msgTv.text = "Erreur : ${e.message}"
            }
        }
    }

    private fun selectCategory(cat: Category) {
        selectedCat = cat.id
        catAdapter?.notifyDataSetChanged()
        val pl = Session.current ?: return
        if (cat.id == "__favorites__") {
            channels = Favorites.forKind(this, "live")
            bindChannels()
            msgTv.text = if (channels.isEmpty()) "Aucune cha\u00eene favorite." else ""
            setLoading(false)
            return
        }
        setLoading(true); msgTv.text = ""; channels = emptyList(); bindChannels()
        lifecycleScope.launch {
            try {
                when (pl.type) {
                    "stalker" -> {
                        val acc = ArrayList<Item>()
                        Api.stalkerItemsPaged(pl, "live", cat.id) { batch ->
                            withContext(Dispatchers.Main) {
                                acc.addAll(batch); channels = acc.toList(); bindChannels(); setLoading(false)
                            }
                        }
                    }
                    "m3u" -> { channels = Api.m3uItems(pl, "live", cat.id); bindChannels(); setLoading(false) }
                    else -> { channels = Api.xtreamItems(pl, "live", cat.id); bindChannels(); setLoading(false) }
                }
                if (channels.isEmpty()) msgTv.text = "Aucune cha\u00eene." else msgTv.text = ""
            } catch (e: Exception) { setLoading(false); msgTv.text = "Erreur : ${e.message}" }
        }
    }

    private fun bindChannels() {
        if (chAdapter == null) { chAdapter = ChannelAdapter(); channelRv.adapter = chAdapter }
        chAdapter?.submit(channels)
    }

    private suspend fun epgFor(pl: Playlist, item: Item): List<EpgEntry> = try {
        when (pl.type) {
            "xtream" -> item.streamId?.let { Api.xtreamFullEpg(pl, it) } ?: emptyList()
            "stalker" -> item.streamId?.let { Api.stalkerShortEpg(pl, it) } ?: emptyList()
            else -> emptyList()
        }
    } catch (e: Exception) { emptyList() }

    private fun updateHero(item: Item, epg: List<EpgEntry>) {
        val now = epg.firstOrNull { it.nowPlaying } ?: epg.firstOrNull()
        heroTitle.text = (now?.title ?: "").ifBlank { item.name }
        heroTime.text = now?.time ?: ""
        heroDesc.text = now?.description ?: ""
        heroImg.load(item.logo) { crossfade(true); placeholder(R.drawable.bg_tile); error(R.drawable.ic_live_tv) }
    }

    private fun playChannel(item: Item) {
        val pl = Session.current ?: return
        if (pl.type == "stalker") {
            val cmd = item.cmd
            if (cmd.isNullOrBlank()) { msgTv.text = "Flux indisponible."; return }
            setLoading(true)
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(pl, cmd, "live") } catch (e: Exception) { null }
                setLoading(false)
                if (!link.isNullOrBlank()) openPreview(link, item) else msgTv.text = "Impossible d'obtenir le flux."
            }
            return
        }
        val url = item.directUrl ?: return
        openPreview(url, item)
    }

    private fun openPreview(url: String, item: Item) {
        Session.liveChannels = channels.filter { it.kind == "live" }
        startActivity(
            Intent(this, LivePreviewActivity::class.java)
                .putExtra("url", url).putExtra("title", item.name)
                .putExtra("logo", item.logo).putExtra("streamId", item.streamId ?: "")
        )
    }

    inner class CatAdapter(val data: List<Category>, val onClick: (Category) -> Unit) :
        RecyclerView.Adapter<CatAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView
            return VH(tv)
        }
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            holder.tv.text = c.name
            val sel = c.id == selectedCat
            holder.tv.isSelected = sel
            holder.tv.setTextColor(ContextCompat.getColor(holder.tv.context, if (sel) R.color.text else R.color.muted))
            holder.tv.setOnClickListener { onClick(c) }
        }
    }

    inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.VH>() {
        private val data = ArrayList<Item>()
        fun submit(list: List<Item>) { data.clear(); data.addAll(list); notifyDataSetChanged() }
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.logoIv)
            val name: TextView = v.findViewById(R.id.nameTv)
            val progRow: LinearLayout = v.findViewById(R.id.progRow)
            var boundStream: String = ""
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.name.text = item.name
            holder.logo.load(item.logo) { crossfade(false); placeholder(R.drawable.bg_tile); error(R.drawable.ic_live_tv) }
            holder.progRow.removeAllViews()
            holder.boundStream = item.streamId ?: item.name
            holder.v.setOnClickListener { playChannel(item) }
            holder.v.setOnFocusChangeListener { _, hasFocus ->
                holder.v.animate().scaleX(if (hasFocus) 1.01f else 1f).scaleY(if (hasFocus) 1.01f else 1f).setDuration(80).start()
                holder.v.translationZ = if (hasFocus) 10f else 0f
            }
            val pl = Session.current ?: return
            val key = holder.boundStream
            lifecycleScope.launch {
                val epg = epgFor(pl, item)
                if (holder.boundStream != key) return@launch
                renderProg(holder.progRow, epg)
                if (holder.v.hasFocus() || position == 0) updateHero(item, epg)
            }
        }
        private fun renderProg(row: LinearLayout, epg: List<EpgEntry>) {
            row.removeAllViews()
            val ctx = row.context
            val dens = resources.displayMetrics.density
            fun px(v: Int) = (v * dens).toInt()
            if (epg.isEmpty()) {
                row.addView(TextView(ctx).apply {
                    text = "Pas de guide"
                    setTextColor(ContextCompat.getColor(ctx, R.color.muted))
                    textSize = 12f
                    setPadding(px(10), px(6), px(10), px(6))
                })
                return
            }
            for (e in epg.take(6)) {
                val cell = TextView(ctx).apply {
                    text = (if (e.time.isNotBlank()) e.time + "  " else "") + e.title
                    setTextColor(ContextCompat.getColor(ctx, if (e.nowPlaying) R.color.text else R.color.muted))
                    textSize = 12f
                    maxLines = 1
                    setPadding(px(10), px(6), px(10), px(6))
                    setBackgroundResource(if (e.nowPlaying) R.drawable.bg_epg_now else R.drawable.bg_epg_cell)
                }
                val lp = LinearLayout.LayoutParams(px(190), LinearLayout.LayoutParams.MATCH_PARENT)
                lp.marginEnd = px(6)
                cell.layoutParams = lp
                row.addView(cell)
            }
        }
    }
}
