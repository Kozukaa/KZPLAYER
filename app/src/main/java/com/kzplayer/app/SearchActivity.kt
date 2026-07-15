package com.kzplayer.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// Ecran de recherche autonome RETIRE : la recherche multi-serveurs est desormais
// integree directement dans Films / Series (BrowseActivity). On conserve ici un
// stub inoffensif pour eviter toute erreur de compilation si d'anciennes references
// subsistent dans le depot. Il ne fait rien et se ferme immediatement.
class SearchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
