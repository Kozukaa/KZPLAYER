# KZ Player V112 - Paquet complet pour build APK sur GitHub (100% gratuit)

Ce paquet contient TOUT ce qu'il faut pour compiler ton APK signe :
- ton code V112 (inchange, sauf la signature mise a jour pour ta nouvelle cle)
- les fichiers de build Gradle (qui manquaient dans le zip d'origine)
- le workflow GitHub deja pret : .github/workflows/build-apk-signed.yml

=====================================================
ETAPE 1 - Mettre ces fichiers sur un depot GitHub
=====================================================
1. Cree un compte sur github.com
2. New repository -> nom: kzplayer -> Private -> Create
3. Envoie tout le contenu de ce dossier dans le depot.

=====================================================
ETAPE 2 - Ajouter les 4 SECRETS (obligatoire)
=====================================================
Sur ton depot GitHub :
Settings -> Secrets and variables -> Actions -> New repository secret

Cree ces 4 secrets (noms EXACTS) :

  KEYSTORE_BASE64    = le contenu du fichier kzplayer-keystore-base64.txt
  KEYSTORE_PASSWORD  = KZplayer2026!
  KEY_ALIAS          = kzplayer
  KEY_PASSWORD       = KZplayer2026!

(Utilise le keystore kzplayer-release.jks que je t'ai donne. NE le perds pas.)

=====================================================
ETAPE 3 - Lancer le build
=====================================================
1. Onglet Actions -> workflow "Build KZ Player APK (signed)"
2. Run workflow -> Run workflow
3. Attends la coche verte.

=====================================================
ETAPE 4 - Recuperer l'APK
=====================================================
1. Clique sur le build termine.
2. En bas, section Artifacts -> telecharge "app-release-signed".
3. Dedans : app-release-signed.apk = ton application.

Signature attendue (nouvelle cle) :
E4288AF20EF2962943F4A7A0056CCB2661DE9D2838A311CDF4FA39BC6197BE6C

=====================================================
IMPORTANT
=====================================================
- La signature a change (nouvelle cle). Toute personne ayant l'ancienne
  version doit desinstaller puis reinstaller la nouvelle.
- Rien d'autre n'a ete modifie : lecteur ExoPlayer, licence, Stalker,
  favoris, historique, protection R8 = identiques a la V112.
