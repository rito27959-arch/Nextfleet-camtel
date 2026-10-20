#!/usr/bin/env bash
# Recrée une base vierge, applique les migrations dans l'ordre Flyway, exécute les tests d'intégrité
# puis un test de concurrence réelle (deux sessions PostgreSQL simultanées sur le même bon).
# Prérequis : PostgreSQL 16 + PostGIS ; variables PGHOST/PGUSER/PGPASSWORD si besoin ; superutilisateur via sudo -u postgres.
set -euo pipefail
DB=${NF_TEST_DB:-nextfleet_test}
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADMIN_PSQL=${NF_ADMIN_PSQL:-"sudo -u postgres psql -q -v ON_ERROR_STOP=1"}
APP_PSQL="psql -q -v ON_ERROR_STOP=1 -h localhost -U nextfleet -d $DB"
export PGPASSWORD=${PGPASSWORD:-nextfleet_dev_only}

$ADMIN_PSQL -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB OWNER nextfleet"
$ADMIN_PSQL -d "$DB" -c "CREATE EXTENSION postgis; CREATE EXTENSION btree_gist; CREATE EXTENSION pgcrypto;"

for f in $(ls "$ROOT"/db/migrations/V*__*.sql | sort -V); do
  echo "→ migration $(basename "$f")"
  $APP_PSQL -f "$f" > /dev/null
done

echo "→ tests d'intégrité"
$APP_PSQL -f "$ROOT/db/tests/db_tests.sql"

echo "→ test de concurrence CAR-01 (deux sessions simultanées, bon de 60 L : 40 L et 30 L)"
$APP_PSQL <<'SQL'
INSERT INTO fuel_order(id, order_number, vehicle_id, supplier_id, org_unit_id, requested_by, approved_by, approved_at,
                       authorized_liters, authorized_amount, valid_until, status, is_demo)
VALUES ('00000000-0000-0000-0000-0000000009e1','BON-CONC','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d1',
        '00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002', now(),
        60, 100000, '2026-12-31','SUBMITTED', true);
UPDATE fuel_order SET status='APPROVED' WHERE id='00000000-0000-0000-0000-0000000009e1';
SQL
fill() { # $1 litres  $2 reçu
  $APP_PSQL -c "BEGIN; INSERT INTO fuel_fill(fuel_order_id, vehicle_id, supplier_id, filled_at, liters, unit_price, amount, full_tank, receipt_reference, entered_by)
    VALUES ('00000000-0000-0000-0000-0000000009e1','00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000d1',
    '2026-10-10 08:00+01', $1, 800, $1*800, false, '$2', '00000000-0000-0000-0000-000000000003'); SELECT pg_sleep(1); COMMIT;" 2>&1
}
TMP=$(mktemp -d)
fill 40 CONC-A > "$TMP/a.log" & PID_A=$!
fill 30 CONC-B > "$TMP/b.log" & PID_B=$!
set +e; wait $PID_A; RA=$?; wait $PID_B; RB=$?; set -e
DELIVERED=$($APP_PSQL -tA -c "SELECT delivered_liters FROM fuel_order WHERE id='00000000-0000-0000-0000-0000000009e1'")
FILLS=$($APP_PSQL -tA -c "SELECT count(*) FROM fuel_fill WHERE fuel_order_id='00000000-0000-0000-0000-0000000009e1'")
echo "   session A : $(grep -o 'CAR-01[^(]*' "$TMP/a.log" || echo acceptée)"
echo "   session B : $(grep -o 'CAR-01[^(]*' "$TMP/b.log" || echo acceptée)"
echo "   pleins acceptés=$FILLS, délivré=${DELIVERED} L"
if [[ "$FILLS" == "1" && ( "$DELIVERED" == "40.00" || "$DELIVERED" == "30.00" ) ]]; then
  echo "   OK : un seul plein accepté, solde jamais dépassé"
else
  echo "   ÉCHEC du test de concurrence"; exit 1
fi
echo "Campagne base de données : SUCCÈS"
