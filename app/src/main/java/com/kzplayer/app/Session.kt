package com.kzplayer.app

// Etat global partage entre les ecrans (simple et suffisant pour cette app).
object Session {
    var playlists: List<Playlist> = emptyList()
    var current: Playlist? = null
    var expiration: String? = null
    var browseTitle: String = ""
    var detailItem: Item? = null
    var seriesItem: Item? = null
    var liveChannels: List<Item> = emptyList()
    // File d'attente des episodes pour la lecture a la suite (fonctionne sur les 2 themes).
    var episodeQueue: List<Item> = emptyList()
    var episodeIndex: Int = -1
}
