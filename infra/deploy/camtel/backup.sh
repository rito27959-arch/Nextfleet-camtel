#!/bin/sh
# Sauvegarde quotidienne (02:30) de la base NexFleet, format custom compressé ; conservation RETENTION_DAYS jours.
# Restauration : pg_restore -h db -U nexfleet -d nexfleet --clean backups/nexfleet-AAAAMMJJ-HHMM.dump
set -eu
while true; do
  now=$(date +%H%M)
  if [ "$now" = "0230" ] || [ ! -f /backups/.premiere ]; then
    f="/backups/nexfleet-$(date +%Y%m%d-%H%M).dump"
    if pg_dump -h db -U nexfleet -d nexfleet -Fc -f "$f.tmp"; then
      mv "$f.tmp" "$f" && touch /backups/.premiere && echo "sauvegarde $f"
    else
      echo "ÉCHEC de la sauvegarde" >&2; rm -f "$f.tmp"
    fi
    find /backups -name 'nexfleet-*.dump' -mtime +"$RETENTION_DAYS" -delete
    sleep 61
  fi
  sleep 30
done
