package com.kzplayer.app

import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

// Cable la barre de navigation laterale (logo en haut) partagee par les ecrans NewTivi.
object NavHelper {
    fun setup(act: AppCompatActivity, active: String) {
        wire(act, R.id.navGuide, R.id.navGuideImg, active == "guide") { go(act, NewGuideActivity::class.java, active, "guide") }
        wire(act, R.id.navMovies, R.id.navMoviesImg, active == "movies") { go(act, NewMoviesActivity::class.java, active, "movies") }
        wire(act, R.id.navSeries, R.id.navSeriesImg, active == "series") { go(act, NewSeriesActivity::class.java, active, "series") }
        wire(act, R.id.navSearch, R.id.navSearchImg, false) {
            act.findViewById<EditText?>(R.id.searchEt)?.let { it.requestFocus() }
        }
        wire(act, R.id.navSettings, R.id.navSettingsImg, false) {
            act.startActivity(Intent(act, SettingsActivity::class.java))
        }
    }

    private fun wire(act: AppCompatActivity, containerId: Int, imgId: Int, activeState: Boolean, onClick: () -> Unit) {
        val c = act.findViewById<View?>(containerId) ?: return
        c.isSelected = activeState
        c.setOnClickListener { onClick() }
        val iv = act.findViewById<ImageView?>(imgId)
        iv?.setColorFilter(ContextCompat.getColor(act, if (activeState) R.color.text else R.color.muted))
    }

    private fun go(act: AppCompatActivity, cls: Class<*>, active: String, tag: String) {
        if (active == tag) return
        act.startActivity(
            Intent(act, cls)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
