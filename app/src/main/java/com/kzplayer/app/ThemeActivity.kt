package com.kzplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

// Sous-menu Parametres : choix du theme (Classique / NewTivi / Netflix).
class ThemeActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        val classicState = findViewById<TextView>(R.id.classicState)
        val newState = findViewById<TextView>(R.id.newState)
        val netflixState = findViewById<TextView>(R.id.netflixState)
        val cineNovaState = findViewById<TextView>(R.id.cineNovaState)
        val cur = ThemePref.get(this)
        // Le badge "Actuel" a un fond blanc (bg_theme_badge_current) ; on l'affiche
        // uniquement pour le theme actif, sinon on le cache pour ne pas laisser un
        // rectangle vide sur les 2 autres cartes.
        setStateBadge(classicState, cur == ThemePref.CLASSIC)
        setStateBadge(newState, cur == ThemePref.NEWTIVI)
        setStateBadge(netflixState, cur == ThemePref.NETFLIX)
        setStateBadge(cineNovaState, cur == ThemePref.CINENOVA)
        findViewById<View>(R.id.themeClassic).setOnClickListener { apply(ThemePref.CLASSIC) }
        findViewById<View>(R.id.themeNew).setOnClickListener { apply(ThemePref.NEWTIVI) }
        findViewById<View>(R.id.themeNetflix).setOnClickListener { apply(ThemePref.NETFLIX) }
        findViewById<View>(R.id.themeCineNova).setOnClickListener { apply(ThemePref.CINENOVA) }
        findViewById<View>(R.id.themeClassic).requestFocus()
    }

    private fun setStateBadge(tv: TextView, isCurrent: Boolean) {
        if (isCurrent) {
            tv.text = "\u2713 ACTUEL"
            tv.visibility = View.VISIBLE
        } else {
            tv.text = ""
            tv.visibility = View.GONE
        }
    }

    private fun apply(v: String) {
        ThemePref.set(this, v)
        startActivity(
            Intent(this, ThemePref.homeClass(this))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
