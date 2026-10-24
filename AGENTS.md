# AGENTS.md — WatchMate Windows

Client bureau Windows de WatchMate (Kotlin, Compose Multiplatform Desktop). Même produit que l’app Android [`watchmate`](https://github.com/ilyasseelmoulaoui/watchmate) : mêmes règles clean-room, même backend Supabase, même protocole de modules.

Lire en premier : `docs/HANDOFF.md`, puis `SPEC.md` (objectifs produit partagés).

## Modules Gradle
- `:core` — logique pure (catalogues, modules, bibliothèque, sync, party protocol…) — alignée sur Android.
- `:relay` — serveur HTTP local JVM (archives / torrents) — alignée sur Android.
- `:desktop` — UI Compose Desktop, lecteur (VLC prévu), libtorrent natif Windows.

## Règles dures
- Clean-room : pas de code, texte, visuel ou marque du produit qui a inspiré la spécification. L’app ne fournit aucun contenu.
- Ne pas proposer ni coder en dur des modules orientés piratage. Suggestions = `core/.../SuggestedModules.kt`.
- Jamais de clé Supabase `service_role`. Seulement la clé anon (dans `gradle.properties` / `BuildConfig`).
- Commit / push seulement si l’utilisateur le demande. Branche `main`.
- `:core` et `:relay` restent purs JVM (pas d’imports Android / Compose Desktop).
- Chaînes UI : français d’abord, anglais ensuite (pour l’instant dans le code Compose ; ressources plus tard).
- Pas de commentaires sauf si le POURQUOI n’est pas évident.

## Objectif fonctionnel
Même périmètre que WatchMate Android : modules, accueil, exploration, fiche, bibliothèque, lecture (direct + relais), compte / sync, réglages (thème, langue), regarder ensemble (sync + invitations), chat / voix si faisable sur bureau.
