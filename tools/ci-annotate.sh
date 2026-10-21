#!/usr/bin/env bash
# Publie les lignes utiles d'un journal de build en annotations GitHub (lisibles par l'API quand le journal brut ne l'est pas).
# Chaque annotation est limitée à ~3 500 caractères (au-delà, GitHub l'ignore) ; les échecs de tests passent en premier.
# Usage : tools/ci-annotate.sh journal.log "Titre"
LOG=$1; TITLE=${2:-build}
[[ -f "$LOG" ]] || exit 0
enc() { sed -e 's/%/%25/g' -e 's/\r//g' | head -c 3500 | awk 'BEGIN{ORS="%0A"} {print}' ; }
# 1) échecs de tests JUnit (surefire) : en-tête de l'échec, message, premières lignes de pile du projet
TF=$(grep -E -A14 "<<< (ERROR|FAILURE)!" "$LOG" | grep -E "<<<|Error|Exception|expected|but was|Assert|at cm\.camtel|Caused by|Status|Response|^\s*[A-Za-z].*:" \
     | grep -v "^\s*at org\.\|^\s*at java\.\|^\s*at jdk\." | head -60 | cut -c1-300 | enc)
[[ -n "$TF" ]] && echo "::error title=${TITLE} — tests en échec::${TF}"
# 2) bilan surefire (lignes « Tests run » en échec)
TR=$(grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])|FAIL" "$LOG" | head -20 | cut -c1-300 | enc)
[[ -n "$TR" ]] && echo "::error title=${TITLE} — bilan des échecs::${TR}"
# 3) erreurs de compilation et de build (lignes [ERROR] uniquement)
ERR=$(grep -E "\[ERROR\]|error:|What went wrong|Execution failed|> Task .*FAILED" "$LOG" | grep -v "^\s*at \|Could not transfer\|Help 1\|re-run\|Re-run" | head -60 | cut -c1-300 | enc)
[[ -n "$ERR" ]] && echo "::error title=${TITLE} — erreurs::${ERR}"
# 4) fin du journal
TAIL=$(tail -60 "$LOG" | grep -v "SCENE3D\|javafx.scene" | cut -c1-250 | enc)
echo "::error title=${TITLE} — fin du journal::${TAIL}"
exit 0
