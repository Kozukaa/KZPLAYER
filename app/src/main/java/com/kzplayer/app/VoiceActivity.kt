package com.kzplayer.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import java.text.Normalizer
import java.util.Locale

// Commande vocale globale (bouton micro). Ouvre la reconnaissance vocale Google de
// l'appareil, analyse la phrase et route vers l'action correspondante.
// Aucun impact lecteur / flux / licence : on ne fait que lancer des ecrans existants
// ou changer un reglage (serveur / theme / couleur).
class VoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ex : Mets TF1  \u00b7  Recherche Titanic  \u00b7  Serveur voiture")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            startActivityForResult(i, REQ)
        } catch (e: ActivityNotFoundException) {
            toast("Reconnaissance vocale indisponible sur cet appareil.")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotBlank()) route(spoken)
        }
        finish()
    }

    // ---------- Analyse de la phrase ----------
    private fun route(spoken: String) {
        val t = norm(spoken)
        val words = t.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val first = words[0]
        val rest = words.drop(1).joinToString(" ").trim()

        when {
            // 1. Changer de serveur / liste de lecture
            first == "serveur" || first == "server" || first == "liste" ->
                switchServer(rest)

            // 2. Recherche VOD
            first == "recherche" || first == "rechercher" || first == "cherche" ||
                first == "chercher" || first == "trouve" || first == "trouver" ->
                openSearch(rest)

            // 3. Mettre une chaine
            first == "met" || first == "mets" || first == "mais" || first == "mettre" ||
                first == "metre" || first == "lance" || first == "allume" -> {
                val name = if (rest.startsWith("sur ")) rest.removePrefix("sur ").trim() else rest
                openChannel(name)
            }

            // 4. Changer la couleur
            first == "couleur" || first == "couleurs" -> applyColor(rest.ifBlank { t })

            // 5. Changer de theme d'interface
            first == "theme" -> applyThemeSwitch(rest.ifBlank { t })

            // 6. Zapping
            first == "chaine" || first == "chaines" -> {
                when {
                    rest.startsWith("suivante") || rest.startsWith("suivant") ||
                        rest.startsWith("d apres") -> openSection("live")
                    rest.startsWith("precedente") || rest.startsWith("precedent") -> openSection("live")
                    rest.startsWith("numero") ->
                        openChannel(digits(rest).ifBlank { rest.removePrefix("numero").trim() })
                    else -> openChannel(rest)
                }
            }
            first == "numero" -> openChannel(digits(rest).ifBlank { rest })
            first == "suivante" || first == "suivant" ||
                first == "precedente" || first == "precedent" -> openSection("live")

            // 7. Ouvrir une section (mot seul)
            t.contains("parametre") || t.contains("reglage") || t.contains("option") -> openSection("settings")
            t.contains("favori") -> openSection("favorites")
            first == "film" || first == "films" || first == "cinema" -> openSection("movie")
            first == "serie" || first == "series" -> openSection("series")
            first == "direct" || first == "tv" || first == "television" ||
                first == "live" || first == "guide" -> openSection("live")

            // 8. Par defaut : recherche
            else -> openSearch(spoken.trim())
        }
    }

    // ---------- Actions ----------
    private fun openBrowse(kind: String, query: String, title: String) {
        Session.browseTitle = title
        val i = Intent(this, BrowseActivity::class.java).putExtra("kind", kind)
        if (query.isNotBlank()) i.putExtra("voiceQuery", query)
        startActivity(i)
    }

    private fun openChannel(name: String) {
        if (name.isBlank()) { openSection("live"); return }
        toast("Chaine : $name")
        // "voicePlay" : Browse charge toutes les chaines, trouve la correspondance et LANCE
        // directement la chaine (repli sur la liste filtree si introuvable).
        Session.browseTitle = "TV"
        startActivity(
            Intent(this, BrowseActivity::class.java)
                .putExtra("kind", "live")
                .putExtra("voicePlay", name)
        )
    }

    private fun openSearch(query: String) {
        if (query.isBlank()) { openSection("movie"); return }
        toast("Recherche : $query")
        openBrowse("movie", query, "Recherche")
    }

    private fun openSection(kind: String) {
        val isNew = ThemePref.isNew(this)
        when (kind) {
            "movie" -> if (isNew) start(NewMoviesActivity::class.java) else openBrowse("movie", "", "Films")
            "series" -> if (isNew) start(NewSeriesActivity::class.java) else openBrowse("series", "", "Series")
            "live" -> if (isNew) start(NewLiveActivity::class.java) else openBrowse("live", "", "TV")
            "favorites" -> openBrowse("favorites", "", "Favoris")
            "settings" -> start(SettingsActivity::class.java)
        }
    }

    private fun switchServer(name: String) {
        if (name.isBlank()) { toast("Dites le nom du serveur."); return }
        val q = norm(name)
        val pl = Session.playlists.firstOrNull { norm(it.nom) == q }
            ?: Session.playlists.firstOrNull { norm(it.nom).contains(q) || q.contains(norm(it.nom)) }
        if (pl == null) { toast("Serveur introuvable : $name"); return }
        Session.current = pl
        toast("Serveur : ${pl.nom}")
        relaunchHome()
    }

    private fun applyColor(text: String) {
        val gris = text.contains("gris") || text.contains("fonce")
        val base = when {
            text.contains("rouge") -> "red"
            text.contains("bleu") -> "blue"
            text.contains("vert") -> "green"
            text.contains("orange") -> "orange"
            text.contains("violet") || text.contains("mauve") -> "purple"
            text.contains("cyan") || text.contains("turquoise") -> "cyan"
            text.contains("rose") -> "pink"
            else -> ""
        }
        if (base.isBlank()) { toast("Couleur non reconnue."); return }
        val id = base + if (gris) "_gray" else "_black"
        val palette = ColorThemePref.ALL.firstOrNull { it.id == id }
            ?: ColorThemePref.ALL.firstOrNull { it.id.startsWith(base) }
        if (palette == null) { toast("Couleur non reconnue."); return }
        ColorThemePref.set(this, palette.id)
        toast("Couleur : ${palette.label}")
        relaunchHome()
    }

    private fun applyThemeSwitch(text: String) {
        when {
            text.contains("class") -> { ThemePref.set(this, ThemePref.CLASSIC); toast("Theme : Classique") }
            text.contains("new") || text.contains("tivi") || text.contains("nouveau") -> {
                ThemePref.set(this, ThemePref.NEWTIVI); toast("Theme : NewTivi")
            }
            else -> { toast("Theme non reconnu."); return }
        }
        relaunchHome()
    }

    private fun relaunchHome() {
        val cls = if (ThemePref.isNew(this)) NewLiveActivity::class.java else HomeActivity::class.java
        startActivity(
            Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    private fun start(cls: Class<*>) { startActivity(Intent(this, cls)) }

    private fun digits(s: String): String = s.filter { it.isDigit() }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
            .replace("\\p{Mn}".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object { private const val REQ = 4711 }
}
