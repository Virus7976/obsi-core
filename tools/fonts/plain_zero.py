"""Gives Atkinson Hyperlegible a plain zero, with no slash.

    python plain_zero.py  path/to/atkinson_regular.ttf  [more.ttf ...]

The font slashes its zero so 0 is never read as O. In an app that shows "0 of 379 lessons" the slash
reads as a different letter, so the zero becomes a plain oval: the outer line is kept, and the hole is
the same line made smaller, so the stroke matches the other digits. The font is under the SIL Open Font
Licence, which asks a changed copy to carry its own name, so it is renamed "Obsi Sans".
Run it once on a fresh copy; running it again changes nothing.
"""
import sys

from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._g_l_y_f import GlyphCoordinates


def plain_zero(path):
    t = TTFont(path)
    glyf = t["glyf"]
    g = glyf["zero"]
    if g.numberOfContours != 3:
        print(path, "zero is already plain")
        return
    coords = list(g.getCoordinates(glyf)[0])
    flags = list(g.flags)
    ends = list(g.endPtsOfContours)
    # Contour 0 is the outside. Contours 1 and 2 are the two holes the slash cuts.
    outer = coords[: ends[0] + 1]
    oflags = flags[: ends[0] + 1]
    holes = coords[ends[0] + 1:]
    ox = [p[0] for p in outer]; oy = [p[1] for p in outer]
    hx = [p[0] for p in holes]; hy = [p[1] for p in holes]
    ocx, ocy = (min(ox) + max(ox)) / 2, (min(oy) + max(oy)) / 2
    hcx, hcy = (min(hx) + max(hx)) / 2, (min(hy) + max(hy)) / 2
    sx = (max(hx) - min(hx)) / (max(ox) - min(ox))
    sy = (max(hy) - min(hy)) / (max(oy) - min(oy))
    inner = [(round(hcx + (x - ocx) * sx), round(hcy + (y - ocy) * sy)) for x, y in outer]
    # A hole runs the other way round.
    inner = inner[::-1]
    iflags = oflags[::-1]
    g.coordinates = GlyphCoordinates(outer + inner)
    g.flags = bytearray(oflags + iflags)
    g.endPtsOfContours = [len(outer) - 1, len(outer) + len(inner) - 1]
    g.numberOfContours = 2
    g.program = type(g.program)() if hasattr(g, "program") else None
    if g.program is not None:
        g.program.fromBytecode(b"")
    g.recalcBounds(glyf)

    name = t["name"]
    for rec in name.names:
        if rec.nameID in (1, 3, 4, 6, 16):
            old = rec.toUnicode()
            new = old.replace("Atkinson Hyperlegible", "Obsi Sans").replace("AtkinsonHyperlegible", "ObsiSans")
            rec.string = new
    t.save(path)
    print(path, "zero made plain")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        plain_zero(p)
