package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

open class CineNovaTvBaseActivity : NtBase() {
    protected open val modeTitle = "Channels"
    protected open val replayMode = false
    private lateinit var catRv: RecyclerView
    private lateinit var itemRv: RecyclerView
    private lateinit var channelTitle: TextView
    private lateinit var channelMeta: TextView
    private lateinit var epgTv: TextView
    private var cats: List<Category> = emptyList()
    private var channels: List<Item> = emptyList()
    private var selectedCat = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cinenova_tv)
        findViewById<TextView>(R.id.titleTv).text = modeTitle
        catRv = findViewById(R.id.catRv); itemRv = findViewById(R.id.itemRv)
        channelTitle = findViewById(R.id.channelTitleTv); channelMeta = findViewById(R.id.channelMetaTv); epgTv = findViewById(R.id.epgTv)
        catRv.layoutManager = LinearLayoutManager(this)
        itemRv.layoutManager = LinearLayoutManager(this)
        ensureSession { loadCats() }
    }

    private fun loadCats() {
        val pl = Session.current ?: return
        lifecycleScope.launch {
            cats = try { when(pl.type){"m3u"->Api.m3uCategories(pl,"live");"stalker"->Api.stalkerCategories(pl,"live");else->Api.xtreamCategories(pl,"live")} } catch(_:Exception){ emptyList() }
            if (replayMode) cats = cats.filter { true }
            catRv.adapter = CatAdapter(cats)
            cats.firstOrNull()?.let { selectCat(it) }
        }
    }
    private fun selectCat(c: Category) {
        selectedCat = c.id; catRv.adapter?.notifyDataSetChanged()
        val pl = Session.current ?: return
        lifecycleScope.launch {
            channels = try { when(pl.type){"m3u"->Api.m3uItems(pl,"live",c.id);"stalker"->Api.stalkerItems(pl,"live",c.id);else->Api.xtreamItems(pl,"live",c.id)} } catch(_:Exception){ emptyList() }
            if (replayMode) channels = channels.filter { it.catchup }
            itemRv.adapter = ChannelAdapter(channels)
            updatePanel(channels.firstOrNull())
        }
    }
    private fun updatePanel(item: Item?) {
        channelTitle.text = item?.name ?: ""
        channelMeta.text = if (replayMode) "REPLAY  •  CATCH-UP" else "• EN DIRECT   CH 2   CATCH-UP"
        epgTv.text = if (replayMode) "Programmes disponibles\nSélectionne une chaîne pour voir les replays." else "ON NOW\n${item?.description?.ifBlank { "Programme en cours" } ?: ""}\n\nUP NEXT\nProgramme suivant"
    }
    private fun play(item: Item) {
        val pl = Session.current ?: return
        lifecycleScope.launch {
            val url = try { if (pl.type == "stalker") Api.stalkerLink(pl, item.cmd ?: "", "live") else item.directUrl } catch(_:Exception){ null }
            if (url.isNullOrBlank()) { Toast.makeText(this@CineNovaTvBaseActivity,"Lecture indisponible",Toast.LENGTH_SHORT).show(); return@launch }
            Session.liveChannels = channels
            startActivity(Intent(this@CineNovaTvBaseActivity, PlayerActivity::class.java).putExtra("url",url).putExtra("title",item.name).putExtra("logo",item.logo).putExtra("historyKind","live").putExtra("mode","live"))
        }
    }
    inner class CatAdapter(val data: List<Category>): RecyclerView.Adapter<CatAdapter.VH>(){
        inner class VH(val tv: TextView): RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(p:ViewGroup,t:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_cinenova_category,p,false) as TextView)
        override fun getItemCount()=data.size
        override fun onBindViewHolder(h:VH,pos:Int){ val c=data[pos]; val sel=c.id==selectedCat; h.tv.text=c.name; h.tv.setTextColor(ContextCompat.getColor(this@CineNovaTvBaseActivity, if(sel) android.R.color.black else R.color.muted)); h.tv.setBackgroundResource(if(sel) R.drawable.bg_cn_cat_selected else android.R.color.transparent); h.tv.setOnClickListener{selectCat(c)} }
    }
    inner class ChannelAdapter(val data: List<Item>): RecyclerView.Adapter<ChannelAdapter.VH>(){
        inner class VH(v:View): RecyclerView.ViewHolder(v){ val logo:ImageView=v.findViewById(R.id.logoIv); val name:TextView=v.findViewById(R.id.nameTv); val sub:TextView=v.findViewById(R.id.subTv); val num:TextView=v.findViewById(R.id.numTv) }
        override fun onCreateViewHolder(p:ViewGroup,t:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_cinenova_channel,p,false))
        override fun getItemCount()=data.size
        override fun onBindViewHolder(h:VH,pos:Int){ val item=data[pos]; h.logo.load(item.logo){error(R.drawable.ic_live_tv)}; h.name.text=item.name; h.sub.text=item.description.ifBlank { if(replayMode) "recordings" else "Programme" }; h.num.text=(pos+1).toString(); h.itemView.setOnFocusChangeListener{_,has-> if(has) updatePanel(item); h.itemView.setBackgroundResource(if(has) R.drawable.bg_cn_cat_selected else R.drawable.bg_cat)}; h.itemView.setOnClickListener{ if(replayMode) updatePanel(item) else play(item) }; h.itemView.setOnLongClickListener{ val added=Favorites.toggle(this@CineNovaTvBaseActivity,item); Toast.makeText(this@CineNovaTvBaseActivity, if(added)"Ajouté aux favoris" else "Retiré des favoris", Toast.LENGTH_SHORT).show(); true } }
    }
}
