#!/usr/bin/env python3
"""Vérifie les contrastes WCAG 2.2 des couples texte/fond du design system (objectif 4,5:1, cahier V4 chap. 16)."""
import json
import sys
from pathlib import Path

tokens = json.loads((Path(__file__).resolve().parent.parent / "shared/ui-tokens/tokens.json").read_text(encoding="utf-8"))


def lum(hex_color):
    h = hex_color.lstrip("#")
    rgb = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    lin = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in rgb]
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]


def ratio(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


PAIRS = [  # (thème, texte, fond, seuil, usage)
    ("light", "text", "background", 4.5, "texte courant"),
    ("light", "text", "surface", 4.5, "texte sur carte"),
    ("light", "muted", "surface", 4.5, "libellé secondaire"),
    ("light", "muted", "background", 4.5, "libellé secondaire sur fond"),
    ("light", "accentText", "surface", 4.5, "lien, bouton sans fond"),
    ("light", "redText", "surface", 4.5, "texte d'alerte, bouton destructif"),
    ("light", "orangeText", "surface", 4.5, "échéance proche"),
    ("light", "greenText", "surface", 4.5, "statut conforme"),
    ("light", "blueText", "surface", 4.5, "statut en déplacement"),
    ("dark", "text", "background", 4.5, "texte courant sombre"),
    ("dark", "muted", "surface", 4.5, "libellé secondaire sombre"),
    ("dark", "accentText", "surface", 4.5, "lien sombre"),
    ("dark", "redText", "surface", 4.5, "alerte sombre"),
    ("dark", "orangeText", "surface", 4.5, "échéance sombre"),
]

failures = 0
for theme, fg, bg, threshold, usage in PAIRS:
    c = tokens["color"][theme]
    r = ratio(c[fg], c[bg])
    ok = r >= threshold
    failures += not ok
    print(f"{'OK ' if ok else 'ÉCHEC'} {theme:5} {fg:>12} sur {bg:<11} {r:5.2f}:1 (seuil {threshold}) — {usage}")

# Verre teinté (design v2) : composé au PIRE cas, verre à 90 % posé sur du noir (carte sombre, photo).
def over(alpha, top, under):
    t = [int(top.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)]
    u = [int(under.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)]
    return "#%02X%02X%02X" % tuple(round(alpha * a + (1 - alpha) * b) for a, b in zip(t, u))

GLASS = [  # (texte, verre, dessous, usage)
    ("#102434", "#FFFFFF", "#000000", "texte principal sur verre clair"),
    ("#525D66", "#FFFFFF", "#000000", "texte secondaire sur verre clair"),
    ("#00695B", "#FFFFFF", "#000000", "onglet actif / accent sur verre clair"),
    # aucune action destructive (rouge) n'est posée sur du verre : elles restent sur surfaces opaques
    ("#F2F4F7", "#1C2228", "#FFFFFF", "texte principal sur verre sombre (sur fond blanc)"),
    ("#AEB8C0", "#1C2228", "#FFFFFF", "texte secondaire sur verre sombre (sur fond blanc)"),
]
for fg, glass, under, usage in GLASS:
    bg = over(0.90, glass, under)
    r = ratio(fg, bg)
    ok = r >= 4.5
    failures += not ok
    print(f"{'OK ' if ok else 'ÉCHEC'} verre {fg} sur {bg} {r:5.2f}:1 (seuil 4.5) — {usage}")

for theme in ("light", "dark"):
    r = ratio("#FFFFFF", tokens["color"][theme]["accent"])
    ok = r >= 4.5
    failures += not ok
    print(f"{'OK ' if ok else 'ÉCHEC'} {theme:5} blanc sur accent {tokens['color'][theme]['accent']} (bouton plein) : {r:.2f}:1 (seuil 4.5)")
sys.exit(1 if failures else 0)
