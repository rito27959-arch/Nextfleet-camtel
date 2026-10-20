# État réel d'avancement — version pilote 0.3.0

**20 octobre 2026.** Ce document distingue ce qui a été exécuté et vérifié de ce qui est seulement écrit.
Aucune autorisation CAMTEL, aucun avis DSIR ou DS et aucune mise en production ne sont revendiqués.

## Ce qui fonctionne réellement (preuves exécutées)

Toutes les lignes marquées **CI** sont rejouées à chaque push par GitHub Actions (`.github/workflows/ci.yml`) ; les
résultats sont lisibles dans l'onglet Actions (annotations « bilan »).

| Élément | Où | Résultat obtenu |
|---|---|---|
| Migrations V1–V7 sur PostgreSQL 16 + PostGIS 3.4 | CI + `infra/db/tests/run_db_tests.sh` | Appliquées sur base vierge |
| Tests d'intégrité de la base | idem | **63 tests réussis** + concurrence CAR-01 (un seul plein accepté sur deux simultanés). Nouveau : journal vérifiable depuis un autre fuseau horaire (V7) |
| Noyau métier Java 21 | CI (Maven) + `tools/build-offline.sh` | **42 tests** |
| API Spring Boot | CI (Maven, Testcontainers PostGIS) | **6 tests d'intégration** : lot 1 (connexion, cloisonnement, doublons, chaîne carburant) et scénario lot 2 complet ci-dessous |
| Passerelle GPS Traccar | CI | **9 tests** dont 5 de bout en bout sur PostgreSQL |
| Messagerie temps réel (WebSocket pilote) | CI | **4 tests** de bout en bout avec de vrais clients WebSocket |
| Client bureau JavaFX | CI + hors ligne | **21 tests** (dont client HTTP du lot 2 et catalogue 3D) |
| Paquet **DEB** | CI (ubuntu-22.04) | Produit par jpackage (~38 Mo), **installé**, **application lancée** sous Xvfb (23 écrans du parcours de démonstration capturés par l'application installée), puis désinstallée proprement |
| Paquet **EXE** Windows | CI (windows-2022) | Produit par jpackage + WiX (~51 Mo). Non signé ; pas encore installé ni lancé automatiquement |
| Application **Android** | CI | Compilation, **lint bloquant**, **4 tests unitaires** ; APK de débogage (~36 Mo) et de publication minifié ; permissions vérifiées dans l'APK (aapt2) : pas de localisation en arrière-plan |
| Contrats OpenAPI | CI | `openapi.yaml` et `openapi-lot2.yaml` (0.3.0, alignés sur l'implémentation) valides |
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
| Traccar Server réel | `infra/traccar/traccar.xml` écrit ; binaire non exécuté | Traccar démarré, position d'un boîtier reçue par la passerelle |
| Vue 3D Android (Filament) | Compilée, lint vert ; rendu jamais observé sur écran | Capture sur un appareil OpenGL ES 3 |
| Lancement de l'EXE | Installateur produit ; application non lancée sous Windows | Installation et connexion sur un poste Windows CAMTEL |
| Bureau ↔ API réelle | Client HTTP testé contre un faux serveur ; routes testées côté API | Recette de bout en bout sur un serveur de test |
| `infra/compose.local.yaml` | Non exécuté | `docker compose up` sur le poste DSIR |

## Ce qui n'est pas commencé

Pièces jointes du chat, messagerie temps réel sur mobile (le mobile interroge l'API REST), écrans carburant / maintenance /
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

1. Essai terrain : un téléphone Android, la passerelle GPS et l'API sur un serveur de test CAMTEL (scénario B complet).
2. Traccar réel avec un boîtier GPS véhicule.
3. Messagerie WebSocket dans l'API et sur le mobile.
4. Écrans carburant et maintenance (tables et règles déjà prêtes en base).
5. Signature des paquets avec les clés CAMTEL et publication par étiquette `v0.4.0`.
