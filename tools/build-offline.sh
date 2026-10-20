#!/usr/bin/env bash
# Construction HORS Maven, pour un poste sans accès à Maven Central.
# Utilise uniquement le JDK 21 et des bibliothèques système Ubuntu (paquets openjfx, junit5, libjackson2-databind-java).
#   sudo apt-get install openjdk-21-jdk openjfx junit5 libjackson2-databind-java
# Avec Maven Central accessible, utiliser plutôt : mvn -B verify  (voir README).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build-offline"
JAVA_LIBS=${JAVA_LIBS:-/usr/share/java}
FX_LIB=${FX_LIB:-/usr/share/openjfx/lib}
rm -rf "$OUT"; mkdir -p "$OUT"

echo "== nextfleet-domain : compilation"
DOM="$ROOT/services/nextfleet-domain"
javac --release 21 -Xlint:all -Werror -d "$OUT/domain" $(find "$DOM/src/main/java" -name '*.java')

echo "== nextfleet-domain : tests JUnit 5"
JUNIT="$JAVA_LIBS/junit-platform-console-standalone.jar"
[[ -f "$JUNIT" ]] || JUNIT=$(ls "$JAVA_LIBS"/junit-platform-console-standalone-*.jar | head -1)
javac --release 21 -d "$OUT/domain-test" -cp "$OUT/domain:$JUNIT" $(find "$DOM/src/test/java" -name '*.java')
java -Dstdout.encoding=UTF-8 -jar "$JUNIT" execute --class-path "$OUT/domain:$OUT/domain-test" --scan-class-path \
     --details=tree --disable-banner --disable-ansi-colors --reports-dir "$OUT/test-reports/domain"

JACKSON="$JAVA_LIBS/jackson-databind.jar:$JAVA_LIBS/jackson-core.jar:$JAVA_LIBS/jackson-annotations.jar"
PG="$JAVA_LIBS/postgresql.jar"
WS=${JAVA_WEBSOCKET_JAR:-$JAVA_LIBS/java_websocket.jar}
for MOD in gps-traccar-adapter chat-realtime; do
  M="$ROOT/services/$MOD"
  [[ -d "$M/src/main/java" ]] || continue
  echo "== $MOD : compilation"
  javac --release 21 -Xlint:all -Werror -d "$OUT/$MOD" -cp "$OUT/domain:$JACKSON:$PG:$WS" $(find "$M/src/main/java" -name '*.java')
  echo "== $MOD : tests unitaires (les tests d'intégration exigent tools/run-integration-tests.sh)"
  javac --release 21 -d "$OUT/$MOD-test" -cp "$OUT/domain:$OUT/$MOD:$JACKSON:$PG:$WS:$JUNIT" $(find "$M/src/test/java" -name '*.java')
  java -Dstdout.encoding=UTF-8 -jar "$JUNIT" execute --class-path "$OUT/domain:$OUT/$MOD:$OUT/$MOD-test:$JACKSON:$PG:$WS" \
       --scan-class-path --details=tree --disable-banner --disable-ansi-colors --reports-dir "$OUT/test-reports/$MOD"
done

if [[ "${SKIP_DESKTOP:-0}" != "1" && -d "$ROOT/apps/desktop-javafx/src/main/java" ]]; then
  echo "== desktop-javafx : compilation"
  DESK="$ROOT/apps/desktop-javafx"
  JACKSON="$JAVA_LIBS/jackson-databind.jar:$JAVA_LIBS/jackson-core.jar:$JAVA_LIBS/jackson-annotations.jar"
  javac --release 21 -Xlint:all,-requires-transitive-automatic,-module -d "$OUT/desktop" \
        --module-path "$FX_LIB" --add-modules javafx.controls,javafx.swing \
        -cp "$OUT/domain:$JACKSON" $(find "$DESK/src/main/java" -name '*.java')
  cp -r "$DESK/src/main/resources/." "$OUT/desktop/"
  mkdir -p "$OUT/desktop/cm/camtel/nextfleet/desktop/assets3d" && cp -r "$ROOT/shared/assets3d/." "$OUT/desktop/cm/camtel/nextfleet/desktop/assets3d/"
  if [[ -d "$DESK/src/test/java" ]]; then
    echo "== desktop-javafx : tests"
    javac --release 21 -d "$OUT/desktop-test" --module-path "$FX_LIB" --add-modules javafx.controls \
          -cp "$OUT/domain:$OUT/desktop:$JACKSON:$JUNIT" $(find "$DESK/src/test/java" -name '*.java')
    java --module-path "$FX_LIB" --add-modules javafx.controls,javafx.swing -jar "$JUNIT" execute \
         --class-path "$OUT/domain:$OUT/desktop:$OUT/desktop-test:$JACKSON" --scan-class-path \
         --details=tree --disable-banner --disable-ansi-colors --reports-dir "$OUT/test-reports/desktop"
  fi
fi
echo "Construction hors ligne : SUCCÈS ($OUT)"
