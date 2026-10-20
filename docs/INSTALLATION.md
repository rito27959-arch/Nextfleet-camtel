# Installation — NexFleet CAMTEL 0.3.0

Ce guide couvre les trois livrables produits par la CI GitHub Actions (gratuite pour ce dépôt public) et le serveur.
Aucune autorisation CAMTEL n'est revendiquée : c'est une version pilote à installer sur des postes et des serveurs de test.

## 1. Récupérer les paquets

Chaque exécution réussie de la CI (onglet **Actions** du dépôt) publie ces artefacts, conservés 90 jours :

| Artefact | Contenu |
|---|---|
| `nexfleet-bureau-deb` | `nexfleet_0.3.0_amd64.deb` (construit sur Ubuntu 22.04 : Ubuntu 22.04 / 24.04, Debian 12+ ; JRE et JavaFX embarqués) |
| `nexfleet-bureau-exe` | `NexFleet-0.3.0.exe` (Windows 10/11, installation par utilisateur, sans droits administrateur) |
| `nexfleet-android-apk` | `nexfleet-0.3.0-debug.apk` et `nexfleet-0.3.0-release-unsigned.apk` (ou `-release.apk` si signé) |
| `nexfleet-api-jar` | Jar exécutable de l'API Spring Boot |

**Version publiée** : la release [v0.3.0](https://github.com/rito27959-arch/Nextfleet-camtel/releases/tag/v0.3.0) contient
l'APK, le DEB, l'EXE et le jar de l'API. Pour publier une version suivante : changer `APP_VERSION` dans
`.github/workflows/ci.yml`, puis pousser une étiquette `vX.Y.Z` ou lancer le workflow « ci » à la main avec « publier » coché.

## 2. Serveur (API + base + passerelle GPS)

Prérequis : PostgreSQL 16 avec PostGIS 3.4, Java 21. Extensions créées par le DBA : `postgis`, `btree_gist`, `pgcrypto`.

Variables d'environnement (aucun secret dans le dépôt) :

| Variable | Rôle |
|---|---|
| `NF_DB_URL`, `NF_DB_USER`, `NF_DB_PASSWORD` | Connexion PostgreSQL |
| `NF_ENVIRONMENT` | `DEV`, `TEST`, `DEMO`, `PREPROD` ou `PRODUCTION` (les données de démonstration sont refusées en production) |
| `NF_MISSION_TOKEN_KEY` | Clé HMAC en base64, 32 octets minimum, **identique** pour l'API et la passerelle GPS. Générer : `openssl rand -base64 48` |
| `NF_GPS_PUBLIC_URL` | Adresse HTTPS de la passerelle GPS vue par les téléphones (renvoyée au démarrage de mission) |
| `NF_GPS_NOTICE_VERSIONS` | Versions de notice acceptées (défaut `NOTICE-GPS-v1`) |
| `NF_TRACCAR_FORWARD_SECRET` | Secret partagé avec `infra/traccar/traccar.xml` (passerelle) |

```bash
java -jar nextfleet-api-*.jar                     # applique les migrations Flyway V1–V7 au démarrage
java -cp gps-traccar-adapter.jar:nextfleet-domain.jar:postgresql.jar:jackson-*.jar \
     cm.camtel.nextfleet.gps.GpsGatewayMain        # écoute NF_GPS_BIND (défaut 127.0.0.1:8090)
```

Sans `NF_MISSION_TOKEN_KEY`, l'API démarre mais refuse tout démarrage de mission (409 « Passerelle GPS non configurée ») :
aucune collecte n'est possible par erreur. L'API et la passerelle doivent être exposées en HTTPS par le frontal de la DSIR.

## 3. Poste bureau

**Linux** : `sudo apt install ./nexfleet_0.3.0_amd64.deb`, puis menu Bureautique → NexFleet, ou `/opt/nexfleet/bin/NexFleet`.
La CI installe réellement ce paquet, lance l'application installée sous Xvfb (parcours de démonstration complet), puis la désinstalle, à chaque exécution.

**Windows** : double-cliquer sur `NexFleet-0.3.0.exe`, choisir le dossier, raccourci créé dans le menu Démarrer.
Le paquet n'est pas signé (certificat de signature de code CAMTEL à fournir) : Windows SmartScreen demandera une confirmation.

Lancement :

- sans option → **mode démonstration** (badge DÉMO, six comptes, mot de passe `demo`) ;
- `NexFleet --api=https://nexfleet.camtel.invalid/` → **mode serveur** (HTTPS obligatoire hors `localhost`).

En mode serveur, le démarrage d'une mission se fait depuis le téléphone (le poste bureau n'est pas un émetteur GPS).

## 4. Téléphone Android

Android 8.0 (API 26) minimum. Voir [`MOBILE.md`](MOBILE.md) pour les écrans et les permissions.

1. Copier l'APK sur le téléphone et autoriser « Installer des applications inconnues » pour le gestionnaire de fichiers.
   En flotte, préférer la distribution par la console MDM de la DSIR.
2. Ouvrir NexFleet. Le commutateur **Démonstration / Serveur** choisit la source ; en mode serveur, saisir l'adresse HTTPS.
3. Au premier démarrage de mission, Android demande la localisation « pendant l'utilisation » et, sur Android 13+, les notifications.

L'APK `debug` est signé avec une clé de débogage : pour essai uniquement. Pour un APK de production signé, renseigner dans
les secrets du dépôt `NF_KEYSTORE_BASE64`, `NF_KEYSTORE_PASSWORD`, `NF_KEY_ALIAS`, `NF_KEY_PASSWORD` ; la CI signe alors l'APK release.
