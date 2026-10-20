#!/usr/bin/env bash
# Publie les lignes utiles d'un journal de build en annotations GitHub (lisibles par l'API quand le journal brut ne l'est pas).
# Usage : tools/ci-annotate.sh journal.log "Titre"
LOG=$1; TITLE=${2:-build}
[[ -f "$LOG" ]] || exit 0
enc() { sed -e 's/%/%25/g' -e 's/\r//g' | awk 'BEGIN{ORS="%0A"} {print}' ; }
# 1) erreurs de compilation / tests (au plus 120 lignes)
ERR=$(grep -E "\[ERROR\]|error:|FAILED|FAILURE|Exception|What went wrong|e: |Execution failed|Caused by|> Task .*FAILED|Could not" "$LOG" | grep -v "^\s*at " | head -120 | cut -c1-400 | enc)
[[ -n "$ERR" ]] && echo "::error title=${TITLE} — erreurs::${ERR}"
# 2) fin du journal (150 lignes)
TAIL=$(tail -150 "$LOG" | cut -c1-300 | enc)
echo "::error title=${TITLE} — fin du journal::${TAIL}"
