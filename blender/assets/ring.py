"""THE RING (DESIGN.md §6) — a plain stroke model, BLUE, placed by the renderer at the boxer's range.

MODEL SPACE IS THE RING'S OWN: the canvas centre is the origin, so `GLRenderer.buildStatic` puts it
at `(0, 0, Boxer.Z)` and you stand inside it 2.6 m from the far rope, near your own. Blender is
Z-up and the exporter maps `(x, y, z) -> (x, z, -y)`: Blender +Y is engine −Z (away from the
player), so the FAR ropes are at Blender y = +3 and your own rope at y = −3, 0.4 m behind the eye.

WHAT IS DRAWN, AND WHY IT IS SHAPED SO. A straight rope reads as a fence on this halo, so every
rope is a 6-segment catenary sagging 6 cm at the middle (the sag is what says "rope" from 2.6 m).
Posts are four strokes — two rails 6 cm apart, a cap and a foot — because a single vertical stroke
at 5 m is a hair and two are a post. Turnbuckle X's sit just inside each post on every rope: they
are small, and at the far corners they are the brightest thing on the ring by overlap, which is
how a corner reads as a corner. The apron is the canvas's overhang, drawn 5 cm under the deck so
the deck edge and the apron edge are two lines, not one. The 6 × 6 canvas grid is its own part
(`grid`) so the renderer can bake it at α 0.18 while the ropes sit at 0.6; alpha is not in the
file format, so the part NAME carries it.

Budget: posts 16 + ropes 72 + X's 48 + apron 8 + grid 14 = 158 segments, well under the 180 the
budget table allows (DESIGN.md §12.3). Everything is BLUE `(0.30, 0.50, 1.00)`; the multiplier's
rope glow and the knockdown's rope shake are the renderer's uniforms, not geometry.
"""
ASSET_NAME = "ring"
BLUE = (0.30, 0.50, 1.00)

HW = 3.0          # half-width of the canvas, metres
POST_H = 1.30     # post height
POST_W = 0.03     # half-width of a post's two rails
ROPES = (0.45, 0.85, 1.25)
SAG = 0.06        # catenary sag at the middle of a rope
ROPE_SEGS = 6
APRON_OUT = 0.40  # the apron's overhang beyond the posts
APRON_DROP = 0.05
X_SIZE = 0.05     # a turnbuckle X's half-size
GRID_N = 6

def _obj(name, pts3, edges, part, color=BLUE, pivot=(0.0, 0.0, 0.0)):
    me = bpy.data.meshes.new(name); me.from_pydata(pts3, edges, []); me.update()
    ob = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(ob)
    ob["part"] = part; ob["color"] = color; ob["pivot"] = pivot
    return ob

def strokes(name, part, segs):
    """A bag of independent segments `[(x0,y0,z0, x1,y1,z1), …]` in Blender coordinates (z up)."""
    pts3, edges = [], []
    for s in segs:
        k = len(pts3)
        pts3 += [(s[0], s[1], s[2]), (s[3], s[4], s[5])]
        edges.append((k, k + 1))
    return _obj(name, pts3, edges, part)

CORNERS = [(-HW, -HW), (HW, -HW), (HW, HW), (-HW, HW)]

# ---------------------------------------------------------------- the posts: two rails, a cap, a foot
posts = []
for (cx, cy) in CORNERS:
    # the rails face the ring centre: offset along x for the near/far pairs reads the same from inside
    posts.append((cx - POST_W, cy, 0.0, cx - POST_W, cy, POST_H))
    posts.append((cx + POST_W, cy, 0.0, cx + POST_W, cy, POST_H))
    posts.append((cx - POST_W, cy, POST_H, cx + POST_W, cy, POST_H))
    posts.append((cx - 0.10, cy, 0.0, cx + 0.10, cy, 0.0))
strokes("posts", "posts", posts)

# ---------------------------------------------------------------- the ropes: three a side, each a catenary
ropes = []
xs = []
for i in range(4):
    a = CORNERS[i]; b = CORNERS[(i + 1) % 4]
    for h in ROPES:
        prev = None
        for k in range(ROPE_SEGS + 1):
            t = k / ROPE_SEGS
            x = a[0] + (b[0] - a[0]) * t
            y = a[1] + (b[1] - a[1]) * t
            u = 2.0 * t - 1.0
            z = h - SAG * (1.0 - u * u)
            if prev is not None:
                ropes.append((prev[0], prev[1], prev[2], x, y, z))
            prev = (x, y, z)
        # the turnbuckle X's, just inside each end of the rope, in the rope's own vertical plane
        dx, dy = b[0] - a[0], b[1] - a[1]
        L = math.hypot(dx, dy)
        ux, uy = dx / L, dy / L
        for (px, py) in ((a[0] + ux * 0.16, a[1] + uy * 0.16), (b[0] - ux * 0.16, b[1] - uy * 0.16)):
            xs.append((px - ux * X_SIZE, py - uy * X_SIZE, h - X_SIZE, px + ux * X_SIZE, py + uy * X_SIZE, h + X_SIZE))
            xs.append((px - ux * X_SIZE, py - uy * X_SIZE, h + X_SIZE, px + ux * X_SIZE, py + uy * X_SIZE, h - X_SIZE))
strokes("ropes", "ropes", ropes)
strokes("turnbuckles", "ropes", xs)

# ---------------------------------------------------------------- the apron: the deck edge and the overhang under it
apron = []
for i in range(4):
    a = CORNERS[i]; b = CORNERS[(i + 1) % 4]
    apron.append((a[0], a[1], 0.0, b[0], b[1], 0.0))
    ao = (math.copysign(HW + APRON_OUT, a[0]), math.copysign(HW + APRON_OUT, a[1]))
    bo = (math.copysign(HW + APRON_OUT, b[0]), math.copysign(HW + APRON_OUT, b[1]))
    apron.append((ao[0], ao[1], -APRON_DROP, bo[0], bo[1], -APRON_DROP))
strokes("apron", "apron", apron)

# ---------------------------------------------------------------- the canvas grid, its own part for its own alpha
grid = []
for i in range(GRID_N + 1):
    t = -HW + i * (2.0 * HW / GRID_N)
    grid.append((t, -HW, 0.0, t, HW, 0.0))
    grid.append((-HW, t, 0.0, HW, t, 0.0))
strokes("grid", "grid", grid)
