# NexFleet CAMTEL — Audit initial et matrice de conformité

**Date : 20 octobre 2026 · Lots 1 et 2 · Références : cahier des charges V4.0 (45 p.), document d'architecture V5.0, maquettes p. 17 et p. 18, planche de 15 écrans.**

## 1. Constat d'audit

| Point | Constat | Conséquence |
|---|---|---|
| Dépôt existant (Flask/SQLite/Flutter cité au cahier v2) | **Retrouvé le 20/10 : `rito27959-arch/nexfleet`.** 84 routes, 78 tests backend réussis, 12 vues web, 8 écrans Flutter. | Correspondance détaillée et défauts : **`docs/REGISTRE_PARITE.md`** (fait foi sur la colonne « Existant » ci-dessous, établie avant l'accès au dépôt). |
| Secrets et données réelles du dépôt de référence (cahier V4, chap. 14) | Non vérifiable sans le dépôt. | L'assainissement reste un prérequis de lancement. Le nouveau dépôt ne contient **aucun secret ni donnée réelle** (mot de passe de base de développement uniquement dans le script de test local). |
| Environnement de réalisation | Maven Central, dépôts Google/Gradle et Docker Hub **bloqués** par le réseau. JDK 21, PostgreSQL 16 + PostGIS, OpenJFX (paquet Ubuntu) et JUnit 5 disponibles. | Spring Boot et Android **non compilables ici**. Le noyau métier, la base et le client JavaFX ont été compilés et testés avec une chaîne hors ligne (`tools/build-offline.sh`). |
| Marque | Le PDF écrit « NexFleet » ; la planche de 15 écrans écrit « Nextfleet ». | Les interfaces utilisent **NexFleet** (actifs officiels du cahier). Décision de marque à confirmer par le BUT. |
| Écarts V4 → V5 | Traccar, catalogue 3D, détourage et reconnaissance ne figurent pas au V4. Le V4 prévoit Android Location API / Fused Location. | Ces éléments sont traités comme **extensions V5 soumises à décision** (D05, D08, D10). Le schéma de base les prévoit sans les rendre obligatoires. |
| Logo | Aucun fichier vectoriel officiel fourni. | Emblème redessiné d'après le cahier, à remplacer par le fichier officiel. |

## 2. Légende

- ✅ **Réalisé et testé** dans ce lot (preuve exécutée, voir `docs/ETAT_AVANCEMENT.md`)
- 🟢 **Garanti par la base** : contrainte ou déclencheur PostgreSQL testé ; API et écrans restant à brancher
- 🟡 **Écrit, non compilé** (Spring Boot) ou non exécuté (Testcontainers)
- ⬜ **À concevoir / réaliser** — lot indiqué
- ❓ **Existant non vérifiable** (dépôt absent)

## 3. Exigences fonctionnelles du cahier V4

| Exigence | Existant | Base de données | Noyau métier | API | Bureau JavaFX | Android | Lot cible |
|---|---|---|---|---|---|---|---|
| NF-01 Sessions et connexion | ❓ | 🟢 sessions hachées, révocation | — | 🟡 login/logout/me | ✅ écran 01, erreur générique, session expirée | ⬜ | 1 / 2 |
| NF-02 Comptes et permissions (6 profils) | ❓ | 🟢 matrice V1+V5, pas d'auto-attribution, délégation bornée, `nf_has_permission` | ✅ `Role`/`Permission` = base (test de parité), `AccessPolicy` | 🟡 `AccessService` ; contrat `/admin/users` | ✅ navigation et écrans par profil, comptes et matrice (lot 2) | ⬜ | 2 |
| NF-03 Paramètres | ❓ | ⬜ | — | ⬜ | ✅ module signalé non disponible | ⬜ | 2 |
| NF-04 Démonstration séparée | ❓ | 🟢 refus en PRODUCTION | — | 🟡 propriété d'environnement | ✅ badge DÉMO permanent | ⬜ | 1 |
| NF-10 Véhicules | ❓ | 🟢 unicité normalisée, archivage, pas de suppression | ✅ `RegistrationNumber` | 🟡 CRUD, If-Match, audit | ✅ liste, recherche, filtres, pagination, fiche, ajout étape 1 | ⬜ | 1 |
| NF-11 Échéances documentaires | ❓ | 🟢 `vehicle_document` | ⬜ | ⬜ | 🟡 alerte assurance (démo) | ⬜ | 2 |
| NF-12 Conducteurs | ❓ | 🟢 affectations sans chevauchement | ⬜ | ⬜ | ⬜ | ⬜ | 2 |
| NF-13 Contrôles / inspections | ❓ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 2 |
| NF-14 Maintenance | ❓ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 2 |
| NF-15 Vidanges | ❓ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 2 |
| NF-16 Pneumatiques | ❓ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 2 |
| NF-17 Kilométrage | ❓ | 🟢 relevés, époque de compteur | ✅ CAR-03 | ⬜ | ⬜ | ⬜ | 2 |
| NF-18 Pièces et photos | ❓ | 🟢 taille ≤ 10 Mo, types, original conservé | ⬜ | ⬜ | ⬜ | ⬜ | 3 |
| NF-19 Localisation déclarée | ❓ | — | — | — | ✅ libellé « position déclarée », jamais « en direct » | ⬜ | 1 |
| NF-20 Tableau de bord | ❓ | — | ✅ consommation calculée | ⬜ agrégat serveur | ✅ écran 02 (démo) | ⬜ | 1 / 2 |
| NF-21 Budget XLSX | ❓ | ⬜ | ⬜ | ⬜ | ⬜ | — | 3 |
| NF-22 Coûts et TCO | ❓ | ⬜ | ⬜ | ⬜ | ⬜ | — | 3 |
| NF-23 Prévisions techniques | ❓ | ⬜ | ⬜ | ⬜ | ⬜ | — | 3 |
| NF-24 Notifications | ❓ | 🟢 outbox | ⬜ | ⬜ | ⬜ (signalé lot 3) | ⬜ | 3 |
| NF-25 Rapports | ❓ | 🟢 vue de rapprochement | ⬜ | ⬜ | ⬜ (Exporter inactif et expliqué) | — | 3 |
| NF-26 Imports / exports | ❓ | ⬜ | ⬜ | ⬜ | ⬜ | — | 3 |
| NF-27 Assistant | ❓ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | 4 |
| NF-28 Audit et sauvegardes | ❓ | 🟢 journal chaîné, ajout seul | — | 🟡 `AuditService` | ⬜ | — | 1 / 5 |
| NF-29 Transition mobile Flutter | ❓ | — | — | ⬜ contrats à relever sur le code Flutter | — | — | bloqué (dépôt absent) |
| NF-40 Bons et autorisations | — | 🟢 demandeur ≠ valideur, états, solde | ✅ `FuelOrder` | 🟡 créer, soumettre, valider | ⬜ | ⬜ | 3 |
| NF-41 Ravitaillements | — | 🟢 reçu unique, immuable, idempotence | — | 🟡 Idempotency-Key | ⬜ | ⬜ | 3 |
| NF-42 Consommation plein-à-plein | — | — | ✅ 8,33 L/100 km (REC-05) | 🟡 endpoint | ✅ graphique tableau de bord | ⬜ | 1 |
| NF-43 Détection des écarts | — | 🟢 règles versionnées | ✅ CAR-02/03/04/06/07 | ⬜ | ⬜ | — | 3 |
| NF-44 Dossier de vérification | — | 🟢 aucun statut « fraude », revue en ajout seul | ✅ libellés non accusatoires | ⬜ | ⬜ | — | 3 |
| NF-45 Rapprochement financier | — | 🟢 vue ligne à ligne (écart 5 L testé) | ✅ CAR-07 | ⬜ | ⬜ | — | 3 |
| NF-50 Annuaire et RH | — | 🟢 identifiant RH, révocation à la suspension | — | ⬜ OIDC après accord DSIR | ✅ mention « après accord DSIR » | ⬜ | 4 |
| NF-51 Hébergement et dépôt | — | — | — | — | — | — | décision DSIR |
| NF-52 Documentation et transfert | — | — | — | — | — | — | continu |
| NF-53 Journal probant | — | 🟢 chaînage SHA-256, modification refusée | — | 🟡 | — | — | 1 |
| NF-60 à NF-64 Chat | — | 🟢 participants, chat:use, séquence sans trou, réémission ignorée, curseurs distribué/lu | ✅ `Chat`, `Outbox`, magasin de référence | ✅ passerelle WebSocket pilote testée sur PostgreSQL ; 🟡 cible Spring WebSocket | ✅ Messages (démo) | ⬜ | 2 / 3 |
| NF-70 à NF-74 GPS de mission | — | 🟢 + registre des sources, rejets journalisés sans position | ✅ `MissionTrackingGate`, analyse de trace | ✅ passerelle Traccar (forward JSON, lots Android à jeton de mission, relais OsmAnd) testée sur PostgreSQL ; Traccar réel non exécuté | ✅ Missions, Mes missions, tracé (démo, sans fond de carte) | ⬜ collecteur | 2 / 3 |
| NF-80 à NF-84 IA | — | 🟢 règles et revues tracées ; activation et interrogations de l'assistant en ajout seul | ✅ moteur de règles + assistant lecture seule `RULES_FR_V1` | 🟡 contrat `/assistant/*` | ✅ Assistant et activation (démo) | — | 2 / 4 |

## 4. Les 15 écrans du storyboard

| # | Écran | Bureau | Android | Remarque |
|---|---|---|---|---|
| 01 | Connexion | ✅ | ⬜ | Erreur générique, attente, session expirée, mode démo signalé |
| 02 | Tableau de bord | ✅ démo | ⬜ | Fidèle à la p. 17 ; agrégat serveur au lot 2 |
| 03 | Liste véhicules | ✅ | ⬜ | Recherche normalisée, 6 filtres, pagination, états vide/erreur |
| 04 | Ajout véhicule | ✅ étape 1 | ⬜ | Validation par champ ; étapes Photos/3D signalées non disponibles |
| 05 | Importer photo | ⬜ | ⬜ | Lot 4 ; base : 10 Mo, types autorisés |
| 06 | Détourage | ⬜ | ⬜ | Lot 4 ; service IA facultatif + mode manuel |
| 07 | Reconnaissance | ⬜ | ⬜ | Lot 4 ; hypothèse + validation humaine |
| 08 | Catalogue 3D | ⬜ | ⬜ | Lot 4 ; publication bloquée sans licence revue (testé) |
| 09 | Vue 3D | ⬜ | ⬜ | Lot 4 ; Filament (Android), OBJ/FXyz3D ou rendu embarqué (bureau) à prototyper |
| 10 | Fiche véhicule | ✅ panneau (partiel) | ⬜ | Illustration générique signalée ; onglets aux lots 2/4 |
| 11 | Galerie réelle | ⬜ | ⬜ | Lot 4 |
| 12 | Intérieur 3D | ⬜ | ⬜ | Seulement si actif intérieur licencié |
| 13 | Maintenance 3D | ⬜ | ⬜ | Lot 4, après le module Maintenance (lot 2) |
| 14 | Suivi GPS | ✅ démo | ⬜ | Lot 2 : écrans Missions / Mes missions ; fond de carte à décider (DSIR) |
| 15 | Parcours Android | — | ⬜ | Bloqué tant que les dépôts Google/Gradle sont inaccessibles |

## 5. Critères de recette couverts par une preuve exécutée

| Critère | Preuve dans ce lot |
|---|---|
| REC-05 plein-à-plein | Test JUnit `FullToFullConsumptionCalculatorTest` : 8,33 L/100 km et « données insuffisantes » |
| REC-06 bon concurrent, reçu répété | `run_db_tests.sh` : deux sessions PostgreSQL simultanées, un seul plein accepté ; reçu répété bloqué |
| REC-IA-01 écart facture/bon | Base : écart 5 L / 4 000 XAF ; noyau : règle CAR-07 avec seuil, version et preuves |
| REC-GPS-01 (partiel) | Base : aucun point avant démarrage, après clôture, ou d'un appareil non autorisé ; noyau : faux « direct » impossible |
| REC-CHAT-02 (partiel) | Base : réémission refusée, séquence stable |
| REC-SEC-01 (partiel) | Base : non-participant refusé ; noyau : accès inter-entités refusé |
| REC-08 (partiel) | Base : suspension → sessions révoquées |
| 3D-01 / 3D-02 (partiel) | Base : publication sans licence refusée ; dérivé de photo sans original refusé |
| REC-11 / REC-UX-01 (partiel) | 11 captures 1440×900 dont thème sombre, erreur réseau, droits, module non disponible |

## 6. Matrice des risques (lot 1)

| Risque | Gravité | Mesure |
|---|---|---|
| Code existant inaccessible : fonctions oubliées | Élevée | Obtenir le dépôt ; registre de parité écran par écran avant tout retrait |
| API Spring Boot non compilée | Élevée | Ouvrir Maven Central ; exécuter `mvn verify` (Testcontainers) avant de déclarer l'API livrée |
| Android non démarré | Élevée | Ouvrir dl.google.com / maven.google.com / services.gradle.org ; lot 2 |
| Seuils GPS et de règles non approuvés | Moyenne | Valeurs « démo » isolées dans `TrackPolicy.demoDefaults()` ; décision métier/DS |
| Numérotation des bons par compteur journalier | Faible | Unicité garantie par la base ; séquence dédiée au lot 3 |
| JavaFX 11 (paquet Ubuntu) utilisé pour la recette hors ligne | Faible | Code compatible JavaFX 21 ciblé par le `pom.xml` ; à revérifier |
