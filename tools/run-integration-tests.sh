#!/usr/bin/env bash
# Tests d'intégration GPS (Traccar) et messagerie contre un vrai PostgreSQL 16 + PostGIS.
# Chaque module part d'une base vierge : migrations V1..Vn + jeu fictif infra/db/tests/it_fixture.sql.
# Prérequis : tools/build-offline.sh déjà exécuté ; superutilisateur via sudo -u postgres ; rôle « nextfleet ».
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build-offline"
JAVA_LIBS=${JAVA_LIBS:-/usr/share/java}
DB=${NF_IT_DB:-nextfleet_it}
ADMIN_PSQL=${NF_ADMIN_PSQL:-"sudo -u postgres psql -q -v ON_ERROR_STOP=1"}
export PGPASSWORD=${PGPASSWORD:-nextfleet_dev_only}
APP_PSQL="psql -q -v ON_ERROR_STOP=1 -h localhost -U nextfleet -d $DB"
JUNIT="$JAVA_LIBS/junit-platform-console-standalone.jar"
[[ -f "$JUNIT" ]] || JUNIT=$(ls "$JAVA_LIBS"/junit-platform-console-standalone-*.jar | head -1)
CP="$OUT/domain:$JAVA_LIBS/jackson-databind.jar:$JAVA_LIBS/jackson-core.jar:$JAVA_LIBS/jackson-annotations.jar:$JAVA_LIBS/postgresql.jar:${JAVA_WEBSOCKET_JAR:-$JAVA_LIBS/java_websocket.jar}"

fresh_db() {
  $ADMIN_PSQL -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB OWNER nextfleet" > /dev/null
  $ADMIN_PSQL -d "$DB" -c "CREATE EXTENSION postgis; CREATE EXTENSION btree_gist; CREATE EXTENSION pgcrypto;" > /dev/null
  for f in $(ls "$ROOT"/infra/db/migrations/V*__*.sql | sort -V); do $APP_PSQL -f "$f" > /dev/null 2>&1; done
  $APP_PSQL -f "$ROOT/infra/db/tests/it_fixture.sql" > /dev/null
}

for MOD in gps-traccar-adapter chat-realtime; do
  echo "== $MOD : base vierge + jeu fictif"
  fresh_db
  echo "== $MOD : tests d'intégration"
  java -Dstdout.encoding=UTF-8 -Dnf.db.url="jdbc:postgresql://localhost:5432/$DB" -Dnf.db.user=nextfleet -Dnf.db.password="$PGPASSWORD" \
       -jar "$JUNIT" execute --class-path "$CP:$OUT/$MOD:$OUT/$MOD-test" --scan-class-path --details=tree \
       --disable-banner --disable-ansi-colors --reports-dir "$OUT/test-reports/$MOD-it"
done
echo "Tests d'intégration : SUCCÈS"
