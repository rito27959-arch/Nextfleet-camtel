# NEXTFLEET CAMTEL — ARCHITECTURE COMPLÈTE ET PROMPT MAÎTRE

**Version de proposition : V5.0 de conception — 19 octobre 2026**  
**Fondation : cahier des charges NexFleet CAMTEL V4.0 (45 pages) et ses maquettes p. 17–18 ; extension de conception : parcours de 15 écrans, catalogue 3D et IA photo → véhicule.**  
**Statut : architecture et instructions de développement, non livraison de fonctionnalités déjà implémentées ni validation CAMTEL.**

## I. OBJECTIF ET PRINCIPES

Concevoir une plateforme complète de gestion du parc automobile pour CAMTEL, accessible sur **Windows et Linux avec JavaFX**, et sur **Android en Java natif**, avec une **API métier Java 21/Spring Boot**, des données dans PostgreSQL/PostGIS et une identité visuelle harmonisée avec le PDF de référence. Conserver et vérifier toutes les fonctions existantes avant leur remplacement ; ne jamais confondre l'état déclaré de l'ancienne application avec la réalisation effective des nouvelles fonctions.

**Règles cardinales :**
1. La source de vérité métier est Nextfleet/Spring Boot, et non les clients, le modèle IA ou Traccar.
2. Démarrer avec un **monolithe modulaire** Spring Boot, complété par Traccar indépendant et des services IA facultatifs : ne pas créer de microservices inutiles pour le pilote.
3. Les interfaces ne lisent jamais directement PostgreSQL/Traccar ni les secrets des services.
4. Un écran maquetté n'est pas une fonction livrée. Chaque capacité dispose d'une API, d'un état, de tests, de droits et d'un traitement honnête des erreurs.
5. L'activité GPS concerne les **missions préalablement autorisées** uniquement ; aucun suivi permanent des conducteurs hors mission.
6. Les anomalies déclenchent une **vérification humaine**, jamais un verdict automatique de fraude ou une sanction.
7. Les modèles 3D téléchargés respectent les droits de leurs auteurs ; **10 000 références de véhicule ≠ 10 000 géométries 3D**.
8. Stockage de démonstration strictement séparé des environnements institutionnels.

## II. ARCHITECTURE LOGIQUE

```text
Android natif Java/XML -------------+                   +-- Catalogue 3D, galerie et service media
                                    |                   |
Windows / Linux JavaFX -------------+-- HTTPS/WSS ------+-- Nextfleet API Spring Boot / Spring Security
                                                        |    modules : identité, parc, conducteurs, missions,
                                                        |    carburant, factures, budget, maintenance, pneus,
                                                        |    échéances, GPS métier, chat, alertes, IA métier,
                                                        |    rapports, imports, fichiers, audit, administration
                                                        |
                                                        +-- PostgreSQL + PostGIS (données métier)
                                                        +-- Stockage privé pièces, photos, GLB/OBJ
                                                        +-- Moteur de règles versionné Java
                                                        +-- Événements outbox + traitements asynchrones
                                                        |
                                                        +-- Adaptateur GPS --> Traccar Server (service distinct)
                                                        |                     ^
                                                        |                     |
                                                        |              Android / traceur GPS futur
                                                        |
                                                        +-- Service IA contrôlé (facultatif, réseau privé)
                                                             |-- catalogue/reconnaissance (modèle à qualifier)
                                                             |-- segmentation image SAM2 ou équivalent
                                                             |-- reconstruction 3D (TripoSR/TRELLIS, phase ultérieure)
                                                             +-- assistant LangChain4j + modèle local approuvé
```

**Sur Android**, Traccar Client SDK est Kotlin Multiplatform et expose des API suspendues : une **mince passerelle Kotlin** peut être utilisée pour l'appeler depuis l'application Java native. Tester réellement l'arrêt, la reprise et les permissions avant usage. Autre option : collecteur Android natif Java + tampon chiffré, sans deux collecteurs concurrents. Les positions doivent être associées et contrôlées par un jeton de mission éphémère sur une passerelle Nextfleet, puis transmises à Traccar par un adaptateur dédié. Les boîtiers physiques alimenteront plus tard Traccar et seront associés au véhicule dans un registre historisé de sources.

**Sur desktop 3D**, JavaFX + FXyz3D peut importer des OBJ et afficher des maillages. Son système d'import ne gère **pas directement GLB/glTF** comme un visualiseur Filament moderne ; prévoir une conversion contrôlée **GLB → OBJ/MTL/textures** pour la vue JavaFX, ou valider un moteur de rendu desktop dédié (par exemple Chromium embarqué + Three.js) sur les trois plateformes cibles. Faire un prototype mesuré, avec fallback photo. Sur **Android**, Filament/glTF constitue la cible principale pour les fichiers GLB. Ne pas promettre de photoréalisme identique sur les deux plateformes sans tests.

## III. DOMAINES MÉTIER À LIVRER

| Domaine | Fonctions principales | Contrôles clés |
|---|---|---|
| Authentification et organisation | Comptes, rôles, entités, délégations, profil, sessions, annuaire optionnel | RBAC + contraintes d'entité et d'objet, révocation |
| Parc automobile | Véhicules, marques, modèles, immatriculation, châssis, année, statut, affectation, historique | Unicité, archivage, historique des changements |
| Conducteurs | Fiches, permis, contacts, affectations, disponibilité | Permis à jour et absence de chevauchements interdits |
| Missions | Planification, validation, conducteur, véhicule, dates, itinéraire prévu, état | Machine à états et autorisation de démarrer |
| GPS | Suivi Android autorisé, traceurs futurs, trajet, kilomètres, vitesses, arrêts, géozones, historique | Mission active, précision, âge de la position, pas de tracking hors mission |
| Inspection | Checklists avant/après mission, défauts, photos, procès-verbaux | Auteur et traçabilité |
| Maintenance | Ordres, garage, réparation, pièces, coût, immobilisation, prochaine intervention | Justificatifs, validation technique et statut |
| Vidanges | Huile, filtres, litres, échéance date/km, coûts | Pas de double comptage |
| Pneumatiques | Position, dimension, permutation, usure, remplacement | Traçabilité par roue et véhicule |
| Carburant | Demandes, bons, approbations, ravitaillements, reçus, consommation plein-à-plein | Approbation indépendante, soldes atomiques, doublons |
| Factures et fournisseurs | Factures, lignes, reçus, rapprochements, pièces | Audit des montants et écarts |
| Budgets et coûts | Import XLSX, budgets par service, réalisé, prévisions, coût/km et TCO si fiable | Décimaux XAF, sources et périodes explicites |
| Alertes & investigations | Seuils, règles versionnées, dossiers, preuves, statut, validation humaine | Pas de conclusion automatique de fraude |
| Chat | 1:1, groupe mission, pièces, messages, état, reconnexion | Permissions à chaque échange, idempotence |
| Assistant IA | Recherche contextualisée, rapports explicables, réponses en français | Outils en lecture seule et citations aux données |
| Documents | Assurance, contrôle technique, permis, photos, reçus, modèles 3D | Chiffrement, stockage privé, expiration des liens |
| Rapports | Tableaux de bord, KPI, PDF/CSV/XLSX, filtres, exports | Reproductibilité des totaux et traçabilité |
| Administration | Rôles, entités, référentiels, seuils, licences, intégrations, journaux | Mises à jour auditées et moindre privilège |

## IV. PARCOURS MAQUETTES : 15 ÉCRANS PRIORITAIRES

**Ces 15 écrans constituent le parcours de démonstration graphique, pas l'intégralité des parcours institutionnels.** Décliner chaque fonction utile sur desktop et Android selon les droits et l'usage terrain ; ne pas recopier aveuglément une disposition desktop sur mobile.

| # | Écran | Contenu et comportement vérifiable |
|---|---|---|
| 01 | Connexion | Identité CAMTEL, compte local/SSO si disponible, erreurs et expiration de session |
| 02 | Tableau de bord | Statistiques parc, disponibilité, maintenance, budget, alertes et graphique |
| 03 | Liste véhicules | Recherche, filtres, badges, tri, pagination/scroll, accès à la fiche |
| 04 | Ajouter véhicule | Étapes identité, immatriculation, modèle, caractéristiques, photos, validation |
| 05 | Importer photo | Galerie/caméra Android ou fichier desktop ; aperçu, taille et type |
| 06 | Détourage | Segmentation service IA facultatif + sélection manuelle/fallback, masquage et aperçu |
| 07 | Reconnaissance | Hypothèse marque/modèle/année avec score et validation humaine, jamais certitude inventée |
| 08 | Choisir modèle 3D | Recherche catalogue des références, licence du modèle, vignettes, couleur permise |
| 09 | Visualisation 3D | Rotation/zoom, vues prédéfinies, mode photo si modèle absent, état de chargement |
| 10 | Fiche véhicule | Données réglementaires, affectation, état, tabs 3D/photos/technique/historique |
| 11 | Galerie réelle | Photos horodatées privées, auteur, métadonnées, permission et suppression contrôlée |
| 12 | Intérieur 3D | Affichage SEULEMENT si modèle intérieur licencié existe ; sinon galerie de photos |
| 13 | Maintenance 3D | Zones de carrosserie annotées, ordres de travail, liens pièces et photos |
| 14 | Suivi GPS | Mission en cours, carte, dernière position et fraîcheur, kilomètres, vitesse, arrêts |
| 15 | Expérience Android | Navigation tactile cohérente : véhicule, galerie, GPS, mission, entretien et documents |

**Écrans métier complémentaires obligatoires (non représentés sur la planche 15) :** conducteurs liste/détail/ajout, affectations, missions liste/création/détail/clôture, carte flotte et historique, contrôle technique, assurance et échéances, demandes et bons de carburant, validations, pleins, factures et rapprochements, maintenance list/création/détail, vidanges, pneus, budget et coût, anomalies list/détail/revue, centre de notifications, chat individuel/groupe, assistant IA, rapports et exports, import XLSX, documents, utilisateurs/rôles, paramètres, audit, profil, aide, états d'erreur/réseau/autorisations. Sur Android : parcours terrain prioritaire (missions, photos, saisies, GPS, chat) ; toute fonctionnalité de gestion non disponible doit être explicitement désignée, jamais simulée par un faux bouton.

## V. CHARTE GRAPHIQUE IMPÉRATIVE

Références visuelles originales : **PDF V4 p. 17 bureau, p. 18 Android**. Préserver l'architecture visuelle : navigation latérale bleu nuit, tableau blanc aéré, cartes douces, badges sémantiques, recherche globale, éléments d'action verts, cartes et tableaux denses mais lisibles, absence de décoration superflue. Ne pas introduire TravelSuite ni de framework graphique propriétaire d'un produit tiers.

**Tokens :**
- `color.navy=#102631`, `color.green=#009B87`, `color.background=#F6F9FC`, `color.text=#102434`.
- Grille 4/8 ; marges Android 16–24 dp ; bureau 24–32 px ; rayons 12–20 ; ombres minimales.
- Inter sous licence compatible (ou police système de substitution) ; échelle 12/14/16/20/28/36 ; chiffres tabulaires.
- Transitions 180–280 ms avec option de réduction de mouvement ; ne jamais cacher les états bloquants.
- Contraste cible 4,5:1 texte ordinaire ; TalkBack Android ; focus clavier JavaFX ; cibles tactiles >=48 dp ; écrans clair et sombre.
- Captures de comparaison à résolutions fixées : 1440×900 desktop et Android 393×852 dp ; adaptation 320–600 dp.
- L'application cible doit suivre la marque écrite dans les actifs officiels (le PDF écrit « NexFleet » ; harmoniser « Nextfleet » si confirmé, sans modifier le logo par accident).

## VI. BIBLIOTHÈQUE DE VÉHICULES ET 3D

### Catalogue et médias
- **10 000+ références automobiIes** comme cible de catalogue (marque, modèle, génération, année, variante, type), PAS 10 000 fichiers 3D prétendument disponibles.
- Table `vehicle_catalog_model`, `vehicle_catalog_variant`, `vehicle_3d_asset`, `vehicle_media`, `asset_license` ; champ `model_asset_status` : `EXACT`, `APPROXIMATE`, `GENERIC`, `NONE`.
- Conserver `source_url`, `author`, `license_id`, `license_version`, `attribution_text`, `download_proof`, `redistribution_allowed`, `reviewed_at`.
- Ne pas récupérer des actifs à partir de jeux vidéo ou de plateformes sans licence explicite ; respecter les marques et droits contractuels. Si licence incertaine : NE PAS IMPORTER.
- Optimiser glTF/GLB : Draco ou Meshopt si lecteurs compatibles, textures compressées après qualification, miniature, version légère Android, checksum, longueur maximum d'URL temporaire et quota cache.
- Android : Filament GLB ; desktop : import OBJ via FXyz3D après conversion versionnée et tests, ou autre moteur validé ; bascule sûre sur la photo.
- Visuel 3D approximatif distingué des photos réelles ; couleurs personnalisables uniquement si le matériau le permet.

### Reconnaissance et détourage de la photo
`Image caméra/galerie → contrôle de droits et consentement → stockage privé → service de segmentation approuvé → masque → image PNG transparence → hypothèse classification/catalogue → confirmation utilisateur → sélection modèle GLB → visualiseur.`

La génération 3D à partir d'une seule photographie est **une fonctionnalité optionnelle ultérieure** : segmentation SAM2 et reconstruction TripoSR/TRELLIS à exécuter sur un serveur GPU approuvé. Elle **invente les parties invisibles** ; étiquette obligatoire « reconstruction estimée, non preuve technique » ; original toujours conservé. Prévoir un mode manuel sans IA (recadrage/masquage ou sélection modèle).

### Fiche d'entretien 3D
Des marqueurs positionnés sur le modèle `asset_id + node_id / surface_coordinate` renvoient aux interventions. La présence de pièces internes/mécaniques ne se déduit pas d'une photo extérieure ; le mode intérieur doit être masqué ou signalé indisponible si absent.

## VII. GPS, DÉPLACEMENTS ET ALERTES

- Mission approuvée, conducteur affecté et consentement/information explicite avant activation. **Arrêt de la collecte à la clôture ou révocation**, indépendamment de la fermeture de l'interface.
- Émetteur : téléphone Android en phase 1, traceur physique en phase 2 ; liaison historisée `tracking_source_assignment` et priorité contrôlée.
- Enregistrement : `mission_id`, `vehicle_id`, `source_device_id`, `client_fix_id`, `recorded_at`, `received_at`, `latitude`, `longitude`, `accuracy_m`, `speed_mps?`, `bearing?`, `provider`, `quality_state`.
- Filtrage des sauts GPS, âge, précision et trous de collecte avant statistiques. Distance calculée sur points qualifiés avec géodésie/PostGIS ; vitesse observée et calculée à distinguer ; arrêts configurables et zones approuvées. Ne pas annoncer une vitesse ou distance exacte quand l'échantillonnage est insuffisant.
- Carte MapLibre avec source et horodatage : `DIRECT` uniquement pour position récente selon seuil approuvé ; sinon `DERNIÈRE POSITION` avec âge. Fond de carte exploité sous licence et politique de tuiles approuvées.
- Hors réseau : file locale chiffrée limitée à mission active, dédoublonnage par `client_fix_id`, reprise avec backoff, purge après accusé, affichage « stocké sur l'appareil / reçu serveur ».
- Rapprochements : bon, facture, plein à plein, compteur réel, GPS estimé, immobilisation ; pas d'équivalence abusive entre litres achetés et litres consommés.

## VIII. CHAT ET IA

**Chat MVP** : Spring WebSocket WSS + REST historique, contrôle d'abonnement par conversation et entité, `client_message_id` unique, séquence serveur, transaction DB avant accusé, états local/serveur/distribué/lu, récupération `after_seq`, pièces privées scannées et notifications minimisées. Openfire n'est pas obligatoire pour le pilote ; décision ultérieure si XMPP devient nécessaire.

**Contrôles** : moteur Java explicable (écarts bon/reçus, reçu doublé, capacité du réservoir, compteur incohérent, plein-à-plein, facture, dépenses inhabituelles, mission GPS), avec `rule_id`, `rule_version`, `evidence_ids`, `severity`, `confidence/quality`, `review_status`, auteur et décision. Utiliser des montants décimaux (Java BigDecimal, XAF). Aucune sanction automatique.

**IA conversationnelle** : LangChain4j plus modèle local UNIQUEMENT si hébergement, licence de modèle et sécurité approuvés. Outils API **lecture seule et filtrés** avec réponses qui citent les enregistrements/périodes. Si service IA indisponible, montrer clairement « service non disponible » et maintenir les calculs déterministes. Tribuo et entraînement sur historique en phase ultérieure, pas une condition MVP.

## IX. DONNÉES, API ET SÉCURITÉ

### Entités principales
`organisation_unit`, `user`, `role`, `permission`, `driver`, `driver_license`, `vehicle`, `vehicle_assignment`, `vehicle_odometer`, `vehicle_catalog_model`, `vehicle_catalog_variant`, `vehicle_3d_asset`, `vehicle_media`, `asset_license`, `mission`, `mission_assignment`, `tracking_authorization`, `tracking_source`, `tracking_source_assignment`, `location_point`, `trip_summary`, `stop_event`, `geofence`, `inspection`, `maintenance_order`, `oil_change`, `tire`, `tire_event`, `fuel_order`, `fuel_approval`, `fuel_fill`, `invoice`, `invoice_line`, `supplier`, `budget`, `budget_line`, `anomaly_rule`, `anomaly`, `investigation`, `evidence_ref`, `conversation`, `conversation_participant`, `message`, `message_receipt`, `attachment`, `assistant_run`, `outbox_event`, `audit_event`.

Index uniques à prévoir : `vehicle.registration_normalized` et identifiants châssis selon règles CAMTEL, `(device_id,client_fix_id)` sur GPS, `(sender_id,client_message_id)` sur chat, identifiants de bon/reçu selon provenance. Versions optimistes pour mises à jour concurrentes ; écritures financières transactionnelles.

### API REST indicatives (à formaliser dans OpenAPI)
```text
POST /api/v1/auth/login ; POST /api/v1/auth/logout ; GET /api/v1/me
GET/POST /api/v1/vehicles ; GET/PATCH /api/v1/vehicles/{id}
GET /api/v1/vehicle-catalog?q=... ; GET /api/v1/vehicle-models/{id}/assets
POST /api/v1/vehicles/{id}/media ; POST /api/v1/vehicles/{id}/model-selection
POST /api/v1/vehicle-media/{id}/segment ; GET /api/v1/processing-jobs/{id}
GET/POST /api/v1/drivers ; GET/POST /api/v1/missions
POST /api/v1/missions/{id}/start ; POST /api/v1/missions/{id}/finish
POST /api/v1/missions/{id}/positions:batch ; GET /api/v1/missions/{id}/track
GET /api/v1/missions/{id}/statistics ; GET /api/v1/vehicles/{id}/location
GET/POST /api/v1/fuel-orders ; POST /api/v1/fuel-orders/{id}/approve
GET/POST /api/v1/fuel-fills ; GET/POST /api/v1/maintenance-orders
GET /api/v1/anomalies ; POST /api/v1/anomalies/{id}/review
GET /api/v1/conversations ; GET /api/v1/conversations/{id}/messages?after=...
POST /api/v1/conversations/{id}/messages ; WSS /ws/chat
POST /api/v1/assistant/query ; GET /api/v1/reports ; GET /api/v1/audit
```
Le backend valide tous les UUID/IDs, états, droits et données. `Idempotency-Key` pour commandes sensibles. `X-Request-ID` pour diagnostic ; UTC au stockage, fuseau `Africa/Douala` à l'écran. Ne pas exposer de service IA brut, identifiants Traccar, URL de fichier permanent ou credentials Postgres dans les clients.

### Sécurité et exploitation
RBAC, autorisation niveau objet/entité, OIDC/annuaire CAMTEL après accord, TLS, stockage privé, chiffrement et gestion des secrets, rate limits, audit d'administration, journalisation de demandes sensibles sans données secrètes, politique de purge/gel de conservation approuvée. Scanner les pièces avant accès. Tests d'absence de tracking hors mission et accès interdits à positions/messages d'autres entités. Séparer dev/test/préprod/prod. Sauvegardes et restauration éprouvées. Git institutionnel et CI avec SBOM/licences à valider. Pas d'intégration CAMTEL active ni certification DS présumée.

## X. STRUCTURE RECOMMANDÉE DU DÉPÔT

```text
nextfleet/
  apps/
    desktop-javafx/
      src/main/java/.../ui/{auth,dashboard,vehicles,drivers,missions,gps,fuel,maintenance,budget,finance,chat,ai,reports,settings,threeD}/
      src/main/resources/{styles,icons,fonts,images}/
    android-java/
      app/src/main/java/.../{features,core,services,threeD,gps}/
      app/src/main/res/{layout,drawable,values,navigation}/
  services/
    nextfleet-api/
      src/main/java/.../{identity,vehicles,drivers,missions,gps,maintenance,fuel,finance,budget,catalog3d,media,chat,alerts,assistant,reports,audit,admin}/
      src/test/
    gps-traccar-adapter/
    ml-media-service/                 # facultatif, isolé, sans connexion externe par défaut
  shared/
    api-contract/openapi.yaml
    ui-tokens/tokens.json
    data-fixtures/demo-FAKE.json
  infra/
    compose.local.yaml
    db/migrations/
    monitoring/
    ci/
  docs/
    architecture.md
    design-reference/
    feature-matrix.md
    threat-model.md
    legal-licenses.md
    qa-test-plan.md
    operations.md
  README.md
```

### Production des binaires
- Backend : JAR Spring Boot ou image OCI, migration Flyway contrôlée.
- Desktop : EXE/MSI Windows et DEB Linux avec runtime approprié via `jpackage` et builds par OS ; pas de promesse d'exécutable multi-OS en une seule compilation Linux.
- Android : APK debug vérifiable ; APK release signé uniquement avec clés de signature remises par le propriétaire ; AAB si canal de distribution requis.
- `./gradlew test`, lint/static analysis, tests API/intégration Testcontainers, UI tests, compilation Android et JavaFX dans CI adaptées ; guide de déploiement documenté.

## XI. STRATÉGIE DE LIVRAISON

**P0 – fondations et parité** : audit code existant, registre des fonctions, schéma de base, login, authz, véhicules, maquettes JavaFX/Android, imports déterministes, tests.  
**P1 – pilote 30 jours** : 15 écrans *du storyboard* au niveau visuel et navigation ; parcours métier exécutables les plus prioritaires ; chat 1:1/groupe de mission ; GPS Android limité à missions ; carte et statistiques qualifiées ; règles de rapprochement carburant ; 3D sur quelques actifs autorisés ; démo fictive et PV de recette. **Ne pas garantir que 15 écrans complexes + toutes les intégrations seront tous fonctionnels en 30 jours sans mesure des capacités de l'équipe.**  
**P2 – consolidation** : bons/approbations/facturation exhaustive, rapports, alertes, charge, revue DS, démonstrateur DSIR, catalogue 10 000 références et collection 3D juridiquement qualifiée.  
**P3 – extensions** : boîtiers GPS physiques, segmentation/reconstruction GPU, modèles intérieurs, IA statistique, intégration SI CAMTEL, industrialisation.

## XII. PROMPT MAÎTRE — À COLLER DANS UNE IA DE DÉVELOPPEMENT

> **Pièces à fournir avec ce prompt** : (a) cahier des charges CAMTEL V4.0 PDF, (b) captures des maquettes p. 17/18, (c) storyboard 15 écrans du chat, (d) dépôt complet Nextfleet existant avec backend et clients et, si applicable, les ZIP. Sans ces ressources, le développeur doit le dire explicitement et ne pas prétendre avoir reproduit les maquettes au pixel près ni préservé des composants dont il ne possède pas le code.

```text
RÔLE
Tu es une équipe senior unifiée : architecte Java, développeur JavaFX, développeur Android Java, expert Spring Boot/PostgreSQL, ingénieur GPS Traccar, spécialiste rendu 3D, expert IA/MLOps, expert sécurité institutionnelle et designer de produit premium. Tu dois CONSTRUIRE, TESTER et LIVRER une application Nextfleet (marque visuelle du PDF : NexFleet) destinée à CAMTEL, sans simuler des fonctionnalités ni inventer des validations.

RÉFÉRENCES NON NÉGOCIABLES
1) Le cahier des charges officiel NexFleet CAMTEL V4.0 en pièce jointe, 45 pages, est la référence de périmètre, sécurité et règles métier. Le PDF p.17 contient la maquette desktop, p.18 les maquettes Android.
2) Les 15 écrans du storyboard Nextfleet fournis sont la référence fonctionnelle du parcours véhicule/photo/catalogue 3D/maintenance/GPS. Ils complètent le PDF sans remplacer les modules carburant, budget, maintenance, audit, chat, assistant, conducteurs et factures.
3) Conserver le design du PDF : bleu nuit #102631, vert #009B87, fond #F6F9FC, texte #102434, typographie Inter ou substitution compatible, grille 4/8, coins 12–20, effets sobres, mode sombre et accessibilité. Respecter la structure de navigation desktop avec sidebar. Adapter le mobile aux conventions Android natives et utiliser des surfaces tactiles >=48dp. Bannir les références visuelles TravelSuite.

PREMIER TRAVAIL OBLIGATOIRE — AUDIT
- Ouvrir et analyser l'intégralité du code réellement fourni. Dresser inventaire modules, packages, langages, versions, dépendances, secrets, tests, fonctionnalités existantes, bugs, incohérences et risques.
- Dresser un tableau de correspondance entre chacune des exigences NF du PDF, l'existant, l'écran Android, l'écran desktop, les API et les tests de preuve.
- Ne PAS effacer ni remplacer sans justification un écran ou un module existant. Pas de refonte destructive ; migrations et compatibilité documentées.
- Si une archive, une image, un fichier, une clé ou un service manque, le signaler et créer un adaptateur ou une interface à configurer ; ne pas prétendre y avoir accès.

PILE CIBLE
- Backend : Java 21, Spring Boot compatible, Spring Security, REST OpenAPI 3, WebSocket sécurisé, persistance PostgreSQL + PostGIS, migrations SQL versionnées, validation Bean Validation, tests JUnit + intégration.
- Desktop Windows/Linux : Java 21, JavaFX, design system NexFleet original, écrans responsifs de bureau, styles JavaFX CSS, raccourcis clavier et focus accessible.
- Mobile : application native Android en Java + XML, architecture repository/view-model adaptée, GPS au premier plan seulement pour missions autorisées, cache limité chiffré, notifications strictement nécessaires.
- GPS : Traccar Server isolé ; Traccar Client SDK via passerelle Kotlin minimale seulement si qualifiée sur application Java, OU collecteur Java natif robuste. Adapter pour le contrôle d'autorisation de mission. Android phase 1 ; traceurs véhicules phase 2 sans changer les identifiants métiers.
- Cartes : MapLibre et tuiles autorisées, indicateurs âge/précision des positions.
- 3D Android : Filament + GLB/glTF. Desktop : JavaFX + FXyz3D avec OBJ/MTL après conversion validée, et fallback photo ; éventuellement un rendu Chromium embarqué si compatibilité et sécurité vérifiées. Ne jamais prétendre que FXyz3D génère la 3D ni importe nativement un GLB.
- Photo : image d'origine privée, import et recadrage, découpage par service ML facultatif, hypothèses de reconnaissance à confirmer, catalogue 10 000+ RÉFÉRENCES et actifs 3D téléchargés uniquement sous licences autorisées. Une vue 3D « approximative » ne remplace jamais une photo du véhicule réel.
- IA : Java rules-first pour les écarts carburant, factures, compteurs, maintenance et données GPS ; assistant lecture seule LangChain4j + modèle local uniquement si approuvé ; scoring Tribuo facultatif en phase ultérieure. Aucun verdict de fraude automatique.
- Chat : Spring WebSocket + REST historique, messages persistés avant accusé, idempotence et reprise après coupure, conversations privées par mission/entité, pièces privées.

LIVRABLES ÉCRANS — LES 15 ÉCRANS DU STORYBOARD
01 Connexion desktop/Android ; 02 Dashboard ; 03 Liste véhicules ; 04 Ajout ; 05 Photo ; 06 Détourage ; 07 Reconnaissance ; 08 Catalogue et sélection 3D ; 09 Vue 3D ; 10 Fiche véhicule ; 11 Galerie réelle ; 12 Vue intérieure conditionnelle à actif réel ; 13 Maintenance associée à des zones 3D ; 14 Carte GPS mission en temps réel ; 15 Parcours complet Android.

AUTRES ÉCRANS INDISPENSABLES
Fiches conducteurs, affectations, missions création/validation/historique, suivi technique, contrôles, assurance, vidanges, pneus, ravitaillement, demandes/bons de carburant, autorisations, factures, budget, coûts et TCO, anomalies et dossiers de vérification, rapports, exports et imports, notifications, chat, assistant IA, rôles et utilisateurs, paramètres, profil, audit, aide, connexion expirée, permissions refusées, état réseau indisponible et chargement. Appliquer RBAC et masquage conditionnel côté UI, doublé de validation côté serveur.

RÈGLES MÉTIER CRITIQUES
- Une dépense est soumise à validation par une personne distincte du demandeur. Montants en BigDecimal/XAF.
- Consommation réelle uniquement « plein-à-plein » lorsque les bornes sont fiables : litres ajoutés depuis le plein initial EXCLU jusqu'au plein final INCLUS, divisés par la distance relevée, multipliés par 100.
- Le volume acheté sur une mission n'est pas automatiquement le volume consommé.
- Chaque correction sensible conserve l'auteur, l'horodatage et l'ancienne valeur selon le cadre légal approuvé.
- Alerte antifraude = indice à instruire ; pas de faute imputée ni sanction automatique.
- Les coordonnées sont collectées seulement si mission active, autorisée, consentement/information et permissions effectives ; arrêt immédiat à clôture/révocation.
- GPS : calculer km, vitesse et arrêt sur données validées ; qualifier écart et imprécision ; pas de valeur « en direct » pour point périmé.
- Chat : jamais confirmer « livré » avant persistance/accusé ; pas de doublon sur réémission ; droit vérifié à chaque message et abonnement.
- IA : accès en lecture seule avec permission de rôle, provenance de chaque réponse, aucune sortie externe par défaut.

FLUX CLÉS À IMPLÉMENTER DE BOUT EN BOUT
A. Connexion -> Dashboard -> Liste véhicules -> Nouveau véhicule -> Photos -> Catalogue 3D -> Choisir actif autorisé -> Fiche détaillée, avec variante 3D indisponible.
B. Gestionnaire crée/autorise une mission -> conducteur démarre sur Android -> positions mission -> Traccar -> API Nextfleet -> carte desktop + kilomètres/vitesses/temps d'arrêt -> fin de mission -> absence totale de nouvelles positions.
C. Bon carburant -> approbation indépendante -> plein avec justificatif -> rapprochement factures et données de trajet -> règle d'écart -> dossier d'investigation -> revue humaine.
D. Responsable ouvre chat mission -> demande photo/justificatif -> conducteur envoie en déplacement réseau -> reconnexion -> message unique horodaté -> pièce consultable selon rôle.
E. Gestionnaire interroge assistant : « Coût des véhicules X, Y et Z ce mois-ci ? » -> outils en lecture seule -> réponse avec chiffres, période, filtres et références ; refus si droits insuffisants.

STRATÉGIE DE RÉALISATION
Travaille par étapes VERIFIABLES, compilables et reproductibles :
Étape 0 : audit et plan, inventaire de parité et matrice des risques.
Étape 1 : environnement de développement, backend, base migrée, fixtures fictives, OpenAPI, authentification/RBAC, CI.
Étape 2 : design tokens, composants JavaFX et Android, navigation et écrans dashboard/véhicules.
Étape 3 : CRUD métier et parcours complets parc/conducteurs/mission/maintenance/carburant/facturation, validations et droits.
Étape 4 : 15 écrans storyboard, catalogue références, photo, visualiseur Android GLB, desktop OBJ/fallback, mode segmentation facultatif.
Étape 5 : GPS mission Traccar, collecte Android, historisation, carte et calculs qualifiés.
Étape 6 : chat WebSocket robuste, pièces et notifications.
Étape 7 : anomalies déterministes et investigations ; IA locale encadrée si possible et approuvée.
Étape 8 : audits de sécurité, charge, accessibilité, régression UX et génération des installateurs.
Chaque étape doit livrer un état fonctionnel utilisable, une preuve de test et un état d'avancement. N'essaie pas d'affirmer « 100 % complet » sur une génération unique sans compiler ni tester.

RETOUR ATTENDU À CHAQUE ÉTAPE
- Code source COMPLET créé ou modifié dans le dépôt, avec chemins exacts, pas seulement pseudo-code.
- Explication concise des choix et différences avec le PDF.
- Commandes exactes pour compiler/tester/démarrer serveur/desktop/Android.
- Résultats réels des commandes, tests passés/échoués, blocages non dissimulés.
- Captures d'écrans comparées aux références p.17 et p.18 lorsque l'environnement graphique le permet.
- Inventaire clair de ce qui marche réellement, ce qui est encore simulé ou indisponible, et prochaines tâches classées.
- Aucun secret réel, aucune donnée CAMTEL réelle ni licence de modèle non validée dans le dépôt.

CRITÈRES DE RÉCEPTION
REC-UX-01 : styles conformes, modes clair/sombre, textes longs, clavier/TalkBack, états de chargement/erreur, mobile petit écran.
REC-GPS-01/02 : suivi seulement en mission, permission refusée respectée, arrêt prouvé, faux live impossible.
REC-CHAT-01/02 : réception + persistance, groupe mission privé, déduplication, récupération après coupure.
REC-IA-01/02 : écart facture-bon exact et documenté, assistant sourcé avec accès contrôlé.
REC-SEC-01 : interdiction vérifiée d'accès aux messages/GPS des autres entités.
3D-01 : chargement d'un modèle licencié correspondant à sa génération avec attribution.
3D-02 : photo originale préservée, fallback quand modèle absent ; aucun résultat de détourage ou génération annoncé sans traitement effectif.
BUILD-01 : backend et migrations testés ; client JavaFX Windows/Linux empaquetable selon OS ; APK Android compilable ; instructions de déploiement testables.

COMMENCE MAINTENANT PAR :
1. Extraire la liste exhaustive des fonctionnalités du PDF et des 15 maquettes.
2. Inspecter le dépôt et établir une matrice « existant / manquant / à réparer / à concevoir ».
3. Produire l'arborescence définitive, les contrats API, le modèle de données et le plan de compilation.
4. Implémenter immédiatement le premier lot minimal fonctionnel et le tester, sans attendre une nouvelle confirmation si les fichiers nécessaires sont présents ; sinon indiquer précisément le manque.
```

## XIII. PRÉCAUTIONS ET CRITÈRES DE CRÉDIBILITÉ

La démonstration en un mois est un **objectif de pilote**. Les traitements de segmentation, de reconnaissance et de reconstruction 3D n'étaient **pas** intégrés au cahier V4 comme fonctionnalités livrées ; ils sont des extensions V5 soumises à faisabilité et validation. L'application proposée n'est pas certifiée CAMTEL ni en production du seul fait de ce document. Les étapes de tests, de revue sécurité, de licence et de performance conditionnent l'utilisation de données et appareils réels.
