package com.kzplayer.app

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Ecran TV EN DIRECT style Netflix (autonome). Un clic sur une chaine lance directement
// le lecteur plein ecran existant (meme moteur, meme resolution de flux Stalker/M3U/Xtream).
class NflxLiveActivity : NflxCatalogActivity() {
    override val kind = "live"
    override val screenTitle = "TV en direct"
    override val landscape = true

    override fun onCardClick(item: Item) {
        val pl = Session.current ?: return
        if (pl.type == "stalker") {
            val cmd = item.cmd
            if (cmd.isNullOrBlank()) return
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(pl, cmd, "live") } catch (e: Exception) { null }
                if (!link.isNullOrBlank()) openPlayer(item, link)
            }
            return
        }
        val url = item.directUrl ?: return
        openPlayer(item, url)
    }

    private fun openPlayer(item: Item, url: String) {
        // Zapping : rend disponible la liste des chaines deja chargees.
        val live = allLoadedItems().filter { it.kind == "live" }
        if (live.isNotEmpty()) Session.liveChannels = live
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", url).putExtra("title", item.name).putExtra("logo", item.logo)
                .putExtra("historyKind", "live").putExtra("mode", "live")
        )
    }
}
