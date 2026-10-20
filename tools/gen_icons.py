#!/usr/bin/env python3
"""Icônes d'application NexFleet (emblème à quatre pétales sur carré arrondi bleu nuit) : PNG, ICO, mipmaps Android."""
import math, sys
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]

def petal_poly(cx, cy, s, angle):
    # pétale en goutte : du centre vers l'extérieur
    pts = []
    for i in range(60):
        t = i / 59 * math.pi
        r = math.sin(t) ** 1.4
        x = 0.06 + 0.42 * (1 - math.cos(t)) / 2
        y = 0.17 * r * (1 if i < 30 else 1)
        pts.append((x, y))
    pts += [(x, -y) for x, y in reversed(pts)]
    out = []
    for x, y in pts:
        xr = x * math.cos(angle) - y * math.sin(angle)
        yr = x * math.sin(angle) + y * math.cos(angle)
        out.append((cx + xr * s, cy + yr * s))
    return out

def icon(size, background=True, padding=0.0):
    k = 4
    S = size * k
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if background:
        r = int(S * 0.225)
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=(16, 38, 49, 255))
    c = S / 2
    scale = S * (0.95 - padding)
    for a in (45, 135, 225, 315):
        d.polygon(petal_poly(c, c, scale, math.radians(a)), fill=(0, 179, 156, 255))
    return img.resize((size, size), Image.LANCZOS)

if __name__ == "__main__":
    pk = ROOT / "apps/desktop-javafx/packaging"
    pk.mkdir(parents=True, exist_ok=True)
    icon(512).save(pk / "nexfleet.png")
    icon(256).save(pk / "nexfleet.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    res = ROOT / "apps/android/app/src/main/res"
    for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        p = res / f"mipmap-{name}"
        p.mkdir(parents=True, exist_ok=True)
        icon(px).save(p / "ic_launcher.png")
        fpx = px * 108 // 48                       # icône adaptative : calque de 108 dp, zone sûre de 66 dp
        fg = Image.new("RGBA", (fpx, fpx), (0, 0, 0, 0))
        im = icon(int(fpx * 0.56), background=False)
        fg.paste(im, ((fpx - im.width) // 2, (fpx - im.height) // 2), im)
        fg.save(p / "ic_launcher_foreground.png")
    print("icônes générées")
