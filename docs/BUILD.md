# Construction, tests et paquets

## 1. Chaîne hors ligne (vérifiée le 20/10/2026)

Prérequis Ubuntu 24.04 : `openjdk-21-jdk openjfx junit5 libjackson2-databind-java postgresql-16 postgresql-16-postgis-3 xvfb python3`.

```bash
# Base de données : migrations V1–V7 + 63 tests d'intégrité + test de concurrence
sudo -u postgres psql -c "create role nextfleet login password 'nextfleet_dev_only' createdb;"
infra/db/tests/run_db_tests.sh

# Noyau métier + client JavaFX : compilation et tests JUnit 5
tools/build-offline.sh

# Contrastes du design system (WCAG 2.2)
python3 tools/check_contrast.py

# Client bureau en mode démonstration
java --module-path /usr/share/openjfx/lib --add-modules javafx.controls,javafx.swing \
     -cp build-offline/desktop:build-offline/domain:/usr/share/java/jackson-databind.jar:/usr/share/java/jackson-core.jar:/usr/share/java/jackson-annotations.jar \
     cm.camtel.nextfleet.desktop.NextfleetDesktopApp --demo

# Captures de recette visuelle 1440×900 (Xvfb si pas d'écran)
tools/screenshots.sh
```

## 2. Chaîne Maven (exécutée par la CI GitHub Actions à chaque push)

```bash
mvn -B verify                                   # domaine, API (Testcontainers : Docker requis), passerelles, bureau
mvn -pl services/nextfleet-api spring-boot:run  # API ; variables : docs/INSTALLATION.md §2
mvn -pl apps/desktop-javafx javafx:run -Djavafx.args="--api=https://serveur/"
```

## 3. Paquets bureau (jpackage, produits par la CI)

`jpackage` ne produit un installateur que pour le système sur lequel il tourne. La CI le fait gratuitement sur des machines
GitHub : DEB sur `ubuntu-24.04` (puis installation réelle du paquet pour vérification), EXE sur `windows-2022` (WiX préinstallé).
JavaFX est pris dans les jars à classifieur de plateforme copiés par Maven (`jp/fx`), le JRE est réduit par `--add-modules`.
La commande exacte est dans `.github/workflows/ci.yml`, job « Bureau ».

Reproduire en local (Linux) :

```bash
mvn -B -ntp -pl apps/desktop-javafx -am package -DskipTests
# puis les étapes « Préparer l'image d'application » et « jpackage » du workflow, avec matrix.type=deb
```

Non fait : signature de code Windows (certificat CAMTEL requis), paquet MSI (l'EXE suffit au pilote), paquet macOS.

## 4. Android (APK, produit par la CI)

```bash
cd apps/android && gradle testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Gradle 9.5.0, AGP 9.3.1, SDK 37. APK de débogage (installable pour essai) et APK de publication minifié, signé seulement si
les secrets `NF_KEYSTORE_*` sont définis. Détails : [`MOBILE.md`](MOBILE.md).
