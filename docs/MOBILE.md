# Application Android NexFleet

Java natif (sans Kotlin ni AppCompat), `apps/android/`. Module `domain` partagé avec le serveur
(`services/nextfleet-domain` : jeton de mission, règles de messagerie, profils).

## Construction

```bash
cd apps/android
gradle testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Outils : AGP 9.3.1, Gradle 9.5.0, compileSdk 37, targetSdk 36, minSdk 26, Java 17. Construit et testé à chaque push par la CI
(job « Android — APK ») ; le lint Android est bloquant.

## Écrans par profil

| Onglet | Profils | Contenu |
|---|---|---|
| Carte | gestionnaire, contrôleur | Flotte en direct (MapLibre) : marqueurs colorés **et** libellés, fiche du véhicule, état de la connexion |
| Missions | conducteur, gestionnaire, contrôleur | Missions affectées ou du périmètre ; démarrage (conducteur affecté seulement) et clôture |
| Trajet | conducteur | Tracé de la mission en cours, état du collecteur et du tampon |
| Messages | profils avec la messagerie (pas l’admin ni l’auditeur) | Conversations de mission, envoi idempotent, états Envoyé / Distribué / Lu |
| Catalogue 3D | tous | Modèles 3D de la flotte affichés avec Google Filament (rotation, zoom), licence et source |
| Profil | tous | Compte, droits, déconnexion |

## Collecte GPS : quand et comment

1. Le conducteur ouvre une mission **autorisée** et appuie sur Démarrer.
2. La notice d'information (`NOTICE-GPS-v1`) s'affiche ; sans accord, rien ne démarre.
3. L'API vérifie que c'est le conducteur affecté, enregistre l'autorisation de collecte et renvoie un **jeton de mission**
   (HMAC-SHA256, lié à la mission et au téléphone, expire après 12 h par défaut).
4. Un service de premier plan (`foregroundServiceType=location`, notification permanente) collecte les positions GPS et réseau,
   ignore les positions simulées, les chiffre (AES-256-GCM, clé dans l'AndroidKeyStore) dans une base SQLite locale, et les
   envoie par lots toutes les 20 s à la passerelle (`POST /gps/android/fixes`, en-tête `Authorization: Mission <jeton>`).
5. À la clôture, le service s'arrête, le tampon est vidé puis purgé ; la base révoque l'autorisation : toute position
   postérieure est refusée par le serveur.

Hors réseau, les positions restent chiffrées dans le tampon (purge après 7 jours) et partent au retour de la connexion ;
un lot rejoué ne crée pas de doublon (identifiant client unique par position).

## Permissions déclarées

| Permission | Pourquoi |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | API et passerelle ; reprise des envois |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Positions pendant une mission démarrée |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | Collecte visible par notification, uniquement en mission |
| `POST_NOTIFICATIONS` | Notification de collecte (Android 13+) |

Volontairement **absent** : `ACCESS_BACKGROUND_LOCATION`. Aucune collecte hors mission. Autres réglages :
`allowBackup=false`, trafic HTTPS uniquement (clair autorisé seulement vers l'émulateur `10.0.2.2` et `localhost`),
OpenGL ES 3 requis pour la vue 3D.

## Carte en direct

MapLibre Native 13.6.1, style vectoriel OpenFreeMap « liberty » (gratuit, sans clé, attribution OpenStreetMap affichée).
Positions poussées par le canal WebSocket du serveur (`/api/v1/live`, OkHttp, reconnexion progressive), états calculés
par le serveur : en mouvement (vert), à l'arrêt (bleu), signal ancien (gris), à vérifier (orange). Un serveur de tuiles
CAMTEL pourra remplacer OpenFreeMap (constante `MapPage.STYLE`).

## Modèles 3D

Rendu : Filament 1.77.1 (`filament-android`, `gltfio-android`, `filament-utils-android`), fichiers `.glb` non compressés dans l'APK.
Catalogue commun au bureau : `shared/assets3d/catalogue.json` ; licences : `shared/assets3d/LICENCES.md` ; ajout d'un
modèle : `tools/import-3d-model.md`. Seuls les modèles redistribuables sont chargés.

## Ce qui n'est pas encore prouvé

- Essai sur un téléphone physique avec la passerelle et Traccar réels (seuls les tests unitaires et la compilation sont exécutés en CI).
- Comportement des surcouches constructeurs qui arrêtent les services de premier plan (Tecno, Infinix, Xiaomi…) : à mesurer.
- Messagerie en temps réel : l'application interroge l'API REST (le temps réel GPS, lui, passe par WebSocket).
- Taille de l'APK : ~66 Mo (bibliothèques natives MapLibre et Filament pour ARM et x86_64) ; des APK par architecture la réduiraient de moitié.

## Mode production (0.6.0)

- **Serveur** : production par défaut (`https://api-production-a1d8.up.railway.app`), modifiable depuis la connexion ou
  Profil → Serveur. Démonstration hors ligne : sept appuis sur le logo de l'écran de connexion.
- **Mot de passe provisoire** : remplacement imposé à la première connexion (le serveur refuse tout le reste) ;
  changement volontaire depuis le profil.
- **Mon véhicule** (onglet Missions) : demander un bon de carburant, déclarer un plein avec photo du reçu, signaler une
  panne, faire le contrôle avant départ. Chaque saisie est revérifiée par le serveur (droits, solde du bon, compteur).
- **Messagerie** : nouvelle conversation, photos et PDF joints (5 max, 10 Mo chacun), messages reçus en temps réel ;
  les pièces jointes s'ouvrent dans une visionneuse interne et ne quittent pas le cache privé de l'application.
