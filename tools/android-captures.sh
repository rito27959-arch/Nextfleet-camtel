#!/usr/bin/env bash
# Recette visuelle Android sur émulateur (CI) : installe l'APK de débogage et capture chaque écran par profil.
# Les intentions de capture n'existent que dans l'APK de débogage (FLAG_DEBUGGABLE).
set -uo pipefail
APK=${1:?APK de débogage}
OUT=${2:-android-shots}
PKG=cm.camtel.nextfleet.mobile
mkdir -p "$OUT"
adb install -r -g "$APK" >/dev/null
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
shot() { sleep "${2:-5}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "capture $1"; }
start() { adb shell am start -W -S -n "$PKG/.ui.LoginActivity" "$@" >/dev/null; }

adb shell pm clear "$PKG" >/dev/null; adb install -r -g "$APK" >/dev/null
adb shell am start -W -n "$PKG/.ui.LoginActivity" >/dev/null; shot 01-connexion 4
start --es captureDemoLogin gestionnaire --ei captureTab 0; shot 02-gestionnaire-missions
start --es captureDemoLogin gestionnaire --ei captureTab 1; shot 03-gestionnaire-carte 14
start --es captureDemoLogin gestionnaire --ei captureTab 2; shot 04-gestionnaire-messages
adb shell am start -W -n "$PKG/.ui.ThreadActivity" --es id conv-014 --es title MIS-2026-014 >/dev/null; shot 05-fil-de-messages
start --es captureDemoLogin gestionnaire --ei captureTab 3; shot 06-catalogue-3d
adb shell am start -W -n "$PKG/.ui.ModelViewerActivity" --es id nexfleet-pickup >/dev/null; shot 07-vue-3d-filament 8
start --es captureDemoLogin gestionnaire --ei captureTab 4; shot 08-profil
start --es captureDemoLogin conducteur --ei captureTab 0; shot 09-conducteur-mes-missions
start --es captureDemoLogin conducteur --ei captureTab 1; shot 10-conducteur-trajet
ls -la "$OUT"
