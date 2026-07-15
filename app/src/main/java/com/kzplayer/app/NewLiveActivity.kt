package com.kzplayer.app

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Ecran TV (theme NewTivi) : liste des chaines en direct. Un clic = on regarde la chaine.
// Le Guide (EPG) est un ecran separe (NewGuideActivity).
class NewLiveActivity : NtCatalogActivity() {
    override val kind = "live"
    override val navTag = "tv"
    override val screenTitle = "TV"

    override fun openItem(item: Item) {
        val pl = Session.current ?: return
        Session.liveChannels = visibleItems().filter { it.kind == "live" }
        if (pl.type == "stalker") {
            val cmd = item.cmd
            if (cmd.isNullOrBlank()) return
            lifecycleScope.launch {
                val link = try { Api.stalkerLink(pl, cmd, "live") } catch (e: Exception) { null }
                if (!link.isNullOrBlank()) openPreview(link, item)
            }
            return
        }
        val url = item.directUrl ?: return
        openPreview(url, item)
    }

    private fun openPreview(url: String, item: Item) {
        startActivity(
            Intent(this, LivePreviewActivity::class.java)
                .putExtra("url", url).putExtra("title", item.name)
                .putExtra("logo", item.logo).putExtra("streamId", item.streamId ?: "")
        )
    }
}
