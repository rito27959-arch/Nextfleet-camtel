# État réel d'avancement — version pilote 0.5.0

**20 octobre 2026.** Ce document distingue ce qui a été exécuté et vérifié de ce qui est seulement écrit.
Aucune autorisation CAMTEL, aucun avis DSIR ou DS et aucune mise en production ne sont revendiqués.

## Ce qui fonctionne réellement (preuves exécutées)

Toutes les lignes marquées **CI** sont rejouées à chaque push par GitHub Actions (`.github/workflows/ci.yml`) ; les
résultats sont lisibles dans l'onglet Actions (annotations « bilan »).

| Élément | Où | Résultat obtenu |
|---|---|---|
| Migrations V1–V8 sur PostgreSQL 16 + PostGIS 3.4 | CI + `infra/db/tests/run_db_tests.sh` | Appliquées sur base vierge |
| Tests d'intégrité de la base | idem | **63 tests réussis** + concurrence CAR-01 (un seul plein accepté sur deux simultanés). Nouveau : journal vérifiable depuis un autre fuseau horaire (V7) |
| Noyau métier Java 21 | CI (Maven) + `tools/build-offline.sh` | **42 tests** |
| API Spring Boot | CI (Maven, Testcontainers PostGIS) | **8 tests d'intégration** : lot 1, scénario lot 2 ci-dessous, **temps réel** (point accepté → WebSocket filtré par droits, autre entité exclue, carte de flotte calculée par PostGIS) et **amorçage DEMO** (comptes fictifs, simulateur visible sur la carte) |
| Passerelle GPS Traccar | CI | **10 tests** dont 5 de bout en bout sur PostgreSQL ; l'écho d'un point relayé à Traccar n'est jamais stocké deux fois |
| Messagerie temps réel (WebSocket pilote) | CI | **4 tests** de bout en bout avec de vrais clients WebSocket |
| Client bureau JavaFX | CI + hors ligne | **24 tests** (client HTTP lot 2, catalogue 3D, projection Web Mercator de la carte) ; écran **Suivi GPS** en direct |
| Paquet **DEB** | CI (ubuntu-22.04) | Produit par jpackage (~38 Mo), **installé**, **application lancée** sous Xvfb (23 écrans du parcours de démonstration capturés par l'application installée), puis désinstallée proprement |
| Paquet **EXE** Windows | CI (windows-2022) | Produit par jpackage + WiX (~51 Mo). Non signé ; pas encore installé ni lancé automatiquement |
| Application **Android** | CI | Compilation, **lint bloquant**, **4 tests unitaires** ; APK ~66 Mo (MapLibre + Filament natifs) ; onglet **Carte** MapLibre en direct ; permissions vérifiées dans l'APK (aapt2) : pas de localisation en arrière-plan |
| **Pile d'hébergement complète** | CI (`tools/smoke-stack.sh`) | Les 3 images Docker démarrent avec PostGIS via le kit CAMTEL : carte de flotte alimentée, mission démarrée par le conducteur, lot Android accepté, relais Traccar **sans doublon**, téléphone **déclaré automatiquement dans Traccar**, position refusée après clôture |
| Images Docker | CI → GHCR | `ghcr.io/rito27959-arch/nexfleet-{api,gps-gateway,traccar}:0.5.0` publiées à chaque version |
| Contrats OpenAPI | CI | `openapi.yaml` et `openapi-lot2.yaml` (0.3.0, alignés sur l'implémentation) valides |
| **Refonte « Liquid Glass teinté » (iOS 26)** | CI + `docs/RECHERCHE_DESIGN_IOS26.md` | Bureau et Android redessinés écran par écran (verre teinté sur la navigation seulement, capsules, rayons concentriques, typographie iOS, alertes iOS 26, réglage « Réduire la transparence »). Contrastes du verre au pire cas vérifiés par `tools/check_contrast.py` ; captures bureau dans `docs/design-reference/captures-v2-ios26/` ; captures Android prises sur émulateur (Pixel 7, API 34) par la CI, branche `captures-android` |
| Captures | `tools/screenshots.sh` | Lot 2 : 22 captures + 2 GIF, dont `23-catalogue-3d.png` (rendu 3D JavaFX réel) |

Scénario lot 2 de l'API (`MissionChatAssistantIntegrationTest`), exécuté sur PostGIS réel :
création de mission → refus de l'auto-autorisation → autorisation par un tiers → cloisonnement Douala/Yaoundé →
démarrage refusé au gestionnaire et avec une notice inconnue → démarrage par le conducteur avec **jeton de mission
vérifié** (signature, mission) → second démarrage refusé → message envoyé deux fois sans doublon → états Envoyé, Distribué,
Lu → conversation refusée à un non-participant et à l'auditeur → clôture → assistant désactivé par défaut, activation refusée
au gestionnaire et avec un signataire identique, puis acceptée → question chiffrée, demande d'écriture refusée, conducteur
sans droit refusé → comptes réservés à l'admin, journal lisible par l'auditeur, **chaîne vérifiée intacte par le serveur**.

## Ce qui est écrit mais pas encore prouvé

| Élément | Statut | Condition pour le déclarer livré |
|---|---|---|
| Collecte GPS sur un vrai téléphone | Code compilé et testé unitairement ; jamais exécuté sur un appareil | Essai terrain : téléphone + passerelle + API, positions reçues pendant la mission et refusées après |
| Boîtier GPS véhicule via Traccar | Traccar 6.16 réel exécuté en CI (relais, déclaration d'appareil) ; aucun boîtier physique | Un boîtier configuré sur le port de son protocole, position reçue |
| Vue 3D (Filament) Android | Compilée, lint vert. **Carte MapLibre et tous les autres écrans observés sur émulateur** (Pixel 7, API 34, captures en CI). La vue 3D, elle, fige l'émulateur du runner : sans GPU, son rendu passe par SwiftShader et la capture revient vide | Capture sur un vrai téléphone OpenGL ES 3 |
| Fond de carte du bureau | Tuiles OpenStreetMap (usage modéré) ; non observé ici (réseau fermé), repères hors ligne affichés | Serveur de tuiles CAMTEL ou contrat d'usage |
| Hébergement Railway | Prêt (images publiques) ; **création du projet refusée par Railway** : panne de leurs constructions le 20/10/2026 (« Deploys have been paused temporarily ») | Relancer quand Railway rétablit le service |
| Lancement de l'EXE | Installateur produit ; application non lancée sous Windows | Installation et connexion sur un poste Windows CAMTEL |
| Bureau ↔ API réelle | Client HTTP et WebSocket testés contre un faux serveur ; routes testées côté API | Recette de bout en bout sur un serveur de test |
| Kit CAMTEL avec HTTPS (Caddy) | Pile éprouvée en CI sans Caddy (ports locaux) | Installation sur un serveur CAMTEL avec ses noms DNS |

## Ce qui n'est pas commencé

Pièces jointes du chat, messagerie temps réel sur mobile (le mobile interroge l'API REST ; le temps réel GPS, lui, est fait), écrans carburant / maintenance /
budget / rapports côté bureau, création de comptes (fédération OIDC / RH), photo / détourage / reconnaissance de véhicule,
modèle de langage local pour l'assistant (moteur de règles actuel `RULES_FR_V1`), signature de code Windows, APK signé
avec la clé CAMTEL.

## Décisions attendues

| Décision | Qui | Impact |
|---|---|---|
| Clé de signature Android et certificat de signature Windows | DSIR | APK et EXE de production |
| Serveur de tuiles (interne ou OpenStreetMap selon licence) | DSIR | Fond de carte des écrans Missions |
| Seuils GPS (précision, saut, arrêt, « en direct ») | Métier + DS | Remplacent `TrackPolicy.demoDefaults()` |
| Texte de la notice d'information GPS (`NOTICE-GPS-v1`) | DS + juridique | Affichée avant chaque démarrage de mission |
| Validation de sécurité de l'assistant | DS / RSSI | Référence exigée pour l'activer |
| Durée de vie du jeton de mission (12 h par défaut) | DS | `NF_MISSION_TOKEN_TTL` |

## Prochain lot proposé (lot 4), dans l'ordre

1. Installer le kit `infra/deploy/camtel` sur un serveur de test CAMTEL (ou Railway quand le service revient).
2. Essai terrain : un téléphone Android démarre MIS-2026-015 et apparaît en direct sur le bureau et la carte mobile.
3. Boîtier GPS véhicule raccordé à Traccar ; messagerie WebSocket sur le mobile.
4. Écrans carburant et maintenance (tables et règles déjà prêtes en base).
5. Signature des paquets avec les clés CAMTEL et publication par étiquette `v0.5.0`.
