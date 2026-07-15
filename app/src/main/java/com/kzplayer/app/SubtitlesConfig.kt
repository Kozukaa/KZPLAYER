package com.kzplayer.app

// ============================================================================
//  BASE DE SOUS-TITRES EXTERNE MULTILANGUE (OpenSubtitles)
// ============================================================================
//  Pour activer les sous-titres en ligne (n'importe quel film / serie, meme
//  ceux qui ne sont PAS sous-titres a la base) :
//
//   1. Cree un compte GRATUIT sur https://www.opensubtitles.com
//   2. Va sur ton profil -> "API consumers" -> "New consumer"
//   3. Recopie la valeur "Api Key" et colle-la entre les guillemets ci-dessous.
//   4. Recompile l'app (git push -> Actions).
//
//  Sans cle : le bouton "Sous-titres" reste present mais indique qu'aucune cle
//  n'est configuree (aucun plantage). Rien d'autre n'est impacte.
// ============================================================================
object SubtitlesConfig {
    // >>> COLLE TA CLE OPENSUBTITLES ICI <<<
    const val OPENSUBTITLES_API_KEY = ""

    // Langues proposees lors de la recherche (codes ISO 639-1, separes par des virgules).
    const val LANGUAGES = "fr,en,es,ar,pt,de,it"
}
