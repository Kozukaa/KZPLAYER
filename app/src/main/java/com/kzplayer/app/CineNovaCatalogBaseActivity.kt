package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

abstract class CineNovaCatalogBaseActivity : NtBase() {
    protected abstract val kind: String
    protected abstract val screenTitle: String
    private lateinit var catRv: RecyclerView
    private lateinit var itemsRv: RecyclerView
    private lateinit var bg: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroDesc: TextView
    private lateinit var rowTitle: TextView
    private var cats: List<Category> = emptyList()
    private var items: List<Item> = emptyList()
    private var selected = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cinenova_catalog)
        findViewById<TextView>(R.id.cnScreenTitle).text = screenTitle
        catRv = findViewById(R.id.cnCatRv); itemsRv = findViewById(R.id.cnItemsRv)
        bg = findViewById(R.id.cnBg); heroTitle = findViewById(R.id.cnHeroTitle); heroDesc = findViewById(R.id.cnHeroDesc); rowTitle = findViewById(R.id.cnRowTitle)
        catRv.layoutManager = LinearLayoutManager(this)
        itemsRv.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        ensureSession { loadCats() }
    }
    private fun loadCats() {
        val pl = Session.current ?: return
        lifecycleScope.launch {
            cats = try { when(pl.type){"m3u"->Api.m3uCategories(pl,kind);"stalker"->Api.stalkerCategories(pl,kind);else->Api.xtreamCategories(pl,kind)} } catch(_:Exception){ emptyList() }
            catRv.adapter = CatAdapter(cats)
            cats.firstOrNull()?.let { selectCat(it) }
        }
    }
    private fun selectCat(c: Category) {
        val pl = Session.current ?: return
        selected = c.id; rowTitle.text = c.name.uppercase(); catRv.adapter?.notifyDataSetChanged()
        lifecycleScope.launch {
            items = try { when(pl.type){"m3u"->Api.m3uItems(pl,kind,c.id);"stalker"->Api.stalkerItems(pl,kind,c.id);else->Api.xtreamItems(pl,kind,c.id)} } catch(_:Exception){ emptyList() }
            itemsRv.adapter = CardAdapter(items)
            updateHero(items.firstOrNull())
        }
    }
    private fun updateHero(item: Item?) { heroTitle.text = item?.name ?: screenTitle; heroDesc.text = item?.description?.ifBlank { item.summary } ?: ""; if (!item?.logo.isNullOrBlank()) bg.load(item!!.logo) }
    inner class CatAdapter(val data: List<Category>): RecyclerView.Adapter<CatAdapter.VH>(){
        inner class VH(val tv:TextView):RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(p:ViewGroup,t:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_cinenova_category,p,false) as TextView)
        override fun getItemCount()=data.size
        override fun onBindViewHolder(h:VH,pos:Int){ val c=data[pos]; h.tv.text=c.name; val sel=c.id==selected; h.tv.setTextColor(ContextCompat.getColor(this@CineNovaCatalogBaseActivity, if(sel) android.R.color.black else R.color.muted)); h.tv.setBackgroundResource(if(sel) R.drawable.bg_cn_cat_selected else android.R.color.transparent); h.tv.setOnClickListener{selectCat(c)} }
    }
    inner class CardAdapter(val data: List<Item>): RecyclerView.Adapter<CardAdapter.VH>(){
        inner class VH(v:View):RecyclerView.ViewHolder(v){ val img:ImageView=v.findViewById(R.id.posterIv); val name:TextView=v.findViewById(R.id.nameTv) }
        override fun onCreateViewHolder(p:ViewGroup,t:Int)=VH(LayoutInflater.from(p.context).inflate(R.layout.item_cinenova_card,p,false))
        override fun getItemCount()=data.size
        override fun onBindViewHolder(h:VH,pos:Int){ val item=data[pos]; h.name.text=item.name; h.img.load(item.logo){error(R.drawable.ic_movie)}; h.itemView.setOnFocusChangeListener{_,has-> if(has) updateHero(item); h.itemView.animate().scaleX(if(has)1.06f else 1f).scaleY(if(has)1.06f else 1f).setDuration(80).start()}; h.itemView.setOnClickListener{openItem(item)} }
    }
    private fun openItem(item: Item){ if(kind=="series"){Session.seriesItem=item; startActivity(Intent(this,NewSeriesDetailActivity::class.java))} else {Session.detailItem=item; startActivity(Intent(this,DetailActivity::class.java))} }
}
