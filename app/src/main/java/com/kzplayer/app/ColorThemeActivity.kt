package com.kzplayer.app

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

// Sous-menu Parametres : choix de la palette de couleurs (accent + fond).
// Construit dynamiquement a partir de ColorThemePref.ALL. N'affecte que l'apparence.
class ColorThemeActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dens = resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(KzColors.resolve(this@ColorThemeActivity, R.attr.kzBg))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(40), dp(40), dp(40))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "\u2190 Retour"
            setTextColor(ContextCompat.getColor(this@ColorThemeActivity, R.color.muted))
            textSize = 14f
            isClickable = true; isFocusable = true
            setPadding(0, 0, 0, dp(14))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "Couleurs"
            setTextColor(ContextCompat.getColor(this@ColorThemeActivity, R.color.text))
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Choisis la palette de l'application"
            setTextColor(ContextCompat.getColor(this@ColorThemeActivity, R.color.muted))
            textSize = 14f
            setPadding(0, dp(4), 0, dp(20))
        })

        val current = ColorThemePref.get(this)
        var focusTarget: View? = null
        for (p in ColorThemePref.ALL) {
            val row = buildRow(p, p.id == current) { dp(it) }
            row.setOnClickListener { applyTheme(p.id) }
            (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
            root.addView(row)
            if (p.id == current) focusTarget = row
        }
        setContentView(scroll)
        focusTarget?.requestFocus()
    }

    private fun buildRow(p: ColorThemePref.Palette, isCurrent: Boolean, dp: (Int) -> Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_tile)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(p.bg); cornerRadius = dp(8).toFloat(); setStroke(dp(1), 0x33FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        })
        row.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(p.accent); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(10) }
        })
        row.addView(TextView(this).apply {
            text = p.label
            setTextColor(ContextCompat.getColor(this@ColorThemeActivity, R.color.text))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(16) }
        })
        row.addView(TextView(this).apply {
            text = if (isCurrent) "\u2713 Actuel" else ""
            setTextColor(p.accent)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun applyTheme(id: String) {
        ColorThemePref.set(this, id)
        // Relance l'ecran d'accueil du theme d'interface actif pour appliquer partout.
        val cls = if (ThemePref.isNew(this)) NewLiveActivity::class.java else HomeActivity::class.java
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
