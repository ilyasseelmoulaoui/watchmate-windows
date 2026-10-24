# Spécification : agrégateur multimédia modulaire (nom à définir)

> Version 1 · 2026-10-19 · rédigée en « salle blanche » (guide §15) à partir de `CONSTATS.md` seul.
> Ce document décrit ce que le produit doit faire, pas la façon dont le produit analysé est programmé. Il ne reprend ni code, ni texte d'interface, ni visuel, ni marque, ni identifiant technique, ni adresse de ce produit.
> Aucun code produit à ce stade.

**Légende** (colonne « Base » ou marque entre crochets)

| Marque | Sens |
|---|---|
| **C** | Constaté : la fonction existe dans le produit analysé. Renvoi à la question Q1–Q5 des constats, ou « démarrage » pour ce qui a été vu au lancement de l'application (écran d'accueil, journaux). Ne dit rien de notre implémentation. |
| **I** | Inféré : déduit ou supposé à partir d'indices (noms, chaînes, logique). Non vérifié, à confirmer avant de s'y fier. |
| **Choix** | Décision de conception propre à notre produit. Modifiable sans nouvelle analyse. |

Couverture : les permissions et la nature technique sont établies. Les modules, la lecture et le réseau ne sont connus qu'en partie. Le contrat des modules (§5.2), le relais local (§5.4) et les services en ligne (§5.5) reposent donc surtout sur **I**.

---

## 1. Objectif et utilisateurs

### 1.1 Objectif
Une application Android qui réunit en un seul endroit des catalogues de films et de séries, leurs fiches, leurs sources de lecture et leurs sous-titres. Tout ce contenu est fourni par des **modules** que l'utilisateur choisit d'installer. L'application lit ensuite ces sources, quelle que soit leur nature.

- Le contenu vient des modules, pas de l'application. **[C Q2, Q3]**
- L'application ne fournit et ne recommande aucune vidéo elle-même. **[Choix]**
- Les sources qui ne sont pas lisibles directement (pair-à-pair, NZB, archives) sont préparées par un **relais local** qui tourne sur l'appareil. **[C Q3 pour le tri des sources ; I pour le passage par le relais]**

### 1.2 Personas **[I : tous]**

| Persona | Besoin principal | Attentes |
|---|---|---|
| Spectateur occasionnel | Lancer ou reprendre un titre en quelques gestes | Accueil avec reprise, recherche, lecture immédiate, lecture en arrière-plan et en mini-fenêtre |
| Utilisateur avancé | Étendre et régler ses sources | Installer un module par son adresse, choisir le moteur de lecture, passer la main à un autre lecteur, régler les sous-titres |
| Foyer partagé | Séparer les usages sur un même compte | Profils distincts, modules et bibliothèque propres à chaque profil, verrouillage possible |
| Développeur de modules | Publier un module utilisable par tous | Contrat stable et documenté, erreurs explicites, aucune autorisation préalable |

---

## 2. Fonctionnalités

### 2.1 MVP

| ID | Fonction | Base |
|---|---|---|
| F1 | **Accueil** : rangée « reprendre », rangées de catalogues par type de contenu (films, séries), recherche | C démarrage |
| F2 | **Exploration** des catalogues des modules installés, avec filtres (type, catalogue) | C démarrage (onglet) ; filtres I |
| F3 | **Fiche d'un titre** : informations, liste des vidéos (épisodes), sources proposées par les modules, avec le module d'origine de chaque source | C Q3 (fiche et sources demandées aux modules) ; écran I |
| F4 | **Lecture intégrée** des sources directes et des vidéos de plateforme publique, avec sous-titres, lecture en arrière-plan avec notification de contrôle, et mini-fenêtre (image dans l'image) | C Q1, Q3 |
| F5 | **Lecteur externe** : transmettre une source à une autre application de lecture installée | C Q3 |
| F6 | **Bibliothèque** : ajouter ou retirer un titre, mémoriser la progression, reprendre | C démarrage (onglet, rangée) ; règles I |
| F7 | **Modules** : liste filtrable, fiche d'un module, installation depuis la liste, installation par adresse, retrait | C Q2 ; retrait I |
| F8 | Un module de fiches par défaut, installé d'office, pour que l'accueil ne soit jamais vide | I |
| F9 | Un profil unique créé d'office ; le modèle de données prévoit déjà plusieurs profils | Choix |

### 2.2 Plus tard

| ID | Fonction | Base |
|---|---|---|
| L1 | **Relais local** pour les sources pair-à-pair, NZB et archives | C Q3 (tri) ; fonctionnement I |
| L2 | Ouverture depuis un lien magnet, un fichier .torrent ou un lien propre au produit | C Q1 |
| L3 | Plusieurs moteurs de lecture intégrés, au choix de l'utilisateur | C Q3 (trois moteurs + un lecteur de plateforme) ; règle de choix I |
| L4 | Compte (inscription, connexion) et synchronisation entre appareils | C Q4 (service de compte), C démarrage (session ouverte) ; synchronisation I |
| L5 | Profils multiples, modules propres à chaque profil, verrouillage par code | C Q2 (gestion des profils) ; par profil et code I |
| L6 | Associer un nouvel appareil au compte en scannant un code QR | I |
| L7 | Annonces dans l'application, pouvant proposer l'installation d'un module | C Q2 |
| L8 | Sous-titres venant d'un service tiers spécialisé | C Q4 (adresse relevée) ; usage I |
| L9 | Liaison avec un service tiers de suivi de visionnage | I |
| L10 | Appréciations sur les titres | I |
| L11 | Export de ses données personnelles, obligatoire dès L4 | I ; obligation Choix |
| L12 | Conversions par le relais : flux découpé en segments pour les lecteurs qui l'exigent, sous-titres convertis en format texte standard | I |
| L13 | Téléchargement pour lecture hors ligne | I (non étayé par les constats) |
| L14 | Autres plateformes (web, ordinateur) | I (adresses d'une version web relevées) |

### 2.3 Non-buts **[Choix : tous]**
- Héberger, fournir, indexer ou recommander des vidéos : le produit affiche ce que proposent les modules installés par l'utilisateur.
- Livrer par défaut un module donnant accès à des contenus dont les droits ne sont pas acquis.
- Contourner un DRM, une authentification ou une protection d'un service tiers.
- Reprendre le code, les textes, les visuels, la charte ou la marque du produit analysé.
- Reproduire son architecture interne (langages, moteur d'exécution embarqué, format des messages) : l'architecture sera décidée en conception.
- Publicité, identifiant publicitaire, SDK de réseau social.
- Exposer le relais aux autres appareils du réseau local.

---

## 3. Parcours utilisateur

### 3.1 Écrans
- **Navigation principale à cinq onglets [C démarrage]** : Accueil, Exploration, Bibliothèque, Modules, Réglages (noms de travail).
- **Écrans secondaires** : fiche d'un titre **[I]**, liste des sources **[I]**, lecteur **[C Q3]**, fiche d'un module **[C Q2]**, annonces **[C Q2]**, gestion des profils **[C Q2]**, connexion et inscription **[I]**, introduction au premier lancement **[I]**, réglages détaillés : lecture, sous-titres, relais, compte, avancé **[I]**.

### 3.2 Parcours

**P1. Premier lancement**
1. Écran d'introduction. **[I]**
2. Demande d'autorisation d'afficher des notifications, si elle n'est pas encore accordée. **[C Q1]**
3. Accueil rempli par le module de fiches par défaut (F8). **[I]**

**P2. Trouver et regarder un titre**
1. Depuis une rangée de l'accueil, la recherche ou l'exploration, ouvrir la fiche du titre. **[C démarrage ; fiche I]**
2. Pour une série, choisir l'épisode. Un épisode a son propre identifiant, distinct de celui de la série. **[C Q3]**
3. Voir les sources proposées par les modules, chacune avec son module d'origine. **[C Q3 pour le module d'origine ; présentation I]**
4. Choisir une source. Le tri de la règle R5 décide : lecture directe, passage par le relais, ou ouverture hors de l'application.
5. Le lecteur s'ouvre et demande la fiche, les sources et les sous-titres du titre en cours. **[C Q3]**
6. Si l'on quitte avant la fin, la position est gardée et le titre apparaît dans « reprendre ». **[I]**

**P3. Installer un module depuis la liste**
1. Onglet Modules, liste filtrable, puis fiche du module. **[C Q2]**
2. Installer. Le succès n'est affiché qu'après confirmation (R2). **[C Q2]**
3. Les catalogues et ressources du module apparaissent dans l'accueil, l'exploration et les fiches. **[I]**
- Variante : installer depuis une annonce. **[C Q2]**

**P4. Installer un module par son adresse**
1. Ouvrir la gestion des profils et choisir un profil. **[C Q2]** Au MVP, avec un profil unique, la saisie se fait depuis l'onglet Modules. **[Choix]**
2. Saisir l'adresse du module ; la règle R1 s'applique. **[C Q2]**
3. Le module est installé pour ce profil. **[C Q2 : profil transmis ; effet limité au profil I]**

**P5. Passer la main à un autre lecteur**
1. Depuis la liste des sources ou le lecteur, choisir « ouvrir avec une autre application ». **[C Q3]**
2. Si la source exige le relais, obtenir d'abord une adresse lisible (R7). **[I]**
3. Transmettre l'adresse. Si aucune application compatible n'est installée, le signaler. **[I]**

**P6. Continuer à regarder hors du lecteur**
- Quitter le lecteur pendant la lecture : la vidéo continue en mini-fenêtre, ou le son continue avec une notification de contrôle. **[C Q1]**

**P7. Ouvrir un lien magnet ou un fichier .torrent (lot L2)**
1. Le système propose le produit pour ouvrir le lien ou le fichier. **[C Q1]**
2. Le produit lance la lecture par le relais, ou montre d'abord une fiche. **[I]**

---

## 4. Modèle de données

### 4.1 Entités

| Entité | Champs principaux | Base |
|---|---|---|
| Profil | identifiant, nom, modules installés, préférences de lecture, verrou (option) | C Q2 (profils ; identifiant transmis à l'installation) ; champs I |
| Compte (L4) | identifiant, moyen de connexion, profils, session | I |
| Module | adresse, identité (nom, version, description), ressources proposées (catalogue, fiche, sources, sous-titres), types de contenu, catalogues déclarés, origine de l'installation (défaut, liste, adresse, annonce) | C Q3 (adresse ; ressources fiche, sources, sous-titres) ; catalogue et identité I |
| Titre | identifiant, type (film, série…), nom, visuels, résumé, vidéos, module qui a fourni la fiche | C Q3 (type + identifiant), C démarrage (films, séries) ; reste I |
| Vidéo | identifiant propre, titre parent, position (saison, numéro) | C Q3 (identifiant propre) ; position I |
| Source | type (§4.2), adresse ou descripteur, libellé, module d'origine, attribut calculé « exige le relais » | C Q3 |
| Piste de sous-titres | langue, adresse, origine (module ou service tiers) | C Q3 (sous-titres demandés) ; champs I |
| Entrée de bibliothèque | profil, titre, date d'ajout, progression (vidéo, position, durée), dernière lecture | C démarrage (bibliothèque, reprise) ; champs I |
| Session de lecture | titre, vidéo, source, module de sources, module de fiche, moteur, position, état | C Q3 (paramètres d'ouverture) ; moteur et état I |
| Annonce | contenu, module proposé (option) | C Q2 ; champs I |
| Relais local (unique) | état, adresse locale simple, adresse locale chiffrée | C démarrage (deux adresses annoncées) |

### 4.2 Types de source **[C Q3, sauf mention]**

| Type | Traitement |
|---|---|
| Adresse directe | lecteur intégré ou externe |
| Vidéo d'une plateforme publique | lecteur propre à cette plateforme |
| Lien externe | ouvert hors du lecteur **[I]** |
| Page de lecture intégrée | relais d'après la règle constatée ; rôle exact inconnu **[I]** |
| Pair-à-pair (magnet, .torrent) | relais |
| NZB | relais |
| Archive (ZIP, RAR, 7z, TAR, TGZ) | relais |

### 4.3 Statuts et transitions

**Module** : C Q2 pour l'attente de confirmation ; le reste I.
Disponible → Installation demandée → Installé, ou → Échec → Disponible (nouvel essai). Installé → Retiré.

**Session de lecture** : I, sauf la préparation (C Q3) et la mini-fenêtre ou l'arrière-plan (C Q1).
Préparation (fiche, sources, sous-titres) → Résolution par le relais (si la source l'exige) → Prête → En lecture ⇄ En pause → Terminée.
En lecture → Mini-fenêtre ou Arrière-plan → En lecture. En lecture → Transférée (lecteur externe). Tout état → Erreur.

**Relais local** : C démarrage pour le lancement ; le reste I.
Arrêté → Démarrage → Prêt → Arrêté. Démarrage ou Prêt → En erreur → Démarrage.

**Entrée de bibliothèque** : I.
Absente → Ajoutée → En cours → Terminée ; tout état → Retirée. La rangée « reprendre » montre les entrées En cours.

**Profil** : I.
Créé → Actif ⇄ Inactif → Supprimé. Verrouillé en option.

**Compte (L4)** : I.
Déconnecté → Connecté → Session expirée → Connecté ou Déconnecté. Mode invité : question ouverte O6.

---

## 5. Intégrations externes et API conceptuelle

### 5.1 Principe
Dans le produit analysé, l'interface ne contacte pas les modules elle-même. Elle envoie des commandes à un cœur métier séparé, par un point d'entrée unique, et attend des événements de résultat. **[C Q2, Q5]**
Nous reprenons l'idée (interface → cœur métier → modules et relais), pas la technique. **[Choix]**

### 5.2 Contrat d'un module (vu par l'application)
Chaque demande vise un module précis, désigné par son adresse. Elle se décrit par trois éléments : la ressource voulue, le type de contenu, l'identifiant. **[C Q3]**
Le transport, le format des réponses, la pagination, les filtres et les erreurs n'ont pas été constatés. **[I]**

| Opération | Entrée | Sortie | Base |
|---|---|---|---|
| Décrire | adresse du module | identité, ressources proposées, types, catalogues | I |
| Catalogue | type, catalogue, filtres, page | liste de titres résumés | I |
| Fiche | type, identifiant du titre | titre détaillé avec ses vidéos | C Q3 (forme) ; contenu I |
| Sources | type, identifiant de vidéo | liste de sources | C Q3 (forme) ; contenu I |
| Sous-titres | type, identifiant de vidéo | liste de pistes | C Q3 (forme) ; contenu I |

### 5.3 Commandes et événements du cœur métier

| Commande | Résultat attendu | Base |
|---|---|---|
| Installer un module (description ou adresse, profil) | événement « module installé » ou « installation échouée » | C Q2 (commande, attente du succès, profil transmis lors d'une installation par adresse) ; échec I |
| Lister les modules (filtres) | liste des modules | C Q2 |
| Retirer un module | événement « module retiré » | I |
| Charger l'accueil, un catalogue, une recherche | listes de titres | I |
| Ouvrir une fiche | titre détaillé et ses sources | I |
| Ouvrir la lecture (source, module de sources, module de fiche, type, identifiant, identifiant de vidéo) | état de lecture : fiche, sources, sous-titres | C Q3 |
| Changer de moteur de lecture | état de lecture mis à jour | I (une commande de ce genre existe ; sa règle est inconnue) |
| Enregistrer la progression | entrée de bibliothèque mise à jour | I |

### 5.4 Relais local (lot L1)
Contraintes constatées : il démarre au lancement de l'application, dans un processus séparé de l'interface. Il n'écoute que sur l'appareil, avec une adresse simple et une adresse chiffrée. **[C Q1, démarrage]**

| Opération | Entrée | Sortie | Base |
|---|---|---|---|
| Préparer un flux | source qui exige le relais | adresse locale lisible par un lecteur | I |
| Adresse pour lecteur externe | source | adresse transmissible à une autre application | I |
| Convertir des sous-titres | adresse d'une piste | piste en format texte standard | I |
| Ouvrir ou fermer un flux segmenté | session de lecture | adresse du flux ; libération des ressources à la fermeture | I |
| État | aucune | arrêté, prêt ou en erreur | I |

### 5.5 Services en ligne du produit (lot L4 et suivants) **[I : tous, déduits d'adresses relevées]**
Compte et synchronisation · association d'appareil par code QR · appréciations · export des données · certificat pour l'adresse chiffrée du relais · module de fiches par défaut et hébergement de ses visuels.

### 5.6 Services tiers

| Rôle | Base | Dans notre produit |
|---|---|---|
| Plateforme vidéo publique | C Q3 | MVP, par son lecteur officiel **[Choix]** |
| Autres lecteurs installés sur l'appareil | C Q3 | MVP |
| Service de sous-titres | C Q4 (adresse relevée) | L8 |
| Suivi de visionnage | I | L9 |
| Base d'informations cinéma (suggestions de recherche, affiches) | C Q4 (adresses relevées) ; usage I | question ouverte O13 |
| Analytique et rapports de plantage | C Q1 (SDK déclarés) ; usage I | rapports de plantage anonymes ; analytique optionnelle, avec accord **[Choix]** |
| Publicité, réseau social, identifiant publicitaire | C Q1 (SDK et permission déclarés) ; usage I | exclus (§2.3) |

---

## 6. Règles métier

| # | Règle | Base |
|---|---|---|
| R1 | Adresse de module saisie : espaces de début et de fin retirés. Refus si elle est vide ou si aucun profil n'est sélectionné | C Q2 |
| R2 | Un module n'est affiché comme installé qu'après l'événement de succès. Sans réponse dans le délai (R9) : échec affiché, rien n'est installé | C Q2 (attente) ; délai Choix |
| R3 | Un module n'est interrogé que pour les ressources et les types qu'il déclare | I |
| R4 | Chaque source garde son module d'origine. Le lecteur reçoit l'adresse du module de sources et celle du module de fiche | C Q3 |
| R5 | Tri des sources : adresse directe, vidéo de plateforme publique et lien externe se passent du relais ; tous les autres types passent par lui. Au MVP, sans relais, ces autres sources sont affichées comme non lisibles dans cette version | C Q3 (tri) ; passage effectif I ; MVP Choix |
| R6 | À l'ouverture du lecteur : demander la fiche, les sources et les sous-titres du titre ou de l'épisode en cours | C Q3 |
| R7 | Lecteur externe et source qui exige le relais : obtenir d'abord une adresse lisible auprès du relais | I |
| R8 | Relais démarré au lancement, isolé de l'interface, à l'écoute de l'appareil seulement. Un démarrage à la demande reste possible (O10) | C démarrage, Q1 ; option Choix |
| R9 | Requêtes réseau : délai maximal de 30 s ; réponses gardées en cache sur le disque | C démarrage (valeur relevée) ; reprise Choix |
| R10 | Notifications : demandées au lancement tant qu'elles ne sont pas accordées. En cas de refus, la lecture fonctionne toujours | C Q1 (demande vue au lancement) ; fréquence et refus I |
| R11 | « Reprendre » : un titre y figure dès qu'une lecture s'arrête avant la fin, et en sort une fois terminé | I ; seuil de fin à définir |
| R12 | Cas d'erreur : module injoignable ou réponse invalide, aucune source, source illisible, relais indisponible, aucun lecteur externe, lien entrant invalide. Pour chacun : message clair, nouvel essai possible, aucune perte de bibliothèque | I ; traitement Choix |

---

## 7. Exigences non fonctionnelles

| Domaine | Exigence | Base |
|---|---|---|
| Plateforme | Android 12L (API 32) minimum, version cible récente | C Q1 (mêmes bornes dans le produit analysé) ; Choix |
| Lecture | Arrière-plan avec notification de contrôle ; mini-fenêtre ; appareil maintenu éveillé pendant la lecture | C Q1 |
| Robustesse | Une panne du relais ne fait pas tomber l'interface | C Q1 (processus séparé) ; formulation Choix |
| Performance | Cache disque des réponses ; délai réseau borné ; les trois demandes du lecteur partent en parallèle | C démarrage (cache, délai) ; parallélisme I |
| Sécurité : trafic | Trafic non chiffré permis seulement vers l'appareil lui-même et vers les modules que l'utilisateur accepte en connaissance de cause | C Q1 (le produit analysé le permet partout) ; restriction Choix |
| Sécurité : relais | Écoute sur l'appareil uniquement ; adresse chiffrée pour les lecteurs qui l'exigent ; jamais ouvert au réseau local | C démarrage ; mécanisme du certificat I ; Choix |
| Sécurité : modules | Les réponses des modules sont des données, jamais du code exécuté. Les pages de lecture intégrées s'affichent dans un espace isolé | Choix |
| Sécurité : service de lecture | Le service de lecture accessible aux autres applications (contrôles média) n'accepte que des commandes de lecture | C Q1 (service exporté) ; Choix |
| Vie privée | Pas d'identifiant publicitaire ; analytique désactivée par défaut ; export et suppression des données dès qu'il y a des comptes | C Q1 (SDK déclarés dans le produit analysé) ; Choix |
| Langues | Interface traduisible ; français et anglais au MVP | C (paquet de langue séparé récupéré) ; Choix |
| Accessibilité | Lecteur d'écran, tailles de texte, contrastes | Choix (non observé) |

---

## 8. Questions ouvertes

| # | Question | Nature | Pour trancher |
|---|---|---|---|
| O1 | Être compatible avec un écosystème de modules existant ? | **Décidé : oui** (2026-10-19, à confirmer sur les conditions d'usage) | Partir de la documentation publique de cet écosystème (ou de ses composants open source, licence vérifiée), pas de l'analyse : le format exact n'a pas été constaté. O2 se résout alors par cette documentation |
| O2 | Format exact du contrat module : transport, réponses, pagination, filtres, erreurs | Inconnu | O1, ou conception propre |
| O3 | Règle de choix du moteur de lecture : selon le format, un réglage, le profil ? | Inconnu | Décision produit |
| O4 | Fonctionnement du relais : adresses produites, conversion en segments, libération des ressources | Inconnu | Conception propre |
| O5 | Modules propres à chaque profil, ou partagés ? L'installation par adresse et celle depuis la liste ont-elles le même effet ? | Inconnu | Décision produit |
| O6 | Mode invité, sans compte ? | Inconnu | Décision produit |
| O7 | Synchronisation : quoi (bibliothèque, progression, modules, réglages) et comment résoudre les conflits ? | Inconnu | Décision produit |
| O8 | Profils : verrouillage par code, restrictions de contenu ? | Inconnu | Décision produit |
| O9 | Téléchargement hors ligne : utile ? | Non étayé | Décision produit |
| O10 | Relais au MVP ou au lot 2 ? Démarrage au lancement ou à la demande (batterie) ? | Décision produit | Mesurer la part des sources qui exigent le relais dans les modules visés ; si elle domine, remonter L1 dans le MVP |
| O11 | Lien magnet ouvert depuis l'extérieur : lecture directe ou fiche d'abord ? | Inconnu | Décision produit |
| O12 | Responsabilité vis-à-vis des modules tiers : avertissement, modération de la liste, signalement | Décision juridique | Avis juridique |
| O13 | Base d'informations cinéma tierce : nécessaire ? À quelles conditions d'usage ? | Décision produit | Lire ses conditions d'usage |
