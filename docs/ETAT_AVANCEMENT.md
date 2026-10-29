# État réel d'avancement — version 0.8.0 (refonte « NexFleet Pro »)

**29 octobre 2026.** Ce document distingue ce qui a été exécuté et vérifié de ce qui est seulement écrit.
Aucune autorisation CAMTEL, aucun avis DSIR ou DS et aucune mise en production ne sont revendiqués.

## Ce qui fonctionne réellement (preuves exécutées)

Toutes les lignes marquées **CI** sont rejouées à chaque push par GitHub Actions (`.github/workflows/ci.yml`) ; les
résultats sont lisibles dans l'onglet Actions (annotations « bilan »).

| Élément | Où | Résultat obtenu |
|---|---|---|
| Migrations V1–V9 sur PostgreSQL 16 + PostGIS 3.4 | CI + `infra/db/tests/run_db_tests.sh` | Appliquées sur base vierge |
| Tests d'intégrité de la base | idem | **63 tests réussis** + concurrence CAR-01 (un seul plein accepté sur deux simultanés). V9 : fichiers privés, pièces jointes du chat (5 max, déposées par l'expéditeur), notifications lues, mot de passe provisoire |
| Noyau métier Java 21 | CI (Maven) + `tools/build-offline.sh` | **42 tests** |
| API Spring Boot | CI (Maven, Testcontainers PostGIS) | **9 tests d'intégration** : parcours de production sur instance vide, lot 1, scénario lot 2 ci-dessous, **temps réel** (point accepté → WebSocket filtré par droits, autre entité exclue, carte de flotte calculée par PostGIS) et **amorçage DEMO** (comptes fictifs, simulateur visible sur la carte) |
| Passerelle GPS Traccar | CI | **10 tests** dont 5 de bout en bout sur PostgreSQL ; l'écho d'un point relayé à Traccar n'est jamais stocké deux fois |
| Messagerie temps réel (WebSocket pilote) | CI | **4 tests** de bout en bout avec de vrais clients WebSocket |
| Client bureau JavaFX | CI + hors ligne | **24 tests** (client HTTP lot 2, catalogue 3D, projection Web Mercator de la carte) ; écran **Suivi GPS** en direct |
| Paquet **DEB** | CI (ubuntu-22.04) | Produit par jpackage (~38 Mo), **installé**, **application lancée** sous Xvfb (23 écrans du parcours de démonstration capturés par l'application installée), puis désinstallée proprement |
| Paquets **Windows** | CI (windows-2022) | MSI (recommandé), ZIP portable et EXE produits par jpackage + WiX. Non signés. **MSI installé, application installée lancée (26 écrans capturés) puis désinstallée ; version portable lancée sans installation**, à chaque exécution |
| Application **Android** | CI | Compilation, **lint bloquant**, **4 tests unitaires** ; APK ~66 Mo (MapLibre + Filament natifs) ; onglet **Carte** MapLibre en direct ; permissions vérifiées dans l'APK (aapt2) : pas de localisation en arrière-plan |
| **Pile d'hébergement complète** | CI (`tools/smoke-stack.sh`) | Les 3 images Docker démarrent avec PostGIS via le kit CAMTEL : carte de flotte alimentée, mission démarrée par le conducteur, lot Android accepté, relais Traccar **sans doublon**, téléphone **déclaré automatiquement dans Traccar**, position refusée après clôture |
| Images Docker | CI → GHCR | `ghcr.io/rito27959-arch/nexfleet-{api,gps-gateway,traccar}:<version>` et `:latest` publiées à chaque version |
| Contrats OpenAPI | CI | `openapi.yaml` et `openapi-lot2.yaml` (0.3.0, alignés sur l'implémentation) valides |
| **Refonte « Liquid Glass teinté » (iOS 26)** | CI + `docs/RECHERCHE_DESIGN_IOS26.md` | Bureau et Android redessinés écran par écran (verre teinté sur la navigation seulement, capsules, rayons concentriques, typographie iOS, alertes iOS 26, réglage « Réduire la transparence »). Contrastes du verre au pire cas vérifiés par `tools/check_contrast.py` ; captures bureau dans `docs/design-reference/captures-v2-ios26/` ; captures Android prises sur émulateur (Pixel 7, API 34) par la CI, branche `captures-android` |
| Captures | `tools/screenshots.sh` | Lot 2 : 22 captures + 2 GIF, dont `23-catalogue-3d.png` (rendu 3D JavaFX réel) |

### Refonte « NexFleet Pro » (0.8.0)

Fondée sur une recherche documentée (leaders du marché, apps iOS primées) : [`RECHERCHE_REFERENCE_MARCHE.md`](RECHERCHE_REFERENCE_MARCHE.md).

| Élément | Preuve |
|---|---|
| Bureau : accueil « À traiter » (documents expirés ou proches, permis, demandes d'atelier, véhicules immobilisés, bons à valider, missions à autoriser), classé par urgence, motif et action en un clic | Compilation, parcours de captures (clair et sombre) |
| Score de santé par véhicule (0–100) avec ses motifs ; santé de la flotte et véhicules les plus fragiles ; anneau dans la liste | Idem ; barème documenté dans `Health.java` |
| Fiche véhicule 360° pleine page : identité, santé, chiffres clés, échéances, historique unifié (interventions, vidanges, pleins) | Idem |
| Navigation regroupée Opérations · Parc · Pilotage ; palette neutre à un seul accent, texte renforcé | Idem ; contrastes vérifiés (`tools/check_contrast.py`) |
| Android : carte de mission selon la phase (compte à rebours, temps écoulé à la seconde, trajet animé, compteurs de positions en chiffres fixes), tuiles terrain, écran « Papiers du véhicule », 4 onglets pour le conducteur | Compilation, lint et recette émulateur en CI ; **à éprouver sur un vrai téléphone** |

### Performance et finitions (0.7.0)

| Élément | Preuve |
|---|---|
| Réponses JSON compressées (gzip) et revalidées par ETag (304 sans corps) | `tools/smoke-stack.sh` contre les vraies images Docker (en-tête `Content-Encoding: gzip`, 304 sur `If-None-Match`) ; test d'intégration : 304 sans corps, droits revérifiés (401 sans jeton malgré l'ETag), aucun ETag sur une écriture, `Cache-Control: no-store` conservé |
| Administrateur d'amorçage obligé de changer son mot de passe en PRODUCTION | `ProductionBootstrapIntegrationTest` (PostGIS réel) ; s'applique aussi au compte `admin` déjà créé sur Railway tant qu'il n'a pas choisi son mot de passe |
| Bureau : appels réseau en parallèle sur fils virtuels, réaffichage instantané des écrans déjà vus puis actualisation silencieuse, cache vidé à chaque modification et à la déconnexion | Compilation + parcours de captures |
| Bureau : recherche universelle Ctrl+K (écrans, véhicules, conducteurs, missions ; clavier ↑ ↓ ↵ Échap ; correspondance surlignée ; récents), fondu enchaîné entre écrans, défilement fluide à la molette, raccourcis Ctrl+1…9 / Ctrl+R / Ctrl+,, fenêtre et thème mémorisés | Captures `34-recherche-universelle*.png` |
| Android : transitions iOS (pages qui glissent, formulaires qui montent), capsule d'onglet qui glisse en ressort, appui qui enfonce, retours haptiques (onglet, succès, refus), secousse sur erreur, tirer pour actualiser, squelettes de chargement, états vides illustrés, bulles de message qui apparaissent comme iMessage | Compilation, lint et recette émulateur en CI ; **sensations haptiques non vérifiables sur émulateur** (vrai téléphone requis) |
| Android : dernières données affichées instantanément et consultables hors connexion (bandeau « Hors connexion · données du … ») ; stockage privé de l'application, effacé à la connexion et à la déconnexion | Compilation en CI ; **comportement hors connexion à essayer sur un vrai téléphone** |

### Mode production (0.6.0)

| Élément | Preuve |
|---|---|
| **Serveur de production en ligne** (Railway, projet `nexfleet-camtel`) | API `https://api-production-a1d8.up.railway.app` et passerelle GPS `https://gps-gateway-production.up.railway.app` répondent `{"status":"UP"}` ; journaux : migrations V1–V9 appliquées, « environnement déclaré PRODUCTION », administrateur technique `admin` créé, compte de service Traccar créé. Base PostGIS et Traccar sur volumes persistants. **Aucune donnée de démonstration en production** |
| Parcours complet sur instance vide (`ProductionFlowIntegrationTest`) | CI, PostGIS réel : admin → entité → comptes à mot de passe provisoire (toute autre route refusée tant qu'il n'est pas changé, code `PASSWORD_CHANGE_REQUIRED`) → droits (pas d'auto-attribution) → véhicule, conducteur, permis, affectation → document avec fichier privé (PDF/JPEG/PNG/WebP vérifiés par signature, 10 Mo max) → échéances et notifications → entretien (demande → qualification → clôture avec visa) → bon de carburant validé par un tiers, plein avec reçu → coûts / budget importé → rapports CSV (export tracé) → chat avec pièce jointe et poussée temps réel aux seuls participants |
| Bureau : tous les écrans branchés sur l'API | Entretiens (atelier et gestionnaire), Carburant, Coûts et budget, Rapports, Échéances, Conducteurs, Missions (créer, autoriser, annuler), Véhicules (fiche, documents, affectation), Comptes (création, droits, réinitialisation), Messages (nouvelle conversation, pièces jointes, temps réel), Mot de passe, choix du serveur. Plus aucun écran « À venir ». Démonstration seulement avec `--demo` |
| Android : terrain en production | Serveur de production par défaut ; démonstration cachée (7 appuis sur le logo). Mot de passe provisoire imposé, demande de bon et plein avec photo du reçu, signalement de panne, contrôle avant départ, messagerie avec pièces jointes (visionneuse photo/PDF intégrée) et temps réel |

Scénario lot 2 de l'API (`MissionChatAssistantIntegrationTest`), exécuté sur PostGIS réel :
création réservée au gestionnaire → autorisation par ce même gestionnaire (auditée) → refus aux autres profils et hors périmètre → cloisonnement Douala/Yaoundé →
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
| Poste Windows CAMTEL | Installation et lancement prouvés en CI (Windows Server 2022) | Connexion depuis un poste CAMTEL réel (antivirus, proxy) |
| Recette métier par CAMTEL | Routes prouvées par les tests d'intégration ; serveur de production joignable ; aucune recette par les utilisateurs CAMTEL | Saisie réelle d'une entité, de ses véhicules et comptes, puis une semaine d'usage |
| Kit CAMTEL avec HTTPS (Caddy) | Pile éprouvée en CI sans Caddy (ports locaux) | Installation sur un serveur CAMTEL avec ses noms DNS |

## Ce qui n'est pas commencé

Fédération des comptes (OIDC / annuaire RH : les comptes sont créés par l'administrateur), photo / détourage / reconnaissance
de véhicule, modèle de langage local pour l'assistant (moteur de règles `RULES_FR_V1`), envoi de notifications par SMS ou
courriel (elles sont affichées dans l'application), signature de code Windows, APK signé avec la clé CAMTEL, nom de
domaine CAMTEL pour le serveur.

## Décisions attendues

| Décision | Qui | Impact |
|---|---|---|
| Clé de signature Android et certificat de signature Windows | DSIR | APK et EXE de production |
| Serveur de tuiles (interne ou OpenStreetMap selon licence) | DSIR | Fond de carte des écrans Missions |
| Seuils GPS (précision, saut, arrêt, « en direct ») | Métier + DS | Remplacent `TrackPolicy.demoDefaults()` |
| Texte de la notice d'information GPS (`NOTICE-GPS-v1`) | DS + juridique | Affichée avant chaque démarrage de mission |
| Validation de sécurité de l'assistant | DS / RSSI | Référence exigée pour l'activer |
| Durée de vie du jeton de mission (12 h par défaut) | DS | `NF_MISSION_TOKEN_TTL` |

## Prochaines étapes proposées, dans l'ordre

1. L'administrateur se connecte avec le compte `admin`, change son mot de passe, crée les entités et les comptes.
2. Essai terrain : un téléphone Android démarre une mission et apparaît en direct sur le bureau et la carte mobile.
3. Boîtier GPS véhicule raccordé à Traccar.
4. Nom de domaine CAMTEL, sauvegardes de la base hors Railway, signature des paquets avec les clés CAMTEL.

### Autorisation des missions (0.7.2)

Le gestionnaire de flotte peut créer puis autoriser sa propre mission depuis sa fiche. La création reste un brouillon : le bouton **Autoriser** enregistre une décision explicite avec son auteur et sa date. Les autres profils ne peuvent ni créer ni autoriser une mission ; le périmètre organisationnel reste contrôlé. Seul le conducteur affecté démarre ensuite la mission et sa collecte GPS. Les brouillons déjà présents bénéficient du correctif serveur, sans migration ni réimport.
