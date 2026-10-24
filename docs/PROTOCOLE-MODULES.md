# Protocole de modules : notes d'implémentation

Source unique : la documentation publique du SDK de modules de Stremio (dépôt `Stremio/stremio-addon-sdk`, dossier `docs/`, licence MIT). Ce document résume le contrat avec nos mots ; aucun résultat de l'analyse du produit n'y est utilisé. À revérifier sur la source avant toute release.

## Transport
- Un module est une adresse HTTPS qui sert `/manifest.json` ; toutes les routes doivent autoriser CORS.
- HTTPS obligatoire, sauf pour `127.0.0.1`. Notre client applique la même règle, avec une liste d'hôtes acceptés explicitement par l'utilisateur (SPEC §7, sécurité du trafic).
- Pas de règle documentée pour « ressource introuvable » : les exemples renvoient un objet ou une liste vide. Le client traite un statut HTTP d'erreur comme un échec et une liste vide comme « aucun résultat ».

## Routes
| Ressource | Route |
|---|---|
| Manifeste | `/manifest.json` |
| Catalogue, fiche, sources, sous-titres | `/{ressource}/{type}/{id}.json` |
| Avec arguments supplémentaires | `/{ressource}/{type}/{id}/{arguments}.json` |

`{arguments}` est une chaîne de requête encodée en pourcentage, par exemple `search=game%20of%20thrones&skip=100`. Un identifiant de vidéo de série contient les numéros de saison et d'épisode (`tt0898266:9:17`).

## Manifeste
- Obligatoires : `id`, `name`, `description`, `version`, `resources`, `types`, `catalogs`.
- `resources` : un texte (hérite des `types` et `idPrefixes` du manifeste) ou un objet `{name, types, idPrefixes?}` (sans `idPrefixes` : tous les identifiants). Les préfixes ne filtrent jamais les catalogues. Implémenté dans `Manifest.supports` (règle R3).
- Catalogue : `type`, `id`, `name`, `extra` (liste de `{name, isRequired, options, optionsLimit}`).

## Réponses
- Catalogue : `{ "metas": [...] }` ; page de 100 éléments, moins de 100 signifie la fin.
- Fiche : `{ "meta": {...} }`. Les exemples montrent parfois un tableau : seul l'objet est accepté, un objet vide donne « pas de fiche ».
- Sources : `{ "streams": [...] }`, de la meilleure qualité à la moins bonne. Une source porte au moins un champ parmi `url`, `ytId`, `infoHash`, `nzbUrl`, `externalUrl`, `rarUrls`, `zipUrls`, `7zipUrls`, `tgzUrls`, `tarUrls`. `fileIdx` désigne le fichier dans un torrent ou une archive. `behaviorHints.proxyHeaders.request` : en-têtes que le lecteur doit envoyer.
- Sous-titres : `{ "subtitles": [...] }` avec `url` et `lang` ; `id` est lu de façon tolérante.

## Ambiguïtés de la documentation (comportement du client)
- Sous-titres : la doc décrit deux formes pour l'identifiant et les arguments. Le client envoie l'identifiant de vidéo et accepte des arguments libres (`videoHash`, `videoSize`, `filename`).
- Tous les champs inconnus sont ignorés ; les valeurs `null` prennent la valeur par défaut.

## Non couvert pour l'instant
Pages de configuration des modules, catalogues de modules (`addonCatalogs`), télévision en direct (EPG), recherche multi-modules, mise en cache selon `cacheMaxAge`.
