# Installation — NexFleet CAMTEL 0.8.0

Ce guide couvre les trois livrables produits par la CI GitHub Actions (gratuite pour ce dépôt public) et le serveur.
Aucune autorisation CAMTEL n'est revendiquée : c'est une version pilote à installer sur des postes et des serveurs de test.

## 1. Récupérer les paquets

Chaque exécution réussie de la CI (onglet **Actions** du dépôt) publie ces artefacts, conservés 90 jours :

| Artefact | Contenu |
|---|---|
| `nexfleet-bureau-deb` | `nexfleet_0.8.0_amd64.deb` (construit sur Ubuntu 22.04 : Ubuntu 22.04 / 24.04, Debian 12+ ; JRE et JavaFX embarqués) |
| `nexfleet-bureau-exe` | `NexFleet-0.8.0.msi` (recommandé), `NexFleet-0.8.0-windows-portable.zip` (sans installation) et `NexFleet-0.8.0.exe` (Windows 10/11, par utilisateur, sans droits administrateur) |
| `nexfleet-android-apk` | `nexfleet-0.8.0-debug.apk` et `nexfleet-0.8.0-release-unsigned.apk` (ou `-release.apk` si signé) |
| `nexfleet-api-jar` | Jar exécutable de l'API Spring Boot |

**Version publiée** : la release [v0.8.0](https://github.com/rito27959-arch/Nextfleet-camtel/releases/tag/v0.8.0) contient
l'APK, le DEB, le MSI, la version portable Windows, l'EXE et le jar de l'API. Pour publier une version suivante : changer `APP_VERSION` dans
`.github/workflows/ci.yml`, puis pousser une étiquette `vX.Y.Z` ou lancer le workflow « ci » à la main avec « publier » coché.

## 2. Serveur (API + base + passerelle GPS + Traccar)

### Serveur de production en service

| Service | Adresse |
|---|---|
| API (bureau et Android s'y connectent par défaut) | `https://api-production-a1d8.up.railway.app` |
| Passerelle GPS (téléphones, relais Traccar) | `https://gps-gateway-production.up.railway.app` |

Hébergé sur Railway (projet `nexfleet-camtel`, environnement `production`) : base PostGIS et Traccar sur volumes
persistants, `NF_ENVIRONMENT=PRODUCTION`, aucune donnée fictive. Les secrets (mot de passe de la base, clé des jetons de
mission, compte Traccar) sont dans les variables Railway, jamais dans le dépôt.

**Premier démarrage** : se connecter avec l'identifiant `admin` et le mot de passe provisoire remis séparément ; l'application
impose aussitôt un mot de passe personnel. L'administrateur crée ensuite les entités (Réglages → Comptes) puis les comptes :
chacun reçoit un mot de passe provisoire affiché une seule fois, à transmettre en main propre, et doit le changer à sa
première connexion.

### Installer sur un serveur CAMTEL

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

**Linux** : `sudo apt install ./nexfleet_0.8.0_amd64.deb`, puis menu Bureautique → NexFleet, ou `/opt/nexfleet/bin/NexFleet`.
La CI installe réellement ce paquet, lance l'application installée sous Xvfb (parcours de démonstration complet), puis la désinstalle, à chaque exécution.

**Windows** (10/11, sans droits administrateur) — trois formats, au choix :

1. **`NexFleet-0.8.0.msi` (recommandé)** : double-cliquer, raccourci créé dans le menu Démarrer. La CI installe réellement
   ce MSI, lance l'application installée (parcours complet), puis la désinstalle, à chaque exécution.
2. **`NexFleet-0.8.0-windows-portable.zip`** : sans installation. Décompresser (clic droit → Extraire tout), puis lancer
   `NexFleet\NexFleet.exe`. Également lancée par la CI.
3. `NexFleet-0.8.0.exe` : ancien installateur. Il décompresse un MSI dans `%TEMP%` avant de l'exécuter ; si un antivirus
   ou une politique de poste bloque ce dossier, il échoue avec « CreateProcess … msiexec.exe … main.msi failed, system error 2 ».
   Utiliser alors le MSI ou la version portable.

Les paquets ne sont pas signés (certificat de signature de code CAMTEL à fournir) : Windows SmartScreen demandera une
confirmation (« Informations complémentaires » → « Exécuter quand même »).

Lancement :

- sans option → **serveur de production** ci-dessus (modifiable sur l'écran de connexion, ligne « Serveur », ou dans
  Réglages ; le choix est mémorisé sur le poste) ;
- `NexFleet --api=https://nexfleet.camtel.cm/` → autre serveur (HTTPS obligatoire hors `localhost`) ;
- `NexFleet --demo` → démonstration avec données fictives (badge DÉMO, mot de passe `demo`), sans serveur ;
- `--tiles=https://tuiles.camtel.cm/{z}/{x}/{y}.png` → fond de carte CAMTEL (par défaut : OpenStreetMap, usage modéré).

En mode serveur, le démarrage d'une mission se fait depuis le téléphone (le poste bureau n'est pas un émetteur GPS).

## 4. Téléphone Android

Android 8.0 (API 26) minimum. Voir [`MOBILE.md`](MOBILE.md) pour les écrans et les permissions.

1. Copier l'APK sur le téléphone et autoriser « Installer des applications inconnues » pour le gestionnaire de fichiers.
   En flotte, préférer la distribution par la console MDM de la DSIR.
2. Ouvrir NexFleet : il se connecte au serveur de production. Pour un autre serveur, toucher « Serveur » sous le
   formulaire de connexion (ou Profil → Serveur). La démonstration hors ligne reste accessible en touchant sept fois le logo.
   À la première connexion, l'application impose le remplacement du mot de passe provisoire.
3. Au premier démarrage de mission, Android demande la localisation « pendant l'utilisation » et, sur Android 13+, les notifications.

L'APK `debug` est signé avec une clé de débogage : pour essai uniquement. Pour un APK de production signé, renseigner dans
les secrets du dépôt `NF_KEYSTORE_BASE64`, `NF_KEYSTORE_PASSWORD`, `NF_KEY_ALIAS`, `NF_KEY_PASSWORD` ; la CI signe alors l'APK release.
