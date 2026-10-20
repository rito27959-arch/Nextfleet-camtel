#!/usr/bin/env bash
# Parcours de recette visuelle du client JavaFX (mode démo) et captures 1440×900.
# Linux : nécessite Xvfb si aucun affichage (xvfb-run). Usage : tools/screenshots.sh [dossier]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT=${1:-"$ROOT/docs/design-reference/captures-lot1"}
B="$ROOT/build-offline"; J=${JAVA_LIBS:-/usr/share/java}; FX=${FX_LIB:-/usr/share/openjfx/lib}
[[ -d "$B/desktop" ]] || "$ROOT/tools/build-offline.sh"
RUN=(java -Dprism.order=sw -Dprism.lcdtext=false --module-path "$FX" --add-modules javafx.controls,javafx.swing
     -cp "$B/desktop:$B/domain:$J/jackson-databind.jar:$J/jackson-core.jar:$J/jackson-annotations.jar"
     cm.camtel.nextfleet.desktop.NextfleetDesktopApp --demo --screenshot="$OUT" ${NF_TOUR:+--tour=$NF_TOUR})
if [[ -z "${DISPLAY:-}" ]]; then xvfb-run -a -s "-screen 0 1600x1000x24" "${RUN[@]}"; else "${RUN[@]}"; fi
