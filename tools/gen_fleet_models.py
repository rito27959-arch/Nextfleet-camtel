#!/usr/bin/env python3
"""
Génère les modèles 3D de la flotte NexFleet (GLB, glTF 2.0) : pick-up, SUV, 4x4, berline, minibus.

Création originale du projet (aucune ressource tierce) : profils latéraux extrudés, vitrages, roues, phares,
bandeau aux couleurs NexFleet. Les fichiers produits sont donc librement redistribuables dans les applications.
Repère glTF : Y vers le haut, avant du véhicule vers +X, mètres. Matériaux PBR métal/rugosité.

Usage : python3 tools/gen_fleet_models.py [dossier de sortie]   (défaut : shared/assets3d/models)
"""
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "shared/assets3d/models")

MATERIALS = {
    "paint": {"baseColorFactor": [0.94, 0.95, 0.96, 1], "metallicFactor": 0.35, "roughnessFactor": 0.32},
    "stripe": {"baseColorFactor": [0.0, 0.52, 0.46, 1], "metallicFactor": 0.2, "roughnessFactor": 0.4},
    "glass": {"baseColorFactor": [0.07, 0.11, 0.15, 1], "metallicFactor": 0.1, "roughnessFactor": 0.08},
    "trim": {"baseColorFactor": [0.12, 0.13, 0.14, 1], "metallicFactor": 0.0, "roughnessFactor": 0.7},
    "tire": {"baseColorFactor": [0.05, 0.05, 0.05, 1], "metallicFactor": 0.0, "roughnessFactor": 0.92},
    "rim": {"baseColorFactor": [0.72, 0.74, 0.76, 1], "metallicFactor": 0.9, "roughnessFactor": 0.28},
    "headlight": {"baseColorFactor": [1, 0.98, 0.9, 1], "metallicFactor": 0.0, "roughnessFactor": 0.2,
                  "emissiveFactor": [0.9, 0.88, 0.75]},
    "taillight": {"baseColorFactor": [0.8, 0.05, 0.05, 1], "metallicFactor": 0.0, "roughnessFactor": 0.25,
                  "emissiveFactor": [0.6, 0.02, 0.02]},
}


class Mesh:
    """Triangles à normales plates, regroupés par matériau."""

    def __init__(self):
        self.parts = {k: ([], []) for k in MATERIALS}   # matériau -> (positions, normales)

    def tri(self, mat, a, b, c):
        a, b, c = map(np.asarray, (a, b, c))
        n = np.cross(b - a, c - a)
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return
        n = n / ln
        pos, nor = self.parts[mat]
        pos.extend([a, b, c])
        nor.extend([n, n, n])

    def quad(self, mat, a, b, c, d):
        self.tri(mat, a, b, c)
        self.tri(mat, a, c, d)

    def box(self, mat, x0, x1, y0, y1, z0, z1):
        p = [(x, y, z) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
        P = lambda i: p[i]
        # faces orientées vers l'extérieur
        self.quad(mat, P(0), P(1), P(3), P(2))   # x0
        self.quad(mat, P(4), P(6), P(7), P(5))   # x1
        self.quad(mat, P(0), P(4), P(5), P(1))   # y0
        self.quad(mat, P(2), P(3), P(7), P(6))   # y1
        self.quad(mat, P(0), P(2), P(6), P(4))   # z0
        self.quad(mat, P(1), P(5), P(7), P(3))   # z1

    def extrude(self, mat, profile, half_width, cap_mat=None, edge_mat=None):
        """Profil (x, y) dans le sens trigonométrique, extrudé de -half_width à +half_width selon Z.
        edge_mat(a, b) choisit le matériau de chaque face de tranche (ex. pare-brise vitré)."""
        pts = profile
        if signed_area(pts) < 0:
            pts = pts[::-1]
        z0, z1 = -half_width, half_width
        for i in range(len(pts)):
            (xa, ya), (xb, yb) = pts[i], pts[(i + 1) % len(pts)]
            em = edge_mat((xa, ya), (xb, yb)) if edge_mat else mat
            self.quad(em, (xa, ya, z1), (xa, ya, z0), (xb, yb, z0), (xb, yb, z1))
        for (i, j, k) in ear_clip(pts):
            a, b, c = pts[i], pts[j], pts[k]
            self.tri(cap_mat or mat, (*a, z1), (*b, z1), (*c, z1))
            self.tri(cap_mat or mat, (*a, z0), (*c, z0), (*b, z0))

    def wheel(self, cx, cy, cz, r, w, side):
        seg = 20
        zo, zi = cz + side * w / 2, cz - side * w / 2
        ring = [(cx + r * math.cos(2 * math.pi * i / seg), cy + r * math.sin(2 * math.pi * i / seg)) for i in range(seg)]
        for i in range(seg):
            (xa, ya), (xb, yb) = ring[i], ring[(i + 1) % seg]
            if side > 0:
                self.quad("tire", (xa, ya, zi), (xb, yb, zi), (xb, yb, zo), (xa, ya, zo))
            else:
                self.quad("tire", (xa, ya, zo), (xb, yb, zo), (xb, yb, zi), (xa, ya, zi))
        rr = r * 0.62
        hub = [(cx + rr * math.cos(2 * math.pi * i / seg), cy + rr * math.sin(2 * math.pi * i / seg)) for i in range(seg)]
        zr = zo + side * 0.004
        for i in range(seg):
            (xa, ya), (xb, yb) = ring[i], ring[(i + 1) % seg]
            (ha, hb), (hc, hd) = hub[i], hub[(i + 1) % seg]
            if side > 0:
                self.quad("tire", (xa, ya, zo), (xb, yb, zo), (hc, hd, zr), (ha, hb, zr))
                self.tri("rim", (cx, cy, zr + side * 0.02), (ha, hb, zr), (hc, hd, zr))
            else:
                self.quad("tire", (xa, ya, zo), (ha, hb, zr), (hc, hd, zr), (xb, yb, zo))
                self.tri("rim", (cx, cy, zr + side * 0.02), (hc, hd, zr), (ha, hb, zr))


def offset_inward(p, d):
    """Décalage vers l'intérieur d'un polygone convexe (sens trigonométrique) de la distance d."""
    if signed_area(p) < 0:
        p = p[::-1]
    lines = []
    for i in range(len(p)):
        (xa, ya), (xb, yb) = p[i], p[(i + 1) % len(p)]
        dx, dy = xb - xa, yb - ya
        ln = math.hypot(dx, dy)
        nx, ny = -dy / ln, dx / ln          # normale intérieure (sens trigonométrique)
        lines.append(((xa + nx * d, ya + ny * d), (dx, dy)))
    out = []
    for i in range(len(lines)):
        (p1, d1), (p2, d2) = lines[i - 1], lines[i]
        den = d1[0] * d2[1] - d1[1] * d2[0]
        if abs(den) < 1e-9:
            out.append(p2)
            continue
        t = ((p2[0] - p1[0]) * d2[1] - (p2[1] - p1[1]) * d2[0]) / den
        out.append((p1[0] + d1[0] * t, p1[1] + d1[1] * t))
    return out


def signed_area(p):
    return 0.5 * sum(p[i][0] * p[(i + 1) % len(p)][1] - p[(i + 1) % len(p)][0] * p[i][1] for i in range(len(p)))


def ear_clip(p):
    idx = list(range(len(p)))
    tris = []

    def inside(pt, a, b, c):
        def s(p1, p2, p3):
            return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])
        d1, d2, d3 = s(pt, a, b), s(pt, b, c), s(pt, c, a)
        return not ((d1 < 0 or d2 < 0 or d3 < 0) and (d1 > 0 or d2 > 0 or d3 > 0))

    guard = 0
    while len(idx) > 3 and guard < 10_000:
        guard += 1
        for k in range(len(idx)):
            i, j, l = idx[k - 1], idx[k], idx[(k + 1) % len(idx)]
            a, b, c = p[i], p[j], p[l]
            if (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]) <= 0:
                continue
            if any(inside(p[m], a, b, c) for m in idx if m not in (i, j, l)):
                continue
            tris.append((i, j, l))
            idx.pop(k)
            break
    if len(idx) == 3:
        tris.append(tuple(idx))
    return tris


# ---------------------------------------------------------------- types de véhicules (profils latéraux, mètres)

def vehicle(kind):
    m = Mesh()
    if kind == "berline":
        L, W, r = 4.55, 1.78, 0.32
        body = [(0, 0.30), (4.55, 0.30), (4.55, 0.66), (4.32, 0.86), (3.35, 0.98), (1.05, 0.99), (0.12, 0.92), (0, 0.72)]
        cabin = [(1.05, 0.98), (3.40, 0.98), (2.86, 1.43), (1.52, 1.44)]
        axles = (1.0, 3.62)
    elif kind == "suv":
        L, W, r = 4.75, 1.86, 0.36
        body = [(0, 0.40), (4.75, 0.40), (4.75, 0.80), (4.50, 1.02), (3.95, 1.12), (0.10, 1.14), (0, 1.00)]
        cabin = [(0.12, 1.12), (3.98, 1.12), (3.30, 1.74), (0.30, 1.76)]
        axles = (1.02, 3.78)
    elif kind == "4x4":
        L, W, r = 4.95, 1.94, 0.39
        body = [(0, 0.44), (4.95, 0.44), (4.95, 0.95), (4.78, 1.14), (4.10, 1.20), (0.06, 1.22), (0, 1.10)]
        cabin = [(0.08, 1.20), (4.14, 1.20), (3.62, 1.86), (0.14, 1.88)]
        axles = (1.05, 3.92)
    elif kind == "minibus":
        L, W, r = 5.30, 1.90, 0.34
        body = [(0, 0.38), (5.30, 0.38), (5.30, 0.90), (5.12, 1.08), (0.04, 1.10), (0, 1.00)]
        cabin = [(0.05, 1.09), (5.14, 1.09), (4.80, 1.98), (4.30, 2.16), (0.08, 2.18)]
        axles = (1.02, 4.12)
    elif kind == "pickup":
        L, W, r = 5.30, 1.86, 0.38
        body = [(0, 0.44), (5.30, 0.44), (5.30, 0.92), (5.05, 1.10), (4.30, 1.15), (2.15, 1.15), (2.15, 0.78), (0, 0.78)]
        cabin = [(2.18, 1.13), (4.32, 1.13), (3.62, 1.80), (2.24, 1.82)]
        axles = (1.12, 4.20)
    else:
        raise ValueError(kind)

    hw = W / 2
    m.extrude("paint", body, hw)
    cw = hw * 0.88

    def windshield(a, b):   # tranches inclinées de l'habitacle = pare-brise et lunette arrière
        dx, dy = abs(b[0] - a[0]), abs(b[1] - a[1])
        return "glass" if dx > 0.12 and dy > 0.12 else "paint"

    m.extrude("paint", cabin, cw, edge_mat=windshield)
    # vitres latérales : profil de l'habitacle décalé vers l'intérieur, posé sur chaque flanc
    win = offset_inward(cabin, 0.075)
    win = [(x, max(y, min(yy for _, yy in cabin) + 0.06)) for x, y in win]
    for s_ in (1, -1):
        z = s_ * (cw + 0.004)
        for (i, j, k) in ear_clip(win if signed_area(win) > 0 else win[::-1]):
            w = win if signed_area(win) > 0 else win[::-1]
            a, b, c = w[i], w[j], w[k]
            if s_ > 0:
                m.tri("glass", (*a, z), (*b, z), (*c, z))
            else:
                m.tri("glass", (*a, z), (*c, z), (*b, z))
        # montant central peint
        xs = [x for x, _ in win]
        xm = (min(xs) + max(xs)) / 2 - (0.25 if kind == "minibus" else 0)
        ys = [y for _, y in win]
        m.box("paint", xm - 0.05, xm + 0.05, min(ys), max(ys), z - 0.006 if s_ > 0 else z - 0.002, z + 0.002 if s_ > 0 else z + 0.006)
    if kind == "pickup":   # benne : ridelles et hayon
        m.box("paint", 0.00, 2.15, 0.78, 1.10, hw - 0.06, hw)
        m.box("paint", 0.00, 2.15, 0.78, 1.10, -hw, -hw + 0.06)
        m.box("paint", 0.00, 0.07, 0.78, 1.10, -hw, hw)
        m.box("trim", 0.07, 2.15, 0.78, 0.80, -hw + 0.06, hw - 0.06)

    # bandeau NexFleet sur les flancs
    yb = 0.62 if kind in ("berline",) else 0.70
    m.box("stripe", 0.05, L - 0.05, yb, yb + 0.07, hw - 0.002, hw + 0.006)
    m.box("stripe", 0.05, L - 0.05, yb, yb + 0.07, -hw - 0.006, -hw + 0.002)
    # pare-chocs
    m.box("trim", L - 0.02, L + 0.06, 0.30, 0.48 if kind != "berline" else 0.42, -hw * 0.95, hw * 0.95)
    m.box("trim", -0.06, 0.02, 0.30, 0.48 if kind != "berline" else 0.42, -hw * 0.95, hw * 0.95)
    # phares et feux
    hy = body[2][1] - 0.12
    for s in (1, -1):
        m.box("headlight", L - 0.01, L + 0.015, hy - 0.08, hy + 0.04, s * hw * 0.62 - 0.17, s * hw * 0.62 + 0.17)
        m.box("taillight", -0.015, 0.01, hy - 0.06, hy + 0.08, s * hw * 0.72 - 0.12, s * hw * 0.72 + 0.12)
    # roues
    for ax in axles:
        for s in (1, -1):
            m.wheel(ax, r, s * (hw - 0.12), r, 0.24, s)
    return m


# ---------------------------------------------------------------- écriture GLB

def write_glb(mesh, path, name):
    bin_parts, buffer_views, accessors, primitives, materials = [], [], [], [], []
    offset = 0

    def add_view(data, target):
        nonlocal offset
        pad = (-len(data)) % 4
        bin_parts.append(data + b"\x00" * pad)
        buffer_views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(data), "target": target})
        offset += len(data) + pad
        return len(buffer_views) - 1

    for mat_name, (pos, nor) in mesh.parts.items():
        if not pos:
            continue
        mi = len(materials)
        spec = MATERIALS[mat_name]
        mat = {"name": mat_name, "pbrMetallicRoughness": {k: spec[k] for k in ("baseColorFactor", "metallicFactor", "roughnessFactor")}}
        if "emissiveFactor" in spec:
            mat["emissiveFactor"] = spec["emissiveFactor"]
        materials.append(mat)
        P = np.asarray(pos, dtype=np.float32)
        N = np.asarray(nor, dtype=np.float32)
        I = np.arange(len(P), dtype=np.uint32)
        vp = add_view(P.tobytes(), 34962)
        vn = add_view(N.tobytes(), 34962)
        vi = add_view(I.tobytes(), 34963)
        accessors.append({"bufferView": vp, "componentType": 5126, "count": len(P), "type": "VEC3",
                          "min": P.min(axis=0).tolist(), "max": P.max(axis=0).tolist()})
        accessors.append({"bufferView": vn, "componentType": 5126, "count": len(N), "type": "VEC3"})
        accessors.append({"bufferView": vi, "componentType": 5125, "count": len(I), "type": "SCALAR"})
        a = len(accessors)
        primitives.append({"attributes": {"POSITION": a - 3, "NORMAL": a - 2}, "indices": a - 1, "material": mi})

    binary = b"".join(bin_parts)
    gltf = {
        "asset": {"version": "2.0", "generator": "NexFleet gen_fleet_models.py",
                  "copyright": "NexFleet CAMTEL — création originale du projet, libre de redistribution"},
        "scene": 0, "scenes": [{"nodes": [0]}],
        "nodes": [{"name": name, "mesh": 0, "translation": [0, 0, 0]}],
        "meshes": [{"name": name, "primitives": primitives}],
        "materials": materials, "accessors": accessors, "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(binary)}],
    }
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * ((-len(js)) % 4)
    total = 12 + 8 + len(js) + 8 + len(binary)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
        f.write(struct.pack("<II", len(binary), 0x004E4942) + binary)
    return sum(len(p[0]) for p in mesh.parts.values()) // 3


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for kind in ("pickup", "suv", "4x4", "berline", "minibus"):
        path = OUT / f"nexfleet-{kind}.glb"
        n = write_glb(vehicle(kind), path, f"NexFleet {kind}")
        print(f"{path.name} : {n} triangles, {path.stat().st_size // 1024} Ko")
