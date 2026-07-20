package com.kzplayer.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// Applique la palette de couleurs choisie AVANT le rendu de chaque ecran.
// N'affecte que l'apparence ; la logique (lecteur, flux, licence) est inchangee.
// Le lecteur (PlayerActivity) n'herite volontairement pas de cette base : il garde
// son theme plein ecran par defaut.
open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        try { setTheme(ColorThemePref.styleRes(this)) } catch (e: Exception) {}
        super.onCreate(savedInstanceState)
    }
}
