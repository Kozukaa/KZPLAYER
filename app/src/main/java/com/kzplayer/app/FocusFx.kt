package com.kzplayer.app

import android.view.View

// v391 : effet de curseur (telecommande) net sur les boutons plats : la vue ciblee
// grandit legerement et passe au premier plan, en plus du fond accent du selecteur.
object FocusFx {
    fun apply(vararg views: View?) {
        for (v in views) {
            if (v == null) continue
            v.isFocusable = true
            v.isClickable = true
            v.setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) 1.07f else 1f)
                    .scaleY(if (hasFocus) 1.07f else 1f)
                    .setDuration(90).start()
                view.translationZ = if (hasFocus) 16f else 0f
            }
        }
    }
}
