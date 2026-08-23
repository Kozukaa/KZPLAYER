package com.kzplayer.app

import android.content.Context
import java.io.File

// v359 : vide le cache de l application (images, fichiers temporaires).
// Ne touche jamais aux reglages, a la licence, aux favoris ni a l historique.
object CacheCleaner {
    private fun sizeOf(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        val kids = f.listFiles() ?: return 0L
        var total = 0L
        for (k in kids) total += sizeOf(k)
        return total
    }

    private fun deleteInside(f: File?) {
        if (f == null || !f.exists()) return
        val kids = f.listFiles() ?: return
        for (k in kids) {
            try {
                if (k.isDirectory) { deleteInside(k); k.delete() } else k.delete()
            } catch (e: Exception) {}
        }
    }

    fun cacheBytes(ctx: Context): Long = sizeOf(ctx.cacheDir) + sizeOf(ctx.externalCacheDir)

    // Renvoie le nombre d octets reellement liberes.
    fun clear(ctx: Context): Long {
        val before = cacheBytes(ctx)
        deleteInside(ctx.cacheDir)
        deleteInside(ctx.externalCacheDir)
        val after = cacheBytes(ctx)
        return (before - after).coerceAtLeast(0L)
    }

    fun human(bytes: Long): String {
        if (bytes < 1024L) return bytes.toString() + " o"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format("%.0f Ko", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format("%.1f Mo", mb)
        return String.format("%.2f Go", mb / 1024.0)
    }
}
