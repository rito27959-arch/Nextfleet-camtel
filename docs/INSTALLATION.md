# Installation — NexFleet CAMTEL 0.4.0

Ce guide couvre les trois livrables produits par la CI GitHub Actions (gratuite pour ce dépôt public) et le serveur.
Aucune autorisation CAMTEL n'est revendiquée : c'est une version pilote à installer sur des postes et des serveurs de test.

## 1. Récupérer les paquets

Chaque exécution réussie de la CI (onglet **Actions** du dépôt) publie ces artefacts, conservés 90 jours :

| Artefact | Contenu |
|---|---|
| `nexfleet-bureau-deb` | `nexfleet_0.4.0_amd64.deb` (construit sur Ubuntu 22.04 : Ubuntu 22.04 / 24.04, Debian 12+ ; JRE et JavaFX embarqués) |
| `nexfleet-bureau-exe` | `NexFleet-0.4.0.exe` (Windows 10/11, installation par utilisateur, sans droits administrateur) |
| `nexfleet-android-apk` | `nexfleet-0.4.0-debug.apk` et `nexfleet-0.4.0-release-unsigned.apk` (ou `-release.apk` si signé) |
| `nexfleet-api-jar` | Jar exécutable de l'API Spring Boot |

**Version publiée** : la release [v0.4.0](https://github.com/rito27959-arch/Nextfleet-camtel/releases/tag/v0.4.0) contient
l'APK, le DEB, l'EXE et le jar de l'API. Pour publier une version suivante : changer `APP_VERSION` dans
`.github/workflows/ci.yml`, puis pousser une étiquette `vX.Y.Z` ou lancer le workflow « ci » à la main avec « publier » coché.

## 2. Serveur (API + base + passerelle GPS + Traccar)

Utiliser le kit **[`infra/deploy/camtel`](../infra/deploy/camtel/README.md)** : Docker Compose, HTTPS automatique (Caddy),
sauvegarde quotidienne, images publiées par la CI sur `ghcr.io/rito27959-arch/`. La même pile est démarrée et éprouvée
de bout en bout à chaque exécution de la CI (`tools/smoke-stack.sh`).

Variables principales (détail dans `.env.example`) :

| Variable | Rôle |
|---|---|
| `NF_ENVIRONMENT` | `PREPROD`, `PRODUCTION`, ou `DEMO` (données fictives autorisées) |
| `NF_BOOTSTRAP_ADMIN_*` | Administrateur technique créé au premier démarrage |
| `NF_MISSION_TOKEN_KEY` | Clé HMAC partagée par l'API et la passerelle (`openssl rand -base64 48`) |
| `NF_TRACCAR_*` | Compte de service Traccar créé automatiquement sur une instance neuve ; secret du forward |
| `NF_DEMO_PASSWORD`, `NF_DEMO_SIMULATOR` | Démonstration : comptes fictifs, véhicules simulés |

Sans `NF_MISSION_TOKEN_KEY`, l'API refuse tout démarrage de mission (409) : aucune collecte n'est possible par erreur.

## 3. Poste bureau

**Linux** : `sudo apt install ./nexfleet_0.4.0_amd64.deb`, puis menu Bureautique → NexFleet, ou `/opt/nexfleet/bin/NexFleet`.
La CI installe réellement ce paquet, lance l'application installée sous Xvfb (parcours de démonstration complet), puis la désinstalle, à chaque exécution.

**Windows** : double-cliquer sur `NexFleet-0.4.0.exe`, choisir le dossier, raccourci créé dans le menu Démarrer.
Le paquet n'est pas signé (certificat de signature de code CAMTEL à fournir) : Windows SmartScreen demandera une confirmation.

Lancement :

- sans option → **mode démonstration** (badge DÉMO, six comptes, mot de passe `demo`) ;
- `NexFleet --api=https://nexfleet.camtel.invalid/` → **mode serveur** (HTTPS obligatoire hors `localhost`) ;
- `--tiles=https://tuiles.camtel.cm/{z}/{x}/{y}.png` → fond de carte CAMTEL (par défaut : OpenStreetMap, usage modéré).

En mode serveur, le démarrage d'une mission se fait depuis le téléphone (le poste bureau n'est pas un émetteur GPS).

## 4. Téléphone Android

Android 8.0 (API 26) minimum. Voir [`MOBILE.md`](MOBILE.md) pour les écrans et les permissions.

1. Copier l'APK sur le téléphone et autoriser « Installer des applications inconnues » pour le gestionnaire de fichiers.
   En flotte, préférer la distribution par la console MDM de la DSIR.
2. Ouvrir NexFleet. Le commutateur **Démonstration / Serveur** choisit la source ; en mode serveur, saisir l'adresse HTTPS.
3. Au premier démarrage de mission, Android demande la localisation « pendant l'utilisation » et, sur Android 13+, les notifications.

L'APK `debug` est signé avec une clé de débogage : pour essai uniquement. Pour un APK de production signé, renseigner dans
les secrets du dépôt `NF_KEYSTORE_BASE64`, `NF_KEYSTORE_PASSWORD`, `NF_KEY_ALIAS`, `NF_KEY_PASSWORD` ; la CI signe alors l'APK release.
