#!/usr/bin/env bash
# Essai de bout en bout de la pile d'hébergement (infra/deploy/camtel) : base, API, passerelle GPS, Traccar.
# Parcours : amorçage DEMO → connexion → carte de flotte (simulateur) → démarrage de mission par le conducteur →
# lot Android à jeton de mission → stockage, relais Traccar sans doublon → appareil déclaré dans Traccar → clôture.
# Prérequis : images ghcr.io/rito27959-arch/nexfleet-*:ci présentes localement, docker compose, curl, jq.
set -euo pipefail
cd "$(dirname "$0")/../infra/deploy/camtel"
rnd() { openssl rand -base64 48 | tr -d '\n/+=' | cut -c1-40; }
cat > .env <<ENV
NF_VERSION=ci
NF_ENVIRONMENT=DEMO
NF_API_DOMAIN=api.localhost
NF_GPS_DOMAIN=gps.localhost
NF_DB_PASSWORD=$(rnd)
NF_MISSION_TOKEN_KEY=$(openssl rand -base64 48 | tr -d '\n')
NF_TRACCAR_FORWARD_SECRET=$(rnd)
NF_TRACCAR_PASSWORD=$(rnd)
NF_BOOTSTRAP_ADMIN_PASSWORD=$(rnd)
NF_DEMO_PASSWORD=Demo-Essai-CI-2026
NF_DEMO_SIMULATOR=true
ENV
DC="docker compose -f compose.yaml -f compose.ci.yaml"
$DC up -d db api gps-gateway traccar
fail() { echo "ÉCHEC : $*"; $DC logs --tail 80 api gps-gateway traccar || true; exit 1; }
for i in $(seq 1 60); do curl -fs http://127.0.0.1:8080/actuator/health >/dev/null && break; sleep 3; done
curl -fs http://127.0.0.1:8080/actuator/health | grep -q UP || fail "API non démarrée"
curl -fs http://127.0.0.1:8090/health | grep -q UP || fail "passerelle non démarrée"
login() { curl -fs -H 'Content-Type: application/json' -d "{\"login\":\"$1\",\"password\":\"Demo-Essai-CI-2026\",\"clientKind\":\"ANDROID\"}" \
  http://127.0.0.1:8080/api/v1/auth/login | jq -r .token; }
MGR=$(login gestionnaire); DRV=$(login conducteur)
[ -n "$MGR" ] && [ "$MGR" != null ] || fail "connexion gestionnaire"
sleep 25
# réponses JSON compressées (gzip) et revalidables par ETag (304 sans corps)
HDR=$(curl -fs -o /dev/null -D - -H 'Accept-Encoding: gzip' -H "Authorization: Bearer $MGR" http://127.0.0.1:8080/api/v1/vehicles | tr -d '\r')
echo "$HDR" | grep -qi '^content-encoding: gzip' || fail "réponse JSON non compressée"
TAG=$(echo "$HDR" | grep -i '^etag:' | cut -d' ' -f2-)
[ -n "$TAG" ] || fail "ETag absent"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "If-None-Match: $TAG" -H 'Accept-Encoding: gzip' -H "Authorization: Bearer $MGR" http://127.0.0.1:8080/api/v1/vehicles)
[ "$CODE" = 304 ] || fail "revalidation ETag : $CODE au lieu de 304"
echo "OK gzip + ETag/304"
N=$(curl -fs -H "Authorization: Bearer $MGR" http://127.0.0.1:8080/api/v1/fleet/live | jq length)
[ "$N" -ge 2 ] || fail "carte de flotte : $N véhicule(s)"
echo "OK carte de flotte : $N véhicules en mission (simulateur de démonstration)"
MID=$(curl -fs -H "Authorization: Bearer $DRV" http://127.0.0.1:8080/api/v1/missions | jq -r '.[] | select(.status=="AUTHORIZED") | .id' | head -1)
START=$(curl -fs -H "Authorization: Bearer $DRV" -H 'Content-Type: application/json' \
  -d '{"noticeVersion":"NOTICE-GPS-v1","deviceId":"AND-CI0001"}' "http://127.0.0.1:8080/api/v1/missions/$MID/start")
TOKEN=$(echo "$START" | jq -r .missionToken)
[ "$(echo "$START" | jq -r .mission.status)" = ACTIVE ] || fail "démarrage de mission : $START"
echo "OK mission démarrée par le conducteur, jeton de mission reçu"
sleep 5   # déclaration asynchrone de l'appareil dans Traccar
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
R=$(curl -fs -H "Authorization: Mission $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"fixes\":[{\"clientFixId\":\"ci-1\",\"recordedAt\":\"$NOW\",\"lat\":3.8667,\"lon\":11.5167,\"accuracyM\":5,\"speedMps\":10}]}" \
  http://127.0.0.1:8090/gps/android/fixes)
[ "$(echo "$R" | jq -r '.results[0].outcome')" = ACCEPTED ] || fail "lot Android : $R"
echo "OK lot Android accepté ; relais Traccar : $(echo "$R" | jq -r .relayedToTraccar)"
sleep 8
COUNT=$($DC exec -T db psql -U nexfleet -d nexfleet -tAc "SELECT count(*) FROM location_point WHERE source_device_id='AND-CI0001'")
[ "$COUNT" = 1 ] || fail "doublon après l'écho Traccar : $COUNT points"
echo "OK aucun doublon après l'écho de Traccar"
TU=$(grep NF_TRACCAR_PASSWORD .env | cut -d= -f2-)
DEV=$(curl -fs -u "service@nexfleet.local:$TU" "http://127.0.0.1:8082/api/devices?uniqueId=AND-CI0001" | jq length)
[ "$DEV" = 1 ] || fail "appareil absent de Traccar"
echo "OK téléphone déclaré automatiquement dans Traccar par l'API"
curl -fs -X POST -H "Authorization: Bearer $DRV" "http://127.0.0.1:8080/api/v1/missions/$MID/finish" | jq -e '.status=="FINISHED"' >/dev/null || fail "clôture"
sleep 2   # horodatage à la seconde : un point de la même seconde que la clôture est légitimement antérieur
R2=$(curl -fs -H "Authorization: Mission $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"fixes\":[{\"clientFixId\":\"ci-2\",\"recordedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"lat\":3.87,\"lon\":11.52,\"accuracyM\":5}]}" \
  http://127.0.0.1:8090/gps/android/fixes)
[ "$(echo "$R2" | jq -r '.results[0].outcome')" = REJECTED ] || fail "position après clôture : $R2"
echo "OK après la clôture, toute position est refusée"
$DC down -v >/dev/null 2>&1 || true
rm -f .env
echo "Pile d'hébergement : SUCCÈS"
