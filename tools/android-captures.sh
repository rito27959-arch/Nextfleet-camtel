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
shot() { sleep "${2:-5}"; timeout 30 adb exec-out screencap -p > "$OUT/$1.png"; echo "capture $1"; }
start() { timeout 60 adb shell am start -W -S -n "$PKG/.ui.LoginActivity" "$@" >/dev/null; }

adb shell pm clear "$PKG" >/dev/null; adb install -r -g "$APK" >/dev/null
adb shell am start -W -n "$PKG/.ui.LoginActivity" >/dev/null; shot 01-connexion 4
start --es captureDemoLogin gestionnaire --ei captureTab 0; shot 02-gestionnaire-missions
start --es captureDemoLogin gestionnaire --ei captureTab 1; shot 03-gestionnaire-carte 14
start --es captureDemoLogin gestionnaire --ei captureTab 2; shot 04-gestionnaire-messages
start --es captureDemoLogin gestionnaire --ei captureTab 2 --es captureThread conv-014; shot 05-fil-de-messages
start --es captureDemoLogin gestionnaire --ei captureTab 3; shot 06-catalogue-3d
start --es captureDemoLogin gestionnaire --ei captureTab 4; shot 07-profil
start --es captureDemoLogin conducteur --ei captureTab 0; shot 08-conducteur-mes-missions
start --es captureDemoLogin conducteur --ei captureTab 1; shot 09-conducteur-trajet
# Vue 3D Filament en dernier : sous SwiftShader (pas de GPU sur le runner), le rendu GLES3 peut faire tomber l'émulateur.
adb logcat -c
start --es captureDemoLogin gestionnaire --ei captureTab 3 --es captureModel nexfleet-pickup; shot 11-vue-3d-filament 12
if adb get-state >/dev/null 2>&1; then
  timeout 30 adb logcat -d -b main -b crash > "$OUT/logcat-3d.txt" 2>/dev/null
  grep -E "FATAL|SIGSEGV|SIGABRT|Filament|filament|EGL|GLES|AndroidRuntime" "$OUT/logcat-3d.txt" | tail -n 8 | while read -r l; do echo "::warning title=Vue 3D (émulateur)::${l:0:240}"; done
  echo "::notice title=Vue 3D (émulateur)::appareil toujours joignable après l'ouverture de la vue 3D"
else
  echo "::warning title=Vue 3D (émulateur)::l'émulateur ne répond plus après l'ouverture de la vue 3D (rendu logiciel SwiftShader)"
fi
find "$OUT" -name '*.png' -size 0 -delete
ls -la "$OUT"
