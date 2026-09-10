"""THE CROWD (DESIGN.md §6) — three rows of stroke silhouettes behind the far ropes, VIOLET.

WORLD SPACE, not the ring's: the renderer places this model at the origin, so the rows sit at
engine z = −6 / −7.5 / −9 (Blender y = 6 / 7.5 / 9 — the exporter maps `(x, y, z) -> (x, z, -y)`)
on tiered risers so the back rows look over the front. Forty silhouettes a row, 0.5 m apart, twenty
metres wide: at 6 m the 62° field spans ±3.6 m, so the crowd runs off both edges of the plate and
never shows an end.

STROKE LOD BY ROW, authored, not computed: the front row is eight strokes (a five-segment head arc
and three for the shoulders), the middle row five (a three-segment arc and two shoulder slopes),
the back row three (a hump). 40 × (8 + 5 + 3) = 640 segments, the budget table's number. The rows
are separate parts (`row0`, `row1`, `row2`) so the renderer can bake each at its own alpha — the
back row dimmer, so depth reads by brightness on a display that has no other depth cue.

THE SWAY IS THE SHADER'S (DESIGN.md §12.5): every vertex is displaced by `amp · sin(freq · t +
x · phase) · max(0, y − floorY)` in the vertex program, so the phase across x makes a wave and the
floor clamp keeps feet planted. Nothing here animates; a seeded height jitter per figure is the
only thing that stops forty identical humps reading as a comb.
"""
ASSET_NAME = "crowd"
VIOLET = (0.60, 0.20, 1.00)

ROWS = ((6.0, 0.55), (7.5, 1.05), (9.0, 1.55))   # (distance, riser height) per row: each tier looks over the rope and the row in front
N = 40
SPACING = 0.5

def _obj(name, pts3, edges, part, color=VIOLET):
    me = bpy.data.meshes.new(name); me.from_pydata(pts3, edges, []); me.update()
    ob = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(ob)
    ob["part"] = part; ob["color"] = color; ob["pivot"] = (0.0, 0.0, 0.0)
    return ob

def polyline(pts3):
    """Returns (points, edges) for one open polyline."""
    return pts3, [(i, i + 1) for i in range(len(pts3) - 1)]

def arc(cx, cy, cz, r, n, a0=0.0, a1=math.pi):
    """A head arc in the x–z plane (z up) from angle a0 to a1, n segments."""
    return [(cx + r * math.cos(a0 + (a1 - a0) * k / n), cy, cz + r * math.sin(a0 + (a1 - a0) * k / n)) for k in range(n + 1)]

def figure(row, x, y, base, h):
    """One silhouette at (x, y) standing on `base`, scaled by the jitter `h`. Row 0 = the front."""
    if row == 0:
        # a five-segment head arc over a neck, and three shoulder strokes: eight in all
        head = arc(x, y, base + 0.62 * h, 0.13 * h, 5)
        sh = [(x - 0.28, y, base + 0.30 * h), (x - 0.14, y, base + 0.48 * h), (x + 0.14, y, base + 0.48 * h), (x + 0.28, y, base + 0.30 * h)]
        return [polyline(head), polyline(sh)]
    if row == 1:
        head = arc(x, y, base + 0.60 * h, 0.12 * h, 3)
        sh = [(x - 0.26, y, base + 0.30 * h), (x, y, base + 0.47 * h), (x + 0.26, y, base + 0.30 * h)]
        return [polyline(head), polyline(sh)]
    hump = [(x - 0.24, y, base + 0.30 * h), (x - 0.08, y, base + 0.66 * h), (x + 0.08, y, base + 0.66 * h), (x + 0.24, y, base + 0.30 * h)]
    return [polyline(hump)]

seed = 1234567
def jitter():
    global seed
    seed = (seed * 1103515245 + 12345) & 0x7fffffff
    return 0.92 + 0.16 * ((seed >> 8) & 0xff) / 255.0

for row, (dist, riser) in enumerate(ROWS):
    pts3, edges = [], []
    for i in range(N):
        x = (i - (N - 1) / 2.0) * SPACING
        for (p, e) in figure(row, x, dist, riser, jitter()):
            k = len(pts3)
            pts3 += p
            edges += [(a + k, b + k) for (a, b) in e]
    _obj(f"row{row}", pts3, edges, f"row{row}")
