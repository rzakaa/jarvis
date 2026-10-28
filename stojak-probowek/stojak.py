"""
Modułowy stojak na probówki – wersja łatwa do druku 3D (bez gwintów, bez podpór).

Części:
  podstawa      – płyta z gniazdami na dno probówek, rantem (barierką) i czopem
  kolumna_XX    – segmenty kolumny (łączone na czop); wysokość = suma segmentów
  plyta_gorna   – płyta z otworami i tulejami-barierkami prowadzącymi probówki
  nakretka      – pokrętło wciskane na czop, dociska górną płytę

Czopy mają ścięcie (płaską ściankę), więc otwory w górnej płycie zawsze trafiają
nad gniazda w podstawie.

Uruchomienie: python stojak.py  (wymaga: pip install manifold3d trimesh numpy)
Wymiary w mm – zmień parametry poniżej i uruchom ponownie.
"""
import math
import os
import sys

import numpy as np
import trimesh
from manifold3d import CrossSection, Manifold

# --- Parametry -----------------------------------------------------------------
PROBOWKA_D = 16.0          # średnica probówki
LUZ_PROBOWKI = 1.5         # otwór = probówka + luz
LICZBA_PROBOWEK = 8
PROMIEN_ROZSTAWU = 33.0    # od środka do środka otworu

PLYTA_D = 100.0
PODSTAWA_GRUBOSC = 4.0
GNIAZDO_GLEBOKOSC = 2.0    # zagłębienie na dno probówki w podstawie
RANT_WYSOKOSC = 8.0        # barierka na brzegu podstawy
RANT_GRUBOSC = 2.0

PLYTA_GRUBOSC = 3.0
TULEJA_WYSOKOSC = 10.0     # barierki wokół otworów w górnej płycie
TULEJA_SCIANKA = 1.6
BRZEG_GORNY_WYSOKOSC = 4.0 # niski rant na brzegu górnej płyty

KOLUMNA_D = 20.0
SEGMENTY = [25.0, 50.0]    # dostępne wysokości segmentów kolumny

CZOP_D = 12.0
CZOP_DLUGOSC = 12.0
CZOP_SCIECIE = 1.2         # płaska ścianka – blokuje obrót
LUZ_PASOWANIA = 0.2        # na stronę; zwiększ do 0.25–0.3, jeśli za ciasno

NAKRETKA_D = 30.0
NAKRETKA_WYSOKOSC = 15.0

SEG = 96                   # gładkość okręgów

OTWOR_D = PROBOWKA_D + LUZ_PROBOWKI
GNIAZDO_D = PROBOWKA_D + LUZ_PROBOWKI


def d_profile(diameter: float, flat: float) -> CrossSection:
    """Okrąg ze ściętym bokiem (kształt litery D) – czop i gniazdo nie obracają się."""
    r = diameter / 2
    circle = CrossSection.circle(r, SEG)
    cut = CrossSection.square((diameter * 2, diameter * 2)).translate((r - flat, -diameter))
    return circle - cut


def cylinder(d: float, h: float, z: float = 0.0, x: float = 0.0, y: float = 0.0) -> Manifold:
    return Manifold.cylinder(h, d / 2, d / 2, SEG).translate((x, y, z))


def ring_positions():
    for i in range(LICZBA_PROBOWEK):
        a = 2 * math.pi * i / LICZBA_PROBOWEK + math.pi / LICZBA_PROBOWEK
        yield PROMIEN_ROZSTAWU * math.cos(a), PROMIEN_ROZSTAWU * math.sin(a)


def peg(z: float) -> Manifold:
    """Czop D z fazką na końcu (łatwiej trafić w gniazdo)."""
    body = d_profile(CZOP_D, CZOP_SCIECIE).extrude(CZOP_DLUGOSC - 1.0)
    tip = d_profile(CZOP_D, CZOP_SCIECIE).extrude(1.0, scale_top=(0.85, 0.85)).translate((0, 0, CZOP_DLUGOSC - 1.0))
    return (body + tip).translate((0, 0, z))


def socket(depth: float) -> Manifold:
    """Gniazdo D otwarte od dołu. Sufit Ø12 mm drukarka mostkuje bez podpór."""
    d = CZOP_D + 2 * LUZ_PASOWANIA
    return d_profile(d, CZOP_SCIECIE).extrude(depth + 0.5).translate((0, 0, -0.5))


def podstawa() -> Manifold:
    base = cylinder(PLYTA_D, PODSTAWA_GRUBOSC)
    rim = cylinder(PLYTA_D, RANT_WYSOKOSC) - cylinder(PLYTA_D - 2 * RANT_GRUBOSC, RANT_WYSOKOSC + 1, -0.5)
    part = base + rim
    for x, y in ring_positions():
        cup = cylinder(GNIAZDO_D, GNIAZDO_GLEBOKOSC + 1, PODSTAWA_GRUBOSC - GNIAZDO_GLEBOKOSC, x, y)
        part = part - cup
    return part + peg(PODSTAWA_GRUBOSC)


def kolumna(h: float) -> Manifold:
    body = cylinder(KOLUMNA_D, h)
    return body - socket(CZOP_DLUGOSC + 0.5) + peg(h)


def plyta_gorna() -> Manifold:
    plate = cylinder(PLYTA_D, PLYTA_GRUBOSC)
    edge = cylinder(PLYTA_D, BRZEG_GORNY_WYSOKOSC) - cylinder(PLYTA_D - 2 * RANT_GRUBOSC, BRZEG_GORNY_WYSOKOSC + 1, -0.5)
    part = plate + edge
    for x, y in ring_positions():
        part = part + cylinder(OTWOR_D + 2 * TULEJA_SCIANKA, TULEJA_WYSOKOSC, 0, x, y)
    for x, y in ring_positions():
        part = part - cylinder(OTWOR_D, TULEJA_WYSOKOSC + 2, -1, x, y)
    center = d_profile(CZOP_D + 2 * LUZ_PASOWANIA, CZOP_SCIECIE).extrude(PLYTA_GRUBOSC + 2).translate((0, 0, -1))
    return part - center


def nakretka() -> Manifold:
    core = cylinder(NAKRETKA_D - 8, NAKRETKA_WYSOKOSC)
    lobes = Manifold()
    for i in range(6):
        a = 2 * math.pi * i / 6
        lobes = lobes + cylinder(9.0, NAKRETKA_WYSOKOSC, 0, (NAKRETKA_D / 2 - 4.5) * math.cos(a), (NAKRETKA_D / 2 - 4.5) * math.sin(a))
    # Fazka na górze – wygodniej w dłoni, bez nawisów.
    chamfer = 3.0
    envelope = cylinder(NAKRETKA_D + 2, NAKRETKA_WYSOKOSC - chamfer) + Manifold.cylinder(
        chamfer, NAKRETKA_D / 2 + 1, NAKRETKA_D / 2 + 1 - chamfer, SEG,
    ).translate((0, 0, NAKRETKA_WYSOKOSC - chamfer))
    knob = (core + lobes) ^ envelope
    depth = CZOP_DLUGOSC - PLYTA_GRUBOSC + 0.5
    return knob - socket(depth)


def export(m: Manifold, path: str) -> trimesh.Trimesh:
    mesh = m.simplify(0.001).to_mesh()
    # process=False: nie usuwamy zerowych trójkątów, bo to rozerwałoby szczelną siatkę.
    tm = trimesh.Trimesh(vertices=np.asarray(mesh.vert_properties)[:, :3], faces=np.asarray(mesh.tri_verts), process=False)
    tm.export(path)
    return tm


def main(out_dir: str):
    os.makedirs(out_dir, exist_ok=True)
    parts = {"podstawa": podstawa(), "plyta_gorna": plyta_gorna(), "nakretka": nakretka()}
    for h in SEGMENTY:
        parts[f"kolumna_{int(h)}mm"] = kolumna(h)
    for name, m in parts.items():
        tm = export(m, os.path.join(out_dir, f"{name}.stl"))
        ext = tm.extents
        print(f"{name:16s} {ext[0]:6.1f} x {ext[1]:6.1f} x {ext[2]:5.1f} mm  objętość {tm.volume / 1000:5.1f} cm3  szczelna={tm.is_watertight}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "stl")
