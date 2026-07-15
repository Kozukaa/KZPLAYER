package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val item = Session.detailItem
        val pl = Session.current
        val title = findViewById<TextView>(R.id.detailTitle)
        val desc = findViewById<TextView>(R.id.detailDesc)
        val playBtn = findViewById<Button>(R.id.playBtn)
        val favBtn = findViewById<Button>(R.id.favBtn)
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
                desc.text = if (info.plot.isBlank()) (if (item.description.isBlank()) "Pas de resume disponible pour ce titre." else item.description) else info.plot
            } else {
                desc.text = if (item.description.isBlank()) "Pas de resume disponible pour ce titre." else item.description
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
}
