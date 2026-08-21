package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class DetailActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val item = Session.detailItem
        val pl = Session.current
        val title = findViewById<TextView>(R.id.detailTitle)
        val desc = findViewById<TextView>(R.id.detailDesc)
        val playBtn = findViewById<Button>(R.id.playBtn)
        val trailerBtn = findViewById<Button>(R.id.trailerBtn)
        val favBtn = findViewById<TextView>(R.id.favBtn)
        val prog = findViewById<ProgressBar>(R.id.detailProgress)
        val back = findViewById<TextView>(R.id.backBtn)

        back.setOnClickListener { finish() }

        if (item == null || pl == null) { finish(); return }

        fun refreshFav() {
            favBtn.text = if (Favorites.isFavorite(this, item)) "★ Favori" else "☆ Favori"
        }
        refreshFav()
        favBtn.setOnClickListener {
            val added = Favorites.toggle(this, item)
            favBtn.text = if (added) "★ Favori" else "☆ Favori"
        }

        title.text = item.name
        findViewById<ImageView>(R.id.posterIv).load(item.logo) {
            placeholder(R.drawable.bg_tile)
            error(R.drawable.ic_movie)
            crossfade(true)
        }
        val meta = findViewById<TextView>(R.id.detailMeta)
        meta.text = if (item.duration.isNotBlank()) "Durée : ${item.duration}" else ""
        desc.text = "Chargement du resume..."

        lifecycleScope.launch {
            val info = try {
                val sid = item.streamId
                if (pl.type == "xtream" && !sid.isNullOrBlank()) Api.xtreamVodInfo(pl, sid) else null
            } catch (e: Exception) { null }
            if (info != null) {
                if (info.duration.isNotBlank()) meta.text = "Durée : ${info.duration}"
                val p = Api.cleanPlot(info.plot).ifBlank { Api.cleanPlot(item.description) }
                desc.text = if (p.isBlank()) "Pas de resume disponible pour ce titre." else p
            } else {
                val p = Api.cleanPlot(item.description)
                desc.text = if (p.isBlank()) "Pas de resume disponible pour ce titre." else p
            }
        }

        loadCastAndSimilar(item, pl)

        trailerBtn.setOnClickListener {
            trailerBtn.isEnabled = false
            lifecycleScope.launch {
                // v342 : on tente en film puis en serie (les listes IPTV melangent les deux).
                var url = try { Tmdb.trailerUrl(item.name, false) } catch (_: Exception) { "" }
                if (url.isBlank()) url = try { Tmdb.trailerUrl(item.name, true) } catch (_: Exception) { "" }
                trailerBtn.isEnabled = true
                if (url.isBlank()) {
                    desc.text = "Aucune bande-annonce trouvée pour ce titre."
                } else {
                    startActivity(
                        Intent(this@DetailActivity, TrailerPlayerActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", "Bande-annonce - ${item.name}")
                    )
                }
            }
        }

        playBtn.setOnClickListener {
            prog.visibility = View.VISIBLE
            playBtn.isEnabled = false
            lifecycleScope.launch {
                val url = try {
                    if (pl.type == "stalker") Api.stalkerLink(pl, item.cmd ?: "", "movie")
                    else item.directUrl
                } catch (e: Exception) { null }
                prog.visibility = View.GONE
                playBtn.isEnabled = true
                if (!url.isNullOrBlank()) {
                    WatchHistory.touch(
                        this@DetailActivity,
                        url,
                        item.name,
                        item.logo,
                        "movie",
                        sourceCmd = item.cmd ?: "",
                        sourceStreamId = item.streamId ?: "",
                        sourceContainerExt = item.containerExt ?: ""
                    )
                    startActivity(
                        Intent(this@DetailActivity, PlayerActivity::class.java)
                            .putExtra("url", url)
                            .putExtra("title", item.name)
                            .putExtra("logo", item.logo)
                            .putExtra("historyKind", "movie")
                            .putExtra("historySourceCmd", item.cmd ?: "")
                            .putExtra("historySourceStreamId", item.streamId ?: "")
                            .putExtra("historySourceContainerExt", item.containerExt ?: "")
                            .putExtra("mode", "vod")
                    )
                } else {
                    desc.text = "Impossible d'obtenir le flux pour ce titre."
                }
            }
        }
    }

    // ============================ v338 : CASTING + TITRES SIMILAIRES ============================
    // - Casting : TMDB (photo + nom + role).
    // - Titres similaires : suggestions TMDB filtrees pour ne garder QUE les titres reellement
    //   presents dans la liste de lecture active (donc lisibles en un clic).
    // 100% additif : si TMDB est indisponible, les deux sections restent simplement masquees.
    private fun loadCastAndSimilar(item: Item, pl: Playlist) {
        val castTitle = findViewById<TextView>(R.id.castTitle)
        val castRv = findViewById<RecyclerView>(R.id.castRv)
        val simTitle = findViewById<TextView>(R.id.similarTitle)
        val simRv = findViewById<RecyclerView>(R.id.similarRv)

        lifecycleScope.launch {
            val cast = try { Tmdb.castFor(item.name, false) } catch (e: Exception) { emptyList<Tmdb.CastMember>() }
            if (cast.isNotEmpty()) {
                castTitle.visibility = View.VISIBLE
                castRv.visibility = View.VISIBLE
                castRv.layoutManager = LinearLayoutManager(this@DetailActivity, RecyclerView.HORIZONTAL, false)
                castRv.adapter = CastAdapter(cast)
            }
        }

        lifecycleScope.launch {
            val names = try { Tmdb.similarTitles(item.name, false) } catch (e: Exception) { emptyList<String>() }
            if (names.isEmpty()) return@launch
            val wanted = names.map { Tmdb.matchKey(it) }.filter { it.length >= 4 }.toHashSet()
            val catalog = try {
                when (pl.type) {
                    "m3u" -> Api.m3uItems(pl, "movie", "__all__")
                    "stalker" -> Api.stalkerItems(pl, "movie", "__all__")
                    else -> Api.xtreamItems(pl, "movie", "__all__")
                }
            } catch (e: Exception) { emptyList<Item>() }
            val mine = Tmdb.matchKey(item.name)
            val found = catalog.filter { c ->
                val k = Tmdb.matchKey(c.name)
                k.isNotBlank() && k != mine && wanted.contains(k)
            }.distinctBy { Tmdb.matchKey(it.name) }.take(20)
            if (found.isNotEmpty()) {
                simTitle.visibility = View.VISIBLE
                simRv.visibility = View.VISIBLE
                simRv.layoutManager = LinearLayoutManager(this@DetailActivity, RecyclerView.HORIZONTAL, false)
                simRv.adapter = SimilarAdapter(found)
            }
        }
    }

    private inner class CastAdapter(val data: List<Tmdb.CastMember>) :
        RecyclerView.Adapter<CastAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.castIv)
            val name: TextView = v.findViewById(R.id.castName)
            val role: TextView = v.findViewById(R.id.castRole)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_kz_cast, parent, false))
        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            holder.name.text = c.name
            holder.role.text = c.role
            if (c.photo.isBlank()) holder.img.setImageResource(R.drawable.ic_person)
            else holder.img.load(c.photo) { crossfade(true); error(R.drawable.ic_person) }
        }
    }

    private inner class SimilarAdapter(val data: List<Item>) :
        RecyclerView.Adapter<SimilarAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.posterIv)
            val name: TextView = v.findViewById(R.id.nameTv)
            val sub: TextView = v.findViewById(R.id.subTv)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_nflx_card, parent, false))
        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val it2 = data[position]
            holder.name.text = it2.name
            holder.sub.visibility = View.GONE
            if (it2.logo.isBlank()) holder.poster.setImageResource(R.drawable.ic_movie)
            else holder.poster.load(it2.logo) { crossfade(false); error(R.drawable.ic_movie) }
            holder.itemView.setOnFocusChangeListener { v, has ->
                val sc = if (has) 1.08f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(110).start()
            }
            holder.itemView.setOnClickListener {
                Session.detailItem = it2
                startActivity(Intent(this@DetailActivity, DetailActivity::class.java))
            }
        }
    }
}
