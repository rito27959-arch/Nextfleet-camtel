# Construction, tests et paquets

## 1. Chaîne hors ligne (vérifiée le 20/10/2026)

Prérequis Ubuntu 24.04 : `openjdk-21-jdk openjfx junit5 libjackson2-databind-java postgresql-16 postgresql-16-postgis-3 xvfb python3`.

```bash
# Base de données : migrations V1–V3 + 32 tests d'intégrité + test de concurrence
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

## 2. Chaîne Maven (cible ; NON exécutée — Maven Central inaccessible lors de la réalisation)

```bash
mvn -B verify                                   # domaine + API (Testcontainers : Docker requis) + bureau
mvn -pl services/nextfleet-api spring-boot:run  # API ; variables NF_DB_URL, NF_DB_USER, NF_DB_PASSWORD
mvn -pl apps/desktop-javafx javafx:run -Djavafx.args="--api=https://serveur/"
```

## 3. Paquets bureau avec jpackage (à exécuter sur chaque système cible)

`jpackage` ne produit un installateur que pour le système sur lequel il tourne : DEB sous Linux, MSI/EXE sous Windows.
JavaFX doit être fourni sous forme de **jmods** (téléchargés depuis gluonhq.com/products/javafx pour la plateforme visée).

```bash
mvn -B -pl apps/desktop-javafx -am package -DskipTests
# Linux (DEB)
jpackage --type deb --name nexfleet --app-version 0.1.0 --vendor "CAMTEL" \
  --input apps/desktop-javafx/target/libs --main-jar ../nextfleet-desktop-0.1.0-SNAPSHOT.jar \
  --main-class cm.camtel.nextfleet.desktop.NextfleetDesktopApp \
  --module-path "$JAVAFX_JMODS" --add-modules javafx.controls,javafx.swing,java.net.http \
  --linux-shortcut --linux-menu-group Office
# Windows (MSI ; EXE avec --type exe) — nécessite WiX Toolset
jpackage --type msi --name NexFleet --app-version 0.1.0 --vendor "CAMTEL" ^
  --input apps\desktop-javafx\target\libs --main-jar ..\nextfleet-desktop-0.1.0-SNAPSHOT.jar ^
  --main-class cm.camtel.nextfleet.desktop.NextfleetDesktopApp ^
  --module-path "%JAVAFX_JMODS%" --add-modules javafx.controls,javafx.swing,java.net.http ^
  --win-menu --win-shortcut --win-dir-chooser
```

Statut : **aucun DEB/EXE/MSI produit dans ce lot.** Les commandes ci-dessus sont à éprouver lors du premier build Maven.

## 4. Android (APK)

Statut : **non commencé**. Le Gradle Android et le SDK exigent `services.gradle.org`, `dl.google.com` et `maven.google.com`,
inaccessibles lors de la réalisation. Le lot 2 créera `apps/android-java` (Java + XML, ViewModel/Repository) avec :
`./gradlew assembleDebug` (APK de vérification) ; APK de production signé uniquement avec les clés détenues par CAMTEL.
