package com.kzplayer.app

import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat

// Resout une couleur pilotee par le theme (attribut kz*) depuis le contexte fourni.
// Le contexte doit etre celui d'une Activity (theme applique) ; les vues d'adapter
// utilisent view.context, qui est bien le contexte themise de l'ecran.
object KzColors {
    fun resolve(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
        } else {
            ContextCompat.getColor(ctx, R.color.accent)
        }
    }
    fun accent(ctx: Context): Int = resolve(ctx, R.attr.kzAccent)
}
