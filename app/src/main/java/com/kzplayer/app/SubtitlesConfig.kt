package com.kzplayer.app

// ============================================================================
//  BASE DE SOUS-TITRES EXTERNE MULTILANGUE (OpenSubtitles.org, par IP)
// ============================================================================
//  Les sous-titres en ligne fonctionnent SANS cle API : le quota est compte
//  par appareil (par IP), donc chaque utilisateur du player a son propre
//  quota. C'est ce qu'il faut quand le player est partage entre plusieurs
//  personnes (pas de quota commun qui se vide d'un coup).
//
//  Rien a configurer ici. On peut juste ajuster la liste des langues.
// ============================================================================
object SubtitlesConfig {
    // Langues proposees lors de la recherche en ligne.
    // Codes ISO 639-2 (3 lettres), separes par des virgules :
    //   fre = Francais, eng = Anglais, spa = Espagnol, ara = Arabe,
    //   por = Portugais, ger = Allemand, ita = Italien
    const val SUBLANGUAGE_IDS = "fre,eng,spa,ara,por,ger,ita"
}
