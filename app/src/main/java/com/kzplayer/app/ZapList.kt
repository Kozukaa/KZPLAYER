package com.kzplayer.app

// v353 : memorise la liste de chaines de la CATEGORIE affichee (dans l ordre de
// l ecran) et la position de la chaine lancee, pour que le zapping fleche haut /
// fleche bas reste dans la meme categorie, dans tous les themes.
object ZapList {

    fun set(list: List<Item>, current: Item?) {
        val chans = list.filter { it.kind == "live" || it.kind == "channel" }
        Session.zapChannels = chans
        Session.zapIndex = indexOf(chans, current)
    }

    fun indexOf(list: List<Item>, item: Item?): Int {
        if (item == null) return -1
        val sid = item.streamId ?: ""
        val cmd = item.cmd ?: ""
        var i = -1
        if (sid.isNotBlank()) i = list.indexOfFirst { (it.streamId ?: "") == sid }
        if (i < 0 && cmd.isNotBlank()) i = list.indexOfFirst { (it.cmd ?: "") == cmd }
        if (i < 0) i = list.indexOfFirst { it.name == item.name }
        return i
    }

    // Repli quand l ecran d origine n a pas renseigne la position.
    fun indexOf(name: String, streamId: String, cmd: String): Int {
        val list = Session.zapChannels
        var i = -1
        if (streamId.isNotBlank()) i = list.indexOfFirst { (it.streamId ?: "") == streamId }
        if (i < 0 && cmd.isNotBlank()) i = list.indexOfFirst { (it.cmd ?: "") == cmd }
        if (i < 0 && name.isNotBlank()) i = list.indexOfFirst { it.name == name }
        return i
    }
}
