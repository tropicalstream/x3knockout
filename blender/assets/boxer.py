"""ROY "THE ROOSTER" RUDD — the one boxer, as a 2D stroke sprite with POSE STRIPS (BOXER.md §1, §8;
DESIGN.md §7.3, §12.6).

WHAT THIS FILE IS. A procedural Blender scene (no `.blend`): the figure as parented polyline
objects hung on JOINT EMPTIES, every strip of BOXER.md §8 authored as key poses in model metres
and baked to one keyframe per object per frame at 12 fps, then `export_strokes.py` samples every
frame through the evaluated depsgraph into `assets/models/boxer.json` + `boxer.x3s`. Nothing here
runs on the glass; the engine (`engine/Poses.kt`) plays the frames back on WORLD time and never
interpolates, so what is drawn on frame N is exactly the pose sampled here at frame N.

THE DRAWING, FOR THE SURFACE. Black is the room; the figure is glowing strokes, no fills. Thick
comic outlines are DOUBLED CONTOURS (two parallel polylines 1.5 cm apart, three for the gloves),
never a line width; interior detail is single-weight; colour blocks are HATCHING at 2.5 cm pitch
(a 2.0 cm variant is one constant away, HATCH_PITCH) on a `hatch_<part>` object at gain 0.35 /
0.55. The head is 37 % of the figure; the gloves are pumpkin fists a third of the head; the feet
ARE drawn because the hooks' tell is the feet.

THE RIG, AND WHY IT IS A RIG AND NOT 196 DRAWINGS. The cabinet's artists drew every frame; one
agent with a week cannot, so the frames are GENERATED from a puppet whose joints are the things
a caricaturist moves — the head snaps and squashes, the brows tilt, the ears flap, each crest
spike fans or droops on its own root, the torso leans and folds, the legs splay, the gloves fly
anywhere in 3D on stretchy two-bone arms — and the things a transform cannot draw (a mouth going
from a grin to a scream, eyes going to slits or an X, the sweat, the spirals, the canvas bowing
under a planted boot) are VARIANT objects keyed on `hide_render`, several per part, one visible
at a time. Every object is keyed every frame so no frame depends on Blender's interpolation; the
curves are set CONSTANT afterwards anyway (Blender 5.x: through the slotted action API, since
`Action.fcurves` is gone) so a preview in the viewport steps like the glass.

THE AXES. Blender is Z-up. The sprite lies in the X–Z plane at y = 0 and FACES BLENDER +Y (the
exporter maps (x, y, z) -> (x, z, -y), so +Y lands on engine -Z, the `fwd` the manifest records);
the engine yaws the billboard toward the camera. Anything that must come TOWARD the viewer — the
extend frame's glove, a falling head — moves to Blender +Y, which is engine -Z here and larger
WORLD Z after the billboard turn (Poses.kt's class note; `StripSetTest` pins it on `wing_r`).
+X IS HIS RIGHT: facing +Y with Z up, his right hand is at +X, and after the billboard turn that
lands on the viewer's LEFT — exactly where the right hand of a man facing you is. `peck_r` is the
x-mirror of `peck_l` (legal there and nowhere else: the crest is symmetric).

THE JOINTS (rest positions in model metres, chin at 1.20, crown at 1.90, crest to 2.12): root at
the feet; hips (0, 0.70) with a leg and a boot each side; torso pivoting at the waist (0, 0.70)
with the neck, the head (pivot at the chin), the ears, the brows (pivot at the inner end so a
rotation is the whole expression), the pupils, the five crest spikes (pivot at the hairline) and
the shoulder helpers. The ARMS ARE NOT PARENTED: each glove is a top-level object placed by the
pose in absolute model space (it is the thing the pose is ABOUT — the collider's band, the arc's
origin, the 1.8x extend), and the two arm bones are solved every frame by a stretchy two-bone IK
from the shoulder the torso carries to the glove, stretching up to 1.6x so a straight punch at the
camera reads as a straight punch. Rotations about Y are in the sprite plane (positive tips the top
toward his right); rotations about X leave the plane (negative brings the top TOWARD the camera).

THE PARTS are the tint table's 32 slots, in this order (PARTS below): the crest is five parts so
the engine can hide one per knockdown; the pupils are their own parts because the eye flash is
per hand; the mouth variants share one part because only one is ever visible; `brow_L/R` share
`brow`; `ear_L/R` share `ears`; the legs and boots are one part a side-pair.

THE HAZARDS. (1) A joint's non-uniform scale shears its rotated children — harmless here because
the only squashed joints (head, torso, root) carry children that are keyed by small angles, and
the export reads world matrices, never a decomposition. (2) The exporter tests `hide_render` on
BOTH the original and the evaluated object, and Blender writes animated values back to originals
on `frame_set`, so a variant keyed hidden is hidden however it is read. (3) The frame budget is
700 segments (MAX_SEGS); the rest pose is ≈ 450 and the busiest frame (spirals, sweat, teeth, a
bow) adds ≈ 60 — `export_strips` exits 3 past the cap, and the engine refuses such a file too.
"""
import math
import bpy
from mathutils import Vector, Matrix

ASSET_NAME = "boxer"
FPS = 12
FWD = "-z"
MAX_SEGS = 700
HATCH_PITCH = 0.025   # metres; 0.020 is the variant DESIGN.md §15.14 keeps one line away
CONTOUR_GAP = 0.015

PARTS = ["head", "crest_0", "crest_1", "crest_2", "crest_3", "crest_4", "brow", "eyes", "pupil_L", "pupil_R",
         "nose", "ears", "mouth", "jaw_hatch", "neck", "torso", "trunks", "uarm_L", "uarm_R", "farm_L", "farm_R",
         "glove_L", "glove_R", "legs", "boots", "hatch_head", "hatch_torso", "hatch_trunks",
         "hatch_glove_L", "hatch_glove_R", "sweat", "spirals"]
MARKERS = ["glove_L", "glove_R", "chin", "body", "eye_L", "eye_R", "crown"]

MAGENTA = (1.00, 0.15, 0.60)
RED = (1.00, 0.22, 0.18)
VIOLET = (0.60, 0.20, 1.00)
CYAN = (0.35, 0.95, 1.00)
WHITE = (0.92, 1.00, 1.00)

UARM_LEN = 0.286      # shoulder (±0.25, 1.12) → elbow (±0.48, 0.95)
FARM_LEN = 0.295      # elbow → wrist (±0.36, 1.22)
GLOVE_IN = 0.14       # the forearm ends this far inside the glove's centre
STRETCH_MAX = 1.6     # rubber-hose arms: a straight punch at the camera is longer than the arm

# ---------------------------------------------------------------- scene state
_J = {}          # joint name -> empty
_VARIANTS = {}   # slot -> {variant name -> object}; exactly one per slot is visible on a frame
_GLOVES = {}     # side -> glove joint (top-level)
_ARMS = {}       # side -> (uarm object, farm object)
_KEYED = []      # every object whose transform is keyed per frame (joints, arms, gloves)
_VIS_KEYED = []  # every variant object: its hide_render is keyed per frame

# ---------------------------------------------------------------- helpers
def _link(ob, parent):
    bpy.context.collection.objects.link(ob)
    if parent is not None:
        ob.parent = parent
    return ob

def joint(name, parent, x, z, y=0.0):
    """A joint: an empty at (x, y, z) in its PARENT's frame. Meshes hang on it in its local frame,
    so a keyed rotation turns about the joint — object origin = pivot, as BOXER.md §8 asks."""
    ob = bpy.data.objects.new("_j_" + name, None)
    ob.rotation_mode = "XYZ"
    ob.location = (x, y, z)
    _link(ob, parent)
    _J[name] = ob
    _KEYED.append(ob)
    return ob

def _obj(name, pts3, edges, part, cls, color, flash=False, parent=None, hidden=False):
    me = bpy.data.meshes.new(name); me.from_pydata(pts3, edges, []); me.update()
    ob = bpy.data.objects.new(name, me)
    _link(ob, parent)
    ob["part"] = part; ob["cls"] = cls; ob["color"] = color; ob["flash"] = flash
    ob["pivot"] = (0.0, 0.0, 0.0)
    if hidden:
        ob.hide_render = True
    return ob

def poly(name, pts, part, cls="detail", color=MAGENTA, closed=False, flash=False, parent=None, y=0.0, hidden=False):
    """A polyline in the y = `y` plane of `parent`'s frame; pts are (x, z)."""
    pts3 = [(x, y, z) for (x, z) in pts]
    edges = [(i, i + 1) for i in range(len(pts) - 1)]
    if closed and len(pts) > 2:
        edges.append((len(pts) - 1, 0))
    return _obj(name, pts3, edges, part, cls, color, flash, parent, hidden)

def contour(name, pts, part, color=MAGENTA, closed=True, flash=False, parent=None, lines=2, gap=CONTOUR_GAP, hidden=False):
    """THE COMIC OUTLINE: `lines` parallel polylines `gap` apart, offset along the 2D normal."""
    n = len(pts)
    objs = []
    for k in range(lines):
        off = (k - (lines - 1) / 2.0) * gap
        out = []
        for i in range(n):
            p0 = pts[(i - 1) % n] if closed else pts[max(i - 1, 0)]
            p1 = pts[(i + 1) % n] if closed else pts[min(i + 1, n - 1)]
            tx, tz = p1[0] - p0[0], p1[1] - p0[1]
            L = math.hypot(tx, tz) or 1.0
            nx, nz = -tz / L, tx / L
            out.append((pts[i][0] + nx * off, pts[i][1] + nz * off))
        objs.append(poly(f"{name}_{k}", out, part, "outline", color, closed, flash, parent, hidden=hidden))
    return objs

def ellipse(cx, cz, rx, rz, n=24, phase=0.0):
    return [(cx + rx * math.cos(2 * math.pi * i / n + phase), cz + rz * math.sin(2 * math.pi * i / n + phase)) for i in range(n)]

def arc(cx, cz, rx, rz, a0, a1, n=8):
    return [(cx + rx * math.cos(a0 + (a1 - a0) * i / n), cz + rz * math.sin(a0 + (a1 - a0) * i / n)) for i in range(n + 1)]

def spiral(cx, cz, r0, r1, turns=1.5, n=12):
    out = []
    for i in range(n + 1):
        t = i / n
        a = 2 * math.pi * turns * t
        r = r0 + (r1 - r0) * t
        out.append((cx + r * math.cos(a), cz + r * math.sin(a)))
    return out

def hatch(name, polygon, part, color, pitch=HATCH_PITCH, angle=0.0, parent=None):
    """Parallel lines at `pitch` clipped to a convex polygon (scanlines along x, tilted by `angle`)."""
    ca, sa = math.cos(angle), math.sin(angle)
    def rot(p): return (p[0] * ca + p[1] * sa, -p[0] * sa + p[1] * ca)
    def unrot(p): return (p[0] * ca - p[1] * sa, p[0] * sa + p[1] * ca)
    poly_r = [rot(p) for p in polygon]
    zs = [p[1] for p in poly_r]
    z = min(zs) + pitch / 2.0
    pts3, edges = [], []
    while z < max(zs):
        xs = []
        n = len(poly_r)
        for i in range(n):
            a, b = poly_r[i], poly_r[(i + 1) % n]
            if (a[1] <= z < b[1]) or (b[1] <= z < a[1]):
                t = (z - a[1]) / (b[1] - a[1])
                xs.append(a[0] + (b[0] - a[0]) * t)
        xs.sort()
        for j in range(0, len(xs) - 1, 2):
            p0, p1 = unrot((xs[j], z)), unrot((xs[j + 1], z))
            k = len(pts3)
            pts3 += [(p0[0], 0.0, p0[1]), (p1[0], 0.0, p1[1])]
            edges.append((k, k + 1))
        z += pitch
    return _obj(name, pts3, edges, part, "hatch", color, parent=parent)

def marker(name, parent, x, z, y=0.0):
    ob = bpy.data.objects.new("m_" + name, None)
    ob.location = (x, y, z); ob["marker"] = True
    _link(ob, parent)
    return ob

def variant(slot, name, ob, default=False):
    """Register `ob` as (one object of) the `name` variant of `slot`; the pose picks one variant
    per slot per frame and every other object of the slot is hidden. Boolean slots (sweat,
    spirals, bowl, bowr, pupils) have the one variant `on`."""
    _VARIANTS.setdefault(slot, {}).setdefault(name, []).append(ob)
    ob.hide_render = not default
    _VIS_KEYED.append(ob)
    return ob

# ---------------------------------------------------------------- THE FIGURE (joint-local metres; z up = engine y)
root = joint("root", None, 0.0, 0.0)
hips = joint("hips", root, 0.0, 0.70)
torso = joint("torso", root, 0.0, 0.70)          # pivots at the waist, like the hips
neck = joint("neck", torso, 0.0, 0.45)           # abs (0, 1.15)
head = joint("head", torso, 0.0, 0.50)           # abs (0, 1.20): the chin is the pivot
for side, sx in (("L", -1), ("R", 1)):
    joint(f"sh_{side}", torso, sx * 0.25, 0.42)  # abs (±0.25, 1.12): the shoulder the IK reads
    joint(f"leg_{side}", hips, sx * 0.12, -0.25)  # abs (±0.12, 0.45)
    joint(f"boot_{side}", _J[f"leg_{side}"], sx * 0.01, -0.35)  # abs (±0.13, 0.10)
    joint(f"ear_{side}", head, sx * 0.28, 0.42)   # abs (±0.28, 1.62)
    joint(f"brow_{side}", head, sx * 0.03, 0.50)  # the inner end, abs (±0.03, 1.70)
    joint(f"pupil_{side}", head, sx * 0.12, 0.40)  # abs (±0.12, 1.60)
for i in range(5):
    joint(f"crest_{i}", head, -0.16 + i * 0.08, 0.64)  # the hairline, abs z 1.84

# the head: a wide bulldog-jawed oval, chin 1.20 (the pivot), crown 1.90 — 37 % of a 1.9 m figure
contour("head", ellipse(0.0, 0.35, 0.28, 0.35, 32), "head", MAGENTA, parent=head)
hatch("hatch_head", ellipse(0.06, 0.30, 0.20, 0.30, 24), "hatch_head", MAGENTA, angle=0.35, parent=head)
# jug ears (their own joints: they flap on a hit), the broken-nose zigzag, six five-o'clock-shadow strokes on the jaw
poly("ear_L", [(0.0, 0.0), (-0.06, 0.04), (-0.08, -0.06), (-0.02, -0.12)], "ears", parent=_J["ear_L"])
poly("ear_R", [(0.0, 0.0), (0.06, 0.04), (0.08, -0.06), (0.02, -0.12)], "ears", parent=_J["ear_R"])
poly("nose", [(0.00, 0.40), (-0.03, 0.32), (0.03, 0.27), (-0.01, 0.22)], "nose", parent=head)
for i in range(6):
    x = -0.15 + i * 0.06
    poly(f"jaw_{i}", [(x, 0.07), (x + 0.02, 0.03)], "jaw_hatch", parent=head)
# EYES: variants on one part — rings at rest, slits before a hook, an X when hit, half-lidded when stunned
_eyes_L = ellipse(-0.12, 0.40, 0.08, 0.055, 16); _eyes_R = ellipse(0.12, 0.40, 0.08, 0.055, 16)
def _multi(name, lines, part, cls, color, parent, hidden=False, closed=False):
    """Several polylines in ONE object (one variant = one object): a mesh of disjoint edge chains.
    `closed` closes every chain of three points or more (two-point strokes stay strokes)."""
    pts3, edges = [], []
    for pts in lines:
        k = len(pts3)
        pts3 += [(x, 0.0, z) for (x, z) in pts]
        edges += [(k + i, k + i + 1) for i in range(len(pts) - 1)]
        if closed and len(pts) > 2:
            edges.append((k + len(pts) - 1, k))
    return _obj(name, pts3, edges, part, cls, color, parent=parent, hidden=hidden)
variant("eyes", "rings", _multi("eyes_rings", [_eyes_L, _eyes_R], "eyes", "detail", WHITE, head, closed=True), True)
variant("eyes", "slits", _multi("eyes_slits", [[(-0.20, 0.40), (-0.04, 0.40)], [(0.04, 0.40), (0.20, 0.40)]], "eyes", "detail", WHITE, head, hidden=True))
variant("eyes", "x", _multi("eyes_x", [[(-0.18, 0.46), (-0.06, 0.34)], [(-0.18, 0.34), (-0.06, 0.46)], [(0.06, 0.46), (0.18, 0.34)], [(0.06, 0.34), (0.18, 0.46)]], "eyes", "detail", WHITE, head, hidden=True))
variant("eyes", "dazed", _multi("eyes_dazed", [arc(-0.12, 0.40, 0.08, 0.055, math.pi, 2 * math.pi, 8) + [(-0.04, 0.43), (-0.20, 0.43)],
                                                  arc(0.12, 0.40, 0.08, 0.055, math.pi, 2 * math.pi, 8) + [(0.20, 0.43), (0.04, 0.43)]], "eyes", "detail", WHITE, head, hidden=True))
# pupils: their own joints (they drop, lean, come forward) and their own parts (the flash is per hand)
variant("pupils", "on", poly("pupil_L", ellipse(0.0, 0.0, 0.03, 0.03, 8), "pupil_L", "detail", MAGENTA, closed=True, flash=True, parent=_J["pupil_L"]), True)
variant("pupils", "on", poly("pupil_R", ellipse(0.0, 0.0, 0.03, 0.03, 8), "pupil_R", "detail", MAGENTA, closed=True, flash=True, parent=_J["pupil_R"]), True)
# brows: single strokes from the inner end outward; their ANGLE is the whole expression channel
poly("brow_L", [(0.0, 0.0), (-0.18, 0.0)], "brow", parent=_J["brow_L"])
poly("brow_R", [(0.0, 0.0), (0.18, 0.0)], "brow", parent=_J["brow_R"])
# THE MOUTH: five variants plus the tongue, one part, one visible (abs z ≈ 1.31–1.36 → head-local 0.11–0.16)
variant("mouth", "grin", poly("mouth_grin", [(-0.12, 0.14), (-0.06, 0.11), (0.06, 0.11), (0.12, 0.15)], "mouth", parent=head), True)
variant("mouth", "flat", poly("mouth_flat", [(-0.10, 0.12), (0.10, 0.12)], "mouth", parent=head, hidden=True))
variant("mouth", "o", poly("mouth_o", ellipse(0.0, 0.12, 0.05, 0.065, 12), "mouth", closed=True, parent=head, hidden=True))
variant("mouth", "grimace", _multi("mouth_grimace", [[(-0.13, 0.10), (-0.10, 0.16), (0.10, 0.16), (0.13, 0.10)]] + [[(-0.10 + k * 0.04, 0.16), (-0.10 + k * 0.04, 0.10)] for k in range(6)],
                                    "mouth", "detail", MAGENTA, head, hidden=True, closed=True))
variant("mouth", "crow", poly("mouth_crow", [(-0.10, 0.06), (0.10, 0.06), (0.10, 0.18), (-0.10, 0.18)], "mouth", closed=True, parent=head, hidden=True))
variant("mouth", "tongue", _multi("mouth_tongue", [[(-0.12, 0.15), (-0.06, 0.11), (0.06, 0.11), (0.12, 0.16)], [(-0.02, 0.11), (-0.03, 0.04), (0.0, 0.01), (0.03, 0.04), (0.02, 0.11)]],
                                   "mouth", "detail", MAGENTA, head, hidden=True))
# THE CREST: five tall spikes on their own roots at the hairline, VIOLET at rest, one lost per knockdown
for i in range(5):
    contour(f"crest_{i}", [(-0.03, 0.0), (0.02 * (i - 2), 0.28), (0.03, 0.0)], f"crest_{i}", VIOLET, closed=False, flash=True, parent=_J[f"crest_{i}"])
# the sweat (four strokes flung off the head) and the stagger's spirals (12 segments each, over the eyes)
variant("sweat", "on", _obj("sweat", [(-0.30, 0, 0.55), (-0.37, 0, 0.63), (-0.33, 0, 0.42), (-0.42, 0, 0.44), (0.30, 0, 0.55), (0.37, 0, 0.63), (0.33, 0, 0.42), (0.42, 0, 0.44)],
                           [(0, 1), (2, 3), (4, 5), (6, 7)], "sweat", "detail", WHITE, parent=head, hidden=True))
variant("spirals", "on", _multi("spirals", [spiral(-0.12, 0.40, 0.015, 0.075), spiral(0.12, 0.40, 0.015, 0.075)], "spirals", "detail", WHITE, head, hidden=True))
# neck, a tiny torso, cyan trunks with a waist stripe (the trunks ride on the hips, the torso on the waist)
poly("neck", [(-0.08, 0.07), (-0.08, -0.01)], "neck", parent=neck); poly("neck2", [(0.08, 0.07), (0.08, -0.01)], "neck", parent=neck)
contour("torso", [(-0.25, 0.45), (0.25, 0.45), (0.22, 0.0), (-0.22, 0.0)], "torso", MAGENTA, parent=torso)
hatch("hatch_torso", [(-0.20, 0.40), (0.20, 0.40), (0.18, 0.04), (-0.18, 0.04)], "hatch_torso", MAGENTA, angle=0.6, parent=torso)
poly("trunks", [(-0.22, 0.0), (0.22, 0.0), (0.24, -0.25), (-0.24, -0.25)], "trunks", "outline", CYAN, closed=True, parent=hips)
poly("waist", [(-0.22, -0.04), (0.22, -0.04)], "trunks", "detail", CYAN, parent=hips)
hatch("hatch_trunks", [(-0.20, -0.02), (0.20, -0.02), (0.22, -0.23), (-0.22, -0.23)], "hatch_trunks", CYAN, angle=-0.5, parent=hips)
# spindly legs and boots — the feet are drawn because the hooks' tell is the feet; the canvas bows under a planted boot
for side, sx in (("L", -1), ("R", 1)):
    poly(f"leg_{side}", [(0.0, 0.0), (sx * 0.02, -0.20), (sx * 0.01, -0.37)], "legs", parent=_J[f"leg_{side}"])
    poly(f"boot_{side}", [(0.0, 0.0), (sx * -0.07, 0.0), (sx * -0.09, -0.10), (sx * 0.11, -0.10), (sx * 0.09, 0.0)], "boots", "outline", MAGENTA, closed=True, parent=_J[f"boot_{side}"])
    variant("bow" + side.lower(), "on", poly(f"bow_{side}", arc(sx * 0.01, -0.10, 0.16, 0.045, math.pi, 2 * math.pi, 6), "boots", "outline", MAGENTA, parent=_J[f"boot_{side}"], hidden=True))
# THE ARMS: two bones a side, top-level, solved by the IK every frame (a doubled contour along local -Z)
for side, sx in (("L", -1), ("R", 1)):
    ua = bpy.data.objects.new(f"_j_uarm_{side}", None); ua.rotation_mode = "QUATERNION"; _link(ua, None); _KEYED.append(ua)
    fa = bpy.data.objects.new(f"_j_farm_{side}", None); fa.rotation_mode = "QUATERNION"; _link(fa, None); _KEYED.append(fa)
    contour(f"uarm_{side}", [(0.0, 0.0), (sx * 0.012, -0.14), (0.0, -UARM_LEN)], f"uarm_{side}", MAGENTA, closed=False, parent=ua, gap=0.02)
    contour(f"farm_{side}", [(0.0, 0.0), (0.0, -FARM_LEN)], f"farm_{side}", MAGENTA, closed=False, parent=fa)
    _ARMS[side] = (ua, fa)
# THE GLOVES: fat red pumpkins, r 0.24, tripled contour, laces, a thumb bump; top-level joints the pose places outright
for side, sx in (("L", -1), ("R", 1)):
    g = bpy.data.objects.new(f"_j_glove_{side}", None); g.rotation_mode = "XYZ"; _link(g, None); _KEYED.append(g)
    g.location = (sx * 0.30, 0.0, 1.32)
    _GLOVES[side] = g
    contour(f"glove_{side}", ellipse(0.0, 0.0, 0.24, 0.22, 24), f"glove_{side}", RED, lines=3, flash=True, parent=g)
    poly(f"thumb_{side}", ellipse(-sx * 0.14, 0.14, 0.07, 0.06, 10), f"glove_{side}", "detail", RED, closed=True, parent=g)
    for k in range(4):
        poly(f"lace_{side}_{k}", [(sx * 0.02, -0.10 + k * 0.05), (sx * 0.10, -0.09 + k * 0.05)], f"glove_{side}", "detail", RED, parent=g)
    hatch(f"hatch_glove_{side}", ellipse(0.0, 0.0, 0.20, 0.18, 16), f"hatch_glove_{side}", RED, angle=0.7, parent=g)
    marker(f"glove_{side}", g, 0.0, 0.0)
# markers the engine reads: the arc's origin (the gloves, above), the hit spark, your punch's target, the stars
marker("chin", head, 0.0, 0.0); marker("body", torso, 0.0, 0.22)
marker("eye_L", head, -0.12, 0.40); marker("eye_R", head, 0.12, 0.40); marker("crown", head, 0.0, 0.92)

# ================================================================== THE POSES
# A pose is a flat dict: (joint, channel) -> number for the rig, plus categorical slots (mouth,
# eyes, sweat, spirals, bowl, bowr, pupils). P() builds one from keyword arguments whose names are
# <joint>_<channel> — the LAST underscore splits them, so `crest_0_ry` and `glove_L_x` both parse.
# Channels: dx dy dz (metres, added to the rest position; dy is TOWARD THE CAMERA), rx ry (degrees;
# ry in the sprite plane, +ve tips the top to his right; rx out of it, -ve toward the camera),
# sx sy sz (scale). Gloves take absolute x y z, a uniform s and an in-plane ry. Anything a pose
# does not name is at rest, so a key pose lists only what moves.
REST_LOC = {name: tuple(ob.location) for name, ob in _J.items()}
CATEGORICAL = {"mouth": "grin", "eyes": "rings", "sweat": 0, "spirals": 0, "bowl": 0, "bowr": 0, "pupils": 1}
GLOVE_REST = {"L": (-0.36, 0.0, 1.30), "R": (0.36, 0.0, 1.30)}
_SCALE_CH = {"s", "sx", "sy", "sz"}

def _default(j, c):
    if j in ("glove_L", "glove_R") and c in ("x", "y", "z"):
        return GLOVE_REST[j[-1]]["xyz".index(c)]
    return 1.0 if c in _SCALE_CH else 0.0

def P(**kw):
    out = {}
    for k, v in kw.items():
        if k in CATEGORICAL:
            out[k] = v
        else:
            j, _, c = k.rpartition("_")
            assert j in _J or j in ("glove_L", "glove_R"), f"no joint {j!r} in {k!r}"
            out[(j, c)] = v
    return out

def merge(*poses):
    out = {}
    for p in poses:
        out.update(p)
    return out

def mirror(pose):
    """The x-flip (BOXER.md §8: legal for `peck_r` and nowhere else). L <-> R, x-ish channels
    negated, the crest reversed, the bows swapped."""
    swap = {"L": "R", "R": "L"}
    out = {}
    for k, v in pose.items():
        if isinstance(k, tuple):
            j, c = k
            if j[-2:] in ("_L", "_R"):
                j = j[:-1] + swap[j[-1]]
            elif j.startswith("crest_"):
                j = "crest_%d" % (4 - int(j[-1]))
            if c in ("dx", "ry", "x"):
                v = -v
            out[(j, c)] = v
        elif k == "bowl":
            out["bowr"] = v
        elif k == "bowr":
            out["bowl"] = v
        else:
            out[k] = v
    return out

def _ease(kind, t):
    if kind == "out":
        return 1.0 - (1.0 - t) ** 2
    if kind == "in":
        return t * t
    if kind == "smooth":
        return t * t * (3.0 - 2.0 * t)
    if kind == "hold":
        return 0.0
    return t

def _lerp_pose(a, b, t):
    keys = set(a) | set(b)
    out = {}
    for k in keys:
        if isinstance(k, tuple):
            va = a.get(k, _default(*k)); vb = b.get(k, _default(*k))
            out[k] = va + (vb - va) * t
        else:
            out[k] = a.get(k, CATEGORICAL[k])
    return out

FRAMES = []     # every global frame's full pose, in strip order
STRIPS = {}

def strip(name, count, keys, loop=False, events=None):
    """Author one strip: `keys` = [(local frame, pose, ease-to-next), ...]. Frames between keys
    are interpolated in Python at authoring time (the in-betweens an artist would draw); a key at
    frame == count is a loop target the sampler aims at without emitting. The sampled frames go
    into FRAMES, one complete pose each — Blender never tweens anything."""
    keys = sorted([(int(k[0]), k[1], k[2] if len(k) > 2 else "lin") for k in keys], key=lambda k: k[0])
    for f, _, _ in keys:
        assert 0 <= f <= count, f"{name}: key {f} outside 0..{count}"
    first = len(FRAMES)
    for f in range(count):
        prev = None; nxt = None
        for kf, kp, ke in keys:
            if kf <= f:
                prev = (kf, kp, ke)
            elif nxt is None:
                nxt = (kf, kp, ke)
        if prev is None:
            pose = _lerp_pose(nxt[1], nxt[1], 0.0)
        elif nxt is None or nxt[0] == prev[0]:
            pose = _lerp_pose(prev[1], prev[1], 0.0)
        else:
            t = (f - prev[0]) / float(nxt[0] - prev[0])
            pose = _lerp_pose(prev[1], nxt[1], _ease(prev[2], t))
        FRAMES.append(pose)
    STRIPS[name] = {"first": first, "count": count, "loop": loop, "events": dict(events or {})}

# ---------------------------------------------------------------- fragments the strips share
def gloves(lx, ly, lz, rx_, ry_, rz, s=1.0, sl=None, sr=None):
    return P(glove_L_x=lx, glove_L_y=ly, glove_L_z=lz, glove_R_x=rx_, glove_R_y=ry_, glove_R_z=rz,
             glove_L_s=sl if sl is not None else s, glove_R_s=sr if sr is not None else s)
def gl(x, y, z, s=1.0, ry=0.0): return P(glove_L_x=x, glove_L_y=y, glove_L_z=z, glove_L_s=s, glove_L_ry=ry)
def gr(x, y, z, s=1.0, ry=0.0): return P(glove_R_x=x, glove_R_y=y, glove_R_z=z, glove_R_s=s, glove_R_ry=ry)
def brows_v(a=20): return P(brow_L_ry=a, brow_R_ry=-a)           # the wind-up: outer ends up
def brows_hurt(a=18): return P(brow_L_ry=-a, brow_R_ry=a)        # inverted V
def brows_up(dz=0.04): return P(brow_L_dz=dz, brow_R_dz=dz)      # raised: the taunt, the win
def crest_fan(): return P(crest_0_ry=-38, crest_1_ry=-19, crest_3_ry=19, crest_4_ry=38, crest_2_sz=1.08)
def crest_droop(): return P(crest_0_ry=-75, crest_1_ry=-55, crest_2_rx=-70, crest_3_ry=55, crest_4_ry=75,
                            crest_0_sz=0.9, crest_1_sz=0.9, crest_3_sz=0.9, crest_4_sz=0.9)
def crest_up(s=1.22): return P(crest_0_ry=8, crest_1_ry=4, crest_3_ry=-4, crest_4_ry=-8,
                               crest_0_sz=s, crest_1_sz=s, crest_2_sz=s, crest_3_sz=s, crest_4_sz=s)
def crest_cross(): return P(crest_0_ry=52, crest_1_ry=36, crest_2_ry=6, crest_3_ry=-36, crest_4_ry=-52)
def crest_flat(): return P(crest_0_ry=-12, crest_1_ry=-6, crest_3_ry=6, crest_4_ry=12,
                           crest_0_sz=0.8, crest_1_sz=0.8, crest_2_sz=0.8, crest_3_sz=0.8, crest_4_sz=0.8)
def legs_bent(a=22, s=0.85, dz=-0.06): return P(leg_L_ry=a, leg_R_ry=-a, leg_L_sz=s, leg_R_sz=s, boot_L_ry=-a, boot_R_ry=a, root_dz=dz)
LIE_Z = 0.40   # the lying figure's axis: his right side (down) clears the canvas AND the plate's bottom edge (0.09 m at 2.6 m)
def lying(dz=LIE_Z, sz=1.0): return P(root_ry=90, root_dx=-0.95, root_dz=dz, root_sz=sz, root_rx=0)
def head_hit(a=25): return P(head_ry=a, head_sx=1.28, head_sz=0.82, head_dx=0.03, eyes="x", pupils=0, mouth="o", sweat=1,
                            crest_2_ry=22, ear_L_ry=40, ear_R_ry=-40, **{"brow_L_ry": -18, "brow_R_ry": 18})

# ---------------------------------------------------------------- THE STRIPS (BOXER.md §8, in its order)
# idle: guard up, the weight shifting foot to foot, brows flat, mouth grin
strip("idle", 8, [
    (0, merge(P(root_dx=0.0, leg_L_ry=2, glove_R_z=1.32), gl(-0.36, 0, 1.30)), "smooth"),
    (2, merge(P(root_dx=0.03, leg_L_ry=4, leg_R_ry=-2, head_dz=0.01, glove_R_z=1.30), gl(-0.36, 0, 1.32)), "smooth"),
    (4, merge(P(root_dx=0.0, leg_L_ry=2, glove_R_z=1.32), gl(-0.36, 0, 1.30)), "smooth"),
    (6, merge(P(root_dx=-0.03, leg_L_ry=2, leg_R_ry=-4, head_dz=0.01, glove_R_z=1.30), gl(-0.36, 0, 1.32)), "smooth"),
    (8, merge(P(root_dx=0.0, leg_L_ry=2, glove_R_z=1.32), gl(-0.36, 0, 1.30)), "smooth"),
], loop=True)

# guard: gloves high over the face, the eyes between them, brighter by the overlap with the head
strip("guard", 4, [
    (0, merge(gloves(-0.34, 0.04, 1.50, 0.34, 0.04, 1.50), P(head_dz=-0.01, mouth="flat")), "smooth"),
    (2, merge(gloves(-0.34, 0.04, 1.53, 0.34, 0.04, 1.53), P(head_dz=-0.02, mouth="flat")), "smooth"),
    (4, merge(gloves(-0.34, 0.04, 1.50, 0.34, 0.04, 1.50), P(head_dz=-0.01, mouth="flat")), "smooth"),
], loop=True)

# peck_l: the left jab. Tell 0-5 (glove back and down, brow V, left pupil forward, the left shoulder
# dips, the head tilts to HIS right); strike 6-8 (the glove 1.8x at the camera, the arm straight);
# recover 9-14 (the arm comes home). peck_r is its mirror.
_PECK_KEYS = [
    (0, merge(gl(-0.40, -0.05, 1.22), P(torso_ry=-6, head_ry=8, pupil_L_s=1.3, pupil_L_dy=0.02, mouth="flat"), brows_v(16)), "out"),
    (3, merge(gl(-0.50, -0.16, 1.10), P(torso_ry=-8, torso_dz=-0.02, head_ry=10, pupil_L_s=1.4, pupil_L_dy=0.03, mouth="flat"), brows_v(22)), "hold"),
    (5, merge(gl(-0.50, -0.16, 1.10), P(torso_ry=-8, torso_dz=-0.02, head_ry=10, pupil_L_s=1.4, pupil_L_dy=0.03, mouth="flat"), brows_v(22)), "lin"),
    (6, merge(gl(-0.06, 0.62, 1.60, 1.8), P(torso_ry=4, head_ry=15, mouth="grimace"), brows_v(22)), "lin"),
    (7, merge(gl(-0.03, 0.72, 1.62, 1.8), P(torso_ry=5, head_ry=15, mouth="grimace"), brows_v(22)), "lin"),
    (8, merge(gl(-0.12, 0.45, 1.50, 1.35), P(torso_ry=3, head_ry=10, mouth="grimace"), brows_v(18)), "lin"),
    (9, merge(gl(-0.25, 0.20, 1.35, 1.1), P(torso_ry=0, head_ry=5, mouth="flat"), brows_v(12)), "out"),
    (12, merge(gl(-0.36, 0.02, 1.28, 1.0), P(mouth="flat"), brows_v(4)), "lin"),
    (14, merge(gl(-0.36, 0.0, 1.30, 1.0), P(mouth="grin")), "lin"),
]
strip("peck_l", 15, _PECK_KEYS, events={"telegraph": 0, "strike": 6, "done": 14})
strip("peck_r", 15, [(f, mirror(p), e) for f, p, e in _PECK_KEYS], events={"telegraph": 0, "strike": 6, "done": 14})

# wing_r: the wide right hook to the head. Tell 0-7 (glove to the hip, crest fans GOLD, the right
# boot plants at 2, eyes to slits); strike 8-11 (the glove crosses the plate 1.8x, the shoulder
# turns); recover 12-21 (over-rotated, the back shoulder shown: the biggest R1 opening).
strip("wing_r", 22, [
    (0, merge(gr(0.42, -0.05, 1.10), crest_fan(), P(eyes="slits", pupils=0, torso_ry=5, root_dx=0.03, mouth="flat"), brows_v(18)), "lin"),
    (1, merge(gr(0.46, -0.12, 0.90), crest_fan(), P(eyes="slits", pupils=0, torso_ry=6, root_dx=0.05, leg_R_sz=0.86, mouth="flat"), brows_v(18)), "lin"),
    (2, merge(gr(0.48, -0.16, 0.78), crest_fan(), P(eyes="slits", pupils=0, torso_ry=8, root_dx=0.06, head_ry=6, bowr=1, mouth="flat"), brows_v(20)), "lin"),
    (4, merge(gr(0.50, -0.18, 0.76), crest_fan(), P(eyes="slits", pupils=0, torso_ry=9, root_dx=0.06, head_ry=7, bowr=0, mouth="flat"), brows_v(20)), "lin"),
    (7, merge(gr(0.52, -0.20, 0.74), crest_fan(), P(eyes="slits", pupils=0, torso_ry=10, root_dx=0.06, head_ry=8, mouth="flat"), brows_v(22)), "lin"),
    (8, merge(gr(0.62, 0.30, 1.55, 1.8), P(torso_ry=2, root_dx=0.04, head_ry=0, mouth="grimace"), brows_v(22)), "lin"),
    (9, merge(gr(0.18, 0.70, 1.70, 1.8), P(torso_ry=-10, root_dx=0.0, head_ry=-8, mouth="grimace"), brows_v(22)), "lin"),
    (10, merge(gr(-0.32, 0.55, 1.68, 1.6), P(torso_ry=-16, torso_sx=0.85, root_dx=-0.03, head_ry=-14, mouth="grimace"), brows_v(18)), "lin"),
    (11, merge(gr(-0.58, 0.22, 1.50, 1.3), P(torso_ry=-20, torso_sx=0.78, root_dx=-0.05, head_ry=-18, mouth="o"), brows_v(10)), "lin"),
    (12, merge(gr(-0.68, 0.05, 1.20), gl(-0.40, 0.0, 1.05), P(torso_ry=-20, torso_sx=0.75, root_dx=-0.05, head_ry=-18, head_dx=-0.04, mouth="o"), brows_hurt(14)), "smooth"),
    (16, merge(gr(-0.66, 0.05, 1.18), gl(-0.42, 0.0, 1.03), P(torso_ry=-19, torso_sx=0.76, root_dx=-0.05, head_ry=-17, head_dx=-0.04, mouth="o"), brows_hurt(14)), "smooth"),
    (19, merge(gr(-0.10, 0.02, 1.15), gl(-0.38, 0.0, 1.22), P(torso_ry=-8, torso_sx=0.9, root_dx=-0.02, head_ry=-6, mouth="flat"), brows_hurt(6)), "lin"),
    (21, merge(gr(0.36, 0.0, 1.30), gl(-0.36, 0.0, 1.30), P(mouth="grin")), "lin"),
], events={"telegraph": 0, "stamp": 2, "strike": 8, "done": 21})

# wing_l: the low left hook to the body. Tell 0-7 (glove drops BELOW the plate's edge, crest droops,
# the left boot plants at 2, pupils down); strike 8-11 (the glove sweeps in low from the player's
# right); recover 12-18.
strip("wing_l", 19, [
    (0, merge(gl(-0.42, -0.02, 1.00), crest_droop(), P(pupil_L_dz=-0.03, pupil_R_dz=-0.03, torso_ry=-5, root_dx=-0.03, mouth="flat"), brows_v(18)), "lin"),
    (1, merge(gl(-0.44, 0.05, 0.55), crest_droop(), P(pupil_L_dz=-0.03, pupil_R_dz=-0.03, torso_ry=-6, root_dx=-0.05, leg_L_sz=0.86, mouth="flat"), brows_v(18)), "lin"),
    (2, merge(gl(-0.42, 0.14, -0.12), crest_droop(), P(pupil_L_dz=-0.03, pupil_R_dz=-0.03, torso_ry=-8, root_dx=-0.06, head_ry=-6, bowl=1, mouth="flat"), brows_v(20)), "lin"),
    (4, merge(gl(-0.40, 0.15, -0.15), crest_droop(), P(pupil_L_dz=-0.03, pupil_R_dz=-0.03, torso_ry=-9, root_dx=-0.06, head_ry=-7, bowl=0, mouth="flat"), brows_v(20)), "lin"),
    (7, merge(gl(-0.40, 0.15, -0.16), crest_droop(), P(pupil_L_dz=-0.03, pupil_R_dz=-0.03, torso_ry=-10, root_dx=-0.06, head_ry=-8, mouth="flat"), brows_v(22)), "lin"),
    (8, merge(gl(-0.62, 0.35, 0.90, 1.8), P(torso_ry=-4, root_dx=-0.04, mouth="grimace"), brows_v(22)), "lin"),
    (9, merge(gl(-0.22, 0.72, 1.08, 1.8), P(torso_ry=2, root_dx=0.0, head_ry=2, mouth="grimace"), brows_v(22)), "lin"),
    (10, merge(gl(0.18, 0.60, 1.14, 1.6), P(torso_ry=8, root_dx=0.03, head_ry=6, mouth="grimace"), brows_v(18)), "lin"),
    (11, merge(gl(0.40, 0.30, 1.10, 1.3), P(torso_ry=12, torso_sx=0.9, root_dx=0.04, head_ry=10, mouth="o"), brows_v(10)), "lin"),
    (12, merge(gl(0.45, 0.10, 1.05), gr(0.40, 0.0, 1.15), P(torso_ry=12, torso_sx=0.85, root_dx=0.04, head_ry=10, mouth="o"), brows_hurt(10)), "smooth"),
    (15, merge(gl(0.10, 0.02, 1.10), gr(0.38, 0.0, 1.22), P(torso_ry=5, torso_sx=0.95, root_dx=0.02, head_ry=4, mouth="flat"), brows_hurt(4)), "lin"),
    (18, merge(gl(-0.36, 0.0, 1.30), gr(0.36, 0.0, 1.30), P(mouth="grin")), "lin"),
], events={"telegraph": 0, "stamp": 2, "strike": 8, "done": 18})

# sunrise: the uppercut. Tell 0-9 (the crow — mouth rectangle — the crest WHITE and straight, both
# gloves drop out of frame, then the RIGHT rises from the bottom edge growing); strike 10-13 (up
# the column past the chin, sky-high); recover 14-27 (arms in the air, chin up: the opening).
strip("sunrise", 28, [
    (0, merge(gloves(-0.40, 0.0, 1.00, 0.40, 0.0, 1.00), crest_up(1.15), brows_up(0.03), P(mouth="crow", pupil_L_s=1.3, pupil_R_s=1.3, head_rx=12)), "lin"),
    (2, merge(gloves(-0.42, 0.05, 0.50, 0.42, 0.05, 0.50), crest_up(1.2), brows_up(0.03), legs_bent(8, 0.95, -0.03), P(mouth="crow", pupil_L_s=1.3, pupil_R_s=1.3, head_rx=12)), "lin"),
    (4, merge(gloves(-0.44, 0.12, -0.10, 0.40, 0.14, -0.12), crest_up(1.25), brows_up(0.03), legs_bent(12, 0.9, -0.06), P(mouth="crow", pupil_L_s=1.3, pupil_R_s=1.3, head_rx=12, torso_rx=-8)), "lin"),
    (5, merge(gl(-0.44, 0.12, -0.10), gr(0.36, 0.20, -0.02, 1.05), crest_up(1.25), brows_up(0.03), legs_bent(12, 0.9, -0.06), P(mouth="crow", pupil_L_s=1.3, pupil_R_s=1.3, head_rx=12, torso_rx=-8)), "lin"),
    (7, merge(gl(-0.44, 0.12, -0.10), gr(0.30, 0.28, 0.30, 1.2), crest_up(1.25), brows_up(0.03), legs_bent(10, 0.92, -0.05), P(mouth="crow", pupil_L_s=1.3, pupil_R_s=1.3, head_rx=12, torso_rx=-6)), "lin"),
    (9, merge(gl(-0.44, 0.12, -0.10), gr(0.22, 0.35, 0.62, 1.35), crest_up(1.25), brows_up(0.03), legs_bent(8, 0.95, -0.03), P(mouth="crow", pupil_L_s=1.3, pupil_R_s=1.3, head_rx=12, torso_rx=-4)), "lin"),
    (10, merge(gl(-0.44, 0.10, -0.05), gr(0.08, 0.55, 1.10, 1.8), crest_up(1.2), brows_up(0.03), P(mouth="crow", pupil_L_s=1.3, pupil_R_s=1.3, head_rx=15, leg_L_sz=1.05, leg_R_sz=1.05, root_dz=0.0)), "lin"),
    (11, merge(gl(-0.44, 0.08, 0.10), gr(0.0, 0.75, 1.66, 1.8), crest_up(1.15), brows_up(0.03), P(mouth="o", head_rx=18, leg_L_sz=1.1, leg_R_sz=1.1, root_dz=0.06)), "lin"),
    (12, merge(gl(-0.44, 0.06, 0.30), gr(-0.06, 0.60, 2.08, 1.6), crest_up(1.1), brows_up(0.03), P(mouth="o", head_rx=20, leg_L_sz=1.1, leg_R_sz=1.1, root_dz=0.08)), "lin"),
    (13, merge(gl(-0.42, 0.05, 0.60), gr(-0.08, 0.40, 2.30, 1.3), crest_up(1.05), brows_up(0.03), P(mouth="o", head_rx=20, leg_L_sz=1.05, leg_R_sz=1.05, root_dz=0.04)), "lin"),
    (14, merge(gl(-0.40, 0.05, 1.95), gr(0.05, 0.10, 2.35), brows_up(0.04), P(mouth="o", head_rx=20)), "smooth"),
    (20, merge(gl(-0.42, 0.05, 1.92), gr(0.08, 0.10, 2.32), brows_up(0.04), P(mouth="o", head_rx=18)), "smooth"),
    (24, merge(gl(-0.40, 0.02, 1.55), gr(0.30, 0.05, 1.70), brows_up(0.02), P(mouth="flat", head_rx=8)), "lin"),
    (27, merge(gl(-0.36, 0.0, 1.30), gr(0.36, 0.0, 1.30), P(mouth="grin")), "lin"),
], events={"telegraph": 0, "crow": 0, "strike": 10, "done": 27})

# feint_peck: the first 30 % of a peck — the glove stops, the shoulder does NOT dip; back to idle
strip("feint_peck", 4, [
    (0, merge(gl(-0.40, -0.04, 1.24), P(pupil_L_s=1.3, pupil_L_dy=0.02, mouth="flat"), brows_v(10)), "lin"),
    (1, merge(gl(-0.43, -0.06, 1.20), P(pupil_L_s=1.3, pupil_L_dy=0.02, mouth="flat"), brows_v(12)), "hold"),
    (2, merge(gl(-0.43, -0.06, 1.20), P(pupil_L_s=1.3, pupil_L_dy=0.02, mouth="flat"), brows_v(12)), "lin"),
    (3, merge(gl(-0.38, -0.02, 1.27), P(mouth="grin")), "lin"),
], events={"telegraph": 0, "done": 3})

# feint_crow: the crow starts, the crest goes white (the engine) and straight, then snaps back; the mouth closes
strip("feint_crow", 5, [
    (0, merge(gloves(-0.40, 0.0, 1.05, 0.40, 0.0, 1.05), crest_up(1.2), brows_up(0.03), P(mouth="crow", head_rx=8)), "lin"),
    (2, merge(gloves(-0.42, 0.03, 0.85, 0.42, 0.03, 0.85), crest_up(1.25), brows_up(0.03), P(mouth="crow", head_rx=8)), "lin"),
    (3, merge(gloves(-0.38, 0.0, 1.20, 0.38, 0.0, 1.20), P(mouth="flat")), "lin"),
    (4, merge(gloves(-0.36, 0.0, 1.30, 0.36, 0.0, 1.30), P(mouth="grin")), "lin"),
], events={"telegraph": 0, "cut": 3, "done": 4})

# half_stamp: the boot plants, nothing else moves
strip("half_stamp", 3, [
    (0, P(bowr=1, root_dx=0.02, mouth="flat"), "hold"),
    (1, P(bowr=1, root_dx=0.02, mouth="flat"), "lin"),
    (2, P(bowr=0, root_dx=0.0), "lin"),
], events={"stamp": 0, "done": 2})

# hit_head: the head snaps 25° away from the hand and stretches sideways, X eyes, the gasp, sweat, one spike wobbles, the ears flap
strip("hit_head", 3, [
    (0, merge(head_hit(25), gloves(-0.38, 0.0, 1.22, 0.38, 0.0, 1.22), P(torso_ry=4)), "lin"),
    (1, merge(P(head_ry=12, head_sx=1.10, head_sz=0.94, eyes="x", pupils=0, mouth="o", sweat=1, crest_2_ry=-12, ear_L_ry=20, ear_R_ry=-20, torso_ry=2),
              gloves(-0.38, 0.0, 1.24, 0.38, 0.0, 1.24), brows_hurt(14)), "lin"),
    (2, merge(P(head_ry=4, eyes="rings", pupils=1, mouth="flat", sweat=0, crest_2_ry=5), gloves(-0.37, 0.0, 1.27, 0.37, 0.0, 1.27), brows_hurt(8)), "lin"),
], events={"flash": 0})

# hit_body: the torso folds toward the camera, the gloves fly outward, the mouth goes O — the "guard opens" picture
strip("hit_body", 3, [
    (0, merge(P(torso_sz=0.72, torso_rx=-14, torso_dz=-0.02, head_dz=-0.10, head_rx=-20, head_sz=0.95, mouth="o", pupil_L_dz=-0.02, pupil_R_dz=-0.02),
              brows_hurt(20), gloves(-0.70, 0.05, 1.05, 0.70, 0.05, 1.05), legs_bent(10, 0.92, -0.04)), "lin"),
    (1, merge(P(torso_sz=0.82, torso_rx=-8, head_dz=-0.06, head_rx=-12, mouth="o"), brows_hurt(14), gloves(-0.60, 0.03, 1.10, 0.60, 0.03, 1.10), legs_bent(6, 0.96, -0.02)), "lin"),
    (2, merge(P(torso_sz=0.92, torso_rx=-3, head_dz=-0.02, head_rx=-5, mouth="flat"), brows_hurt(8), gloves(-0.48, 0.0, 1.18, 0.48, 0.0, 1.18), legs_bent(2, 1.0, 0.0)), "lin"),
], events={"flash": 0, "open": 0})

# stagger: knees bent, gloves at the hips, spiral eyes, crossed crest, tongue out, the head bobbing (8, loop)
_STAG = merge(legs_bent(22, 0.85, -0.06), gloves(-0.44, 0.02, 0.72, 0.44, 0.02, 0.72), crest_cross(), brows_hurt(15),
              P(eyes="none", pupils=0, spirals=1, sweat=1, mouth="tongue"))
strip("stagger", 8, [
    (0, merge(_STAG, P(head_ry=-12, torso_ry=-4, root_dx=-0.02)), "smooth"),
    (2, merge(_STAG, P(head_ry=4, torso_ry=2, root_dx=0.0, head_dz=-0.02)), "smooth"),
    (4, merge(_STAG, P(head_ry=12, torso_ry=4, root_dx=0.02)), "smooth"),
    (6, merge(_STAG, P(head_ry=-4, torso_ry=-2, root_dx=0.0, head_dz=-0.02)), "smooth"),
    (8, merge(_STAG, P(head_ry=-12, torso_ry=-4, root_dx=-0.02)), "smooth"),
], loop=True, events={"open": 0})

# stun: hands low, a dazed face — shorter than a stagger, no spirals (4, loop)
_STUN = merge(gloves(-0.40, 0.0, 0.88, 0.40, 0.0, 0.88), legs_bent(8, 0.95, -0.02), brows_up(0.02),
              P(eyes="dazed", pupil_L_dz=-0.02, pupil_R_dz=-0.02, mouth="flat", head_dz=-0.02))
strip("stun", 4, [
    (0, merge(_STUN, P(head_ry=-6, torso_ry=-2)), "smooth"),
    (2, merge(_STUN, P(head_ry=6, torso_ry=2)), "smooth"),
    (4, merge(_STUN, P(head_ry=-6, torso_ry=-2)), "smooth"),
], loop=True, events={"open": 0})

# knockdown: the hit, the knees buckle, he falls TOWARD the camera glove up, then the cut to the lying pose
_LIE_ARMS = merge(gl(0.06, 0.05, 1.02), gr(-0.30, 0.05, 0.22))
strip("knockdown", 13, [
    (0, merge(head_hit(25), gloves(-0.62, 0.10, 1.40, 0.60, 0.10, 1.45)), "lin"),
    (2, merge(legs_bent(20, 0.85, -0.05), P(torso_rx=-10, head_ry=10, head_sx=1.1, head_sz=0.92, eyes="x", pupils=0, mouth="grimace", sweat=1), brows_hurt(18), gloves(-0.55, 0.15, 1.25, 0.55, 0.15, 1.25)), "lin"),
    (4, merge(legs_bent(22, 0.8, -0.05), P(root_rx=-18, torso_rx=-4, head_rx=5, eyes="x", pupils=0, mouth="grimace", sweat=1), brows_hurt(18), gl(-0.50, 0.35, 1.10), gr(0.45, 0.40, 1.05)), "lin"),
    (6, merge(legs_bent(25, 0.75, -0.05), P(root_rx=-28, torso_rx=-4, head_rx=5, eyes="x", pupils=0, mouth="o", sweat=1), brows_hurt(18), gl(-0.50, 0.75, 0.85), gr(0.40, 0.80, 0.80, 1.15)), "lin"),
    (8, merge(legs_bent(25, 0.72, -0.05), P(root_rx=-38, torso_rx=-4, head_rx=5, eyes="x", pupils=0, mouth="o", sweat=1), brows_hurt(18), gl(-0.48, 1.05, 0.55), gr(0.40, 1.10, 0.50, 1.25)), "lin"),
    (9, merge(lying(LIE_Z, 0.88), P(head_ry=-10, eyes="x", pupils=0, mouth="o", sweat=1), brows_hurt(18), gl(0.05, 0.05, 1.05), gr(-0.30, 0.05, 0.22)), "lin"),
    (10, merge(lying(LIE_Z + 0.06, 1.04), P(head_ry=-4, eyes="x", pupils=0, mouth="o", sweat=1), brows_hurt(18), gl(0.05, 0.05, 1.10), gr(-0.30, 0.05, 0.24)), "lin"),
    (11, merge(lying(LIE_Z, 0.97), P(head_ry=-2, eyes="x", pupils=0, mouth="o", sweat=0), brows_hurt(18), gl(0.08, 0.05, 1.00), gr(-0.30, 0.05, 0.22)), "lin"),
    (12, merge(lying(LIE_Z, 1.0), P(eyes="x", pupils=0, mouth="o"), brows_hurt(18), _LIE_ARMS), "lin"),
], events={"down": 12})

# down: on the canvas, X eyes, the chest heaving, the raised glove wavering; the engine draws the stars at `crown` (4, loop)
_DOWN = merge(lying(LIE_Z, 1.0), P(eyes="x", pupils=0, mouth="o"), brows_hurt(18), gr(-0.30, 0.05, 0.22))
strip("down", 4, [
    (0, merge(_DOWN, P(torso_sx=1.0), gl(0.06, 0.05, 1.02)), "smooth"),
    (2, merge(_DOWN, P(torso_sx=1.06, head_ry=-2), gl(0.04, 0.05, 1.06)), "smooth"),
    (4, merge(_DOWN, P(torso_sx=1.0), gl(0.06, 0.05, 1.02)), "smooth"),
], loop=True)

# getup: to one knee, a slip, to standing, a shake of the head (8, `up` at 7)
strip("getup", 8, [
    (0, merge(_DOWN, gl(0.06, 0.05, 1.02)), "lin"),
    (1, merge(P(root_dz=-0.30, root_rx=-5, leg_R_ry=-75, leg_R_sz=0.9, boot_R_ry=75, leg_L_ry=40, leg_L_sz=0.75, boot_L_ry=-40, torso_rx=-15, torso_ry=-5,
                head_rx=-10, head_dz=-0.02, eyes="dazed", mouth="grimace", sweat=1), brows_hurt(16), gl(-0.55, 0.25, 0.20), gr(0.35, 0.10, 0.75)), "lin"),
    (2, merge(P(root_dz=-0.26, root_rx=-4, leg_R_ry=-75, leg_R_sz=0.9, boot_R_ry=75, leg_L_ry=40, leg_L_sz=0.75, boot_L_ry=-40, torso_rx=-12, torso_ry=-5,
                head_rx=-8, head_dz=-0.02, eyes="dazed", mouth="grimace", sweat=1), brows_hurt(16), gl(-0.55, 0.22, 0.24), gr(0.36, 0.08, 0.78)), "lin"),
    (3, merge(P(root_dz=-0.14, leg_R_ry=-45, leg_R_sz=0.9, boot_R_ry=45, leg_L_ry=25, leg_L_sz=0.85, boot_L_ry=-25, torso_rx=-8, head_rx=-5,
                eyes="dazed", mouth="flat", sweat=0), brows_hurt(12), gl(-0.50, 0.15, 0.55), gr(0.38, 0.05, 0.95)), "lin"),
    (4, merge(P(root_dz=-0.24, root_ry=-8, leg_R_ry=-30, leg_R_sz=0.9, boot_R_ry=30, leg_L_ry=35, leg_L_sz=0.8, boot_L_ry=-35, torso_rx=-6, head_ry=-15,
                eyes="rings", pupil_L_dz=-0.02, pupil_R_dz=-0.02, mouth="o", sweat=1), brows_hurt(16), gl(-0.62, 0.20, 0.30), gr(0.30, 0.05, 0.80)), "lin"),
    (5, merge(P(root_dz=-0.10, root_ry=-3, leg_R_ry=-15, leg_R_sz=0.92, boot_R_ry=15, leg_L_ry=15, leg_L_sz=0.92, boot_L_ry=-15, torso_rx=-3, head_ry=-6,
                mouth="flat", sweat=0), brows_hurt(10), gl(-0.45, 0.10, 0.70), gr(0.42, 0.02, 0.95)), "lin"),
    (6, merge(P(root_dz=-0.02, leg_R_ry=-5, leg_R_sz=0.98, boot_R_ry=5, leg_L_ry=5, leg_L_sz=0.98, boot_L_ry=-5, head_ry=4, mouth="flat"), gloves(-0.42, 0.0, 1.0, 0.42, 0.0, 1.0)), "lin"),
    (7, merge(P(head_ry=-14, head_sx=1.06, mouth="flat", sweat=1), gloves(-0.38, 0.0, 1.18, 0.38, 0.0, 1.18)), "lin"),
], events={"up": 7})

# ko: the fall (8 frames), then the flat pose with the tongue out and the crest flat; the engine fades the gain
strip("ko", 12, [
    (0, merge(head_hit(25), P(crest_2_ry=25), gloves(-0.65, 0.10, 1.35, 0.65, 0.10, 1.35)), "lin"),
    (2, merge(legs_bent(15, 0.85, -0.04), P(root_rx=-8, head_ry=10, head_rx=-4, head_sx=1.1, head_sz=0.92, eyes="x", pupils=0, mouth="grimace", sweat=1), brows_hurt(18), gloves(-0.55, 0.25, 1.15, 0.55, 0.25, 1.15)), "lin"),
    (4, merge(legs_bent(20, 0.78, -0.04), P(root_rx=-20, torso_rx=-4, head_rx=0, eyes="x", pupils=0, mouth="o", sweat=1), brows_hurt(18), gloves(-0.50, 0.55, 0.95, 0.50, 0.55, 0.95)), "lin"),
    (6, merge(legs_bent(25, 0.72, -0.04), P(root_rx=-32, torso_rx=-4, head_rx=3, eyes="x", pupils=0, mouth="o", sweat=1), brows_hurt(18), gloves(-0.45, 0.95, 0.70, 0.45, 0.95, 0.70, 1.15)), "lin"),
    (7, merge(legs_bent(25, 0.72, -0.04), P(root_rx=-38, torso_rx=-4, head_rx=5, eyes="x", pupils=0, mouth="o", sweat=1), brows_hurt(18), gloves(-0.45, 1.15, 0.55, 0.45, 1.15, 0.55, 1.2)), "lin"),
    (8, merge(lying(LIE_Z, 0.90), crest_flat(), P(eyes="x", pupils=0, mouth="tongue", sweat=1), brows_hurt(18), gl(-0.20, 0.30, 0.25), gr(-0.40, 0.05, 0.20)), "lin"),
    (9, merge(lying(LIE_Z + 0.05, 1.03), crest_flat(), P(eyes="x", pupils=0, mouth="tongue", sweat=1), brows_hurt(18), gl(-0.18, 0.32, 0.28), gr(-0.40, 0.05, 0.22)), "lin"),
    (10, merge(lying(LIE_Z, 0.98), crest_flat(), P(eyes="x", pupils=0, mouth="tongue", sweat=0), brows_hurt(18), gl(-0.20, 0.30, 0.25), gr(-0.40, 0.05, 0.20)), "lin"),
    (11, merge(lying(LIE_Z, 1.0), crest_flat(), P(eyes="x", pupils=0, mouth="tongue"), brows_hurt(18), gl(-0.20, 0.30, 0.25), gr(-0.40, 0.05, 0.20)), "lin"),
], events={"ko": 11})

# taunt: a glove beckons, brows raised, tongue out — the intro's pose (holds on its last frame)
_TAUNT = merge(gl(-0.44, 0.02, 0.74), brows_up(0.04), P(mouth="tongue", head_ry=8, torso_ry=4, root_dx=0.02))
strip("taunt", 10, [
    (0, merge(_TAUNT, gr(0.30, 0.30, 1.20, 1.05, -30), P(head_dz=0.0)), "smooth"),
    (2, merge(_TAUNT, gr(0.22, 0.42, 1.25, 1.1, -70), P(head_dz=0.01)), "smooth"),
    (4, merge(_TAUNT, gr(0.32, 0.28, 1.18, 1.05, -25), P(head_dz=0.02)), "smooth"),
    (6, merge(_TAUNT, gr(0.22, 0.42, 1.25, 1.1, -70), P(head_dz=0.01)), "smooth"),
    (8, merge(_TAUNT, gr(0.30, 0.32, 1.20, 1.05, -40), P(head_dz=0.0)), "lin"),
    (9, merge(_TAUNT, gr(0.24, 0.40, 1.24, 1.1, -65), P(head_dz=0.0)), "lin"),
])

# win: both gloves up, bouncing — the TIME / NO DECISION card (8, loop)
_WIN = merge(brows_up(0.04), crest_up(1.15), P(mouth="grin"))
strip("win", 8, [
    (0, merge(_WIN, gloves(-0.45, 0.0, 2.10, 0.45, 0.0, 2.10), P(root_dz=0.0, leg_L_sz=0.96, leg_R_sz=0.96)), "smooth"),
    (2, merge(_WIN, gloves(-0.45, 0.0, 2.18, 0.45, 0.0, 2.18), P(root_dz=0.10, leg_L_sz=1.05, leg_R_sz=1.05)), "smooth"),
    (4, merge(_WIN, gloves(-0.45, 0.0, 2.08, 0.45, 0.0, 2.08), P(root_dz=0.02, leg_L_sz=0.98, leg_R_sz=0.98)), "smooth"),
    (6, merge(_WIN, gloves(-0.45, 0.0, 2.20, 0.45, 0.0, 2.20), P(root_dz=0.12, leg_L_sz=1.06, leg_R_sz=1.06)), "smooth"),
    (8, merge(_WIN, gloves(-0.45, 0.0, 2.10, 0.45, 0.0, 2.10), P(root_dz=0.0, leg_L_sz=0.96, leg_R_sz=0.96)), "smooth"),
], loop=True)

# ================================================================== THE BAKE
# One keyframe per object per frame. The shoulders are read through the rig's OWN matrices (the
# same T·R·S composition Blender uses, parent inverse = identity because `ob.parent` was assigned
# directly), never through the depsgraph mid-bake: `view_layer.update()` re-evaluates the animation
# at the scene's current frame and would write the last keyed pose back over the one being set.
def _basis(ob):
    return Matrix.Translation(ob.location) @ ob.rotation_euler.to_matrix().to_4x4() @ Matrix.Diagonal((ob.scale[0], ob.scale[1], ob.scale[2], 1.0))

def _world(ob):
    m = _basis(ob)
    return (_world(ob.parent) @ m) if ob.parent is not None else m

def _solve_arm(side, C):
    """Stretchy two-bone IK from the shoulder the torso carries to the glove centre `C`: the wrist
    sits GLOVE_IN inside the glove, the elbow bends toward `hint` (outward and down, in the sprite
    plane), and past the arm's reach both bones stretch up to STRETCH_MAX — the rubber-hose arm a
    straight punch at the camera needs."""
    sx = -1.0 if side == "L" else 1.0
    S = _world(_J[f"sh_{side}"]).translation.copy()
    v = C - S
    if v.length < 1e-5:
        v = Vector((sx * 0.01, 0.0, -0.01))
    u = v.normalized()
    T = C - u * GLOVE_IN
    w = T - S
    d = w.length
    reach = UARM_LEN + FARM_LEN
    stretch = 1.0
    if d > reach * 0.995:
        stretch = min(STRETCH_MAX, d / (reach * 0.995))
    a = UARM_LEN * stretch; b = FARM_LEN * stretch
    d = max(min(d, (a + b) * 0.995), abs(a - b) + 1e-3)
    uw = w.normalized() if w.length > 1e-6 else u
    cos_a = (a * a + d * d - b * b) / (2.0 * a * d)
    ang = math.acos(max(-1.0, min(1.0, cos_a)))
    hint = Vector((sx * 0.8, 0.0, -0.6))
    n = hint - uw * hint.dot(uw)
    if n.length < 1e-4:
        n = Vector((0.0, -1.0, 0.0)) - uw * (-uw.y)
    n.normalize()
    E = S + uw * (a * math.cos(ang)) + n * (a * math.sin(ang))
    ua, fa = _ARMS[side]
    ua.location = S
    ua.rotation_quaternion = Vector((0.0, 0.0, -1.0)).rotation_difference((E - S).normalized())
    ua.scale = (1.0, 1.0, stretch)
    fa.location = E
    fa.rotation_quaternion = Vector((0.0, 0.0, -1.0)).rotation_difference((T - E).normalized())
    fa.scale = (1.0, 1.0, stretch)

_BOOL_SLOTS = ("sweat", "spirals", "bowl", "bowr", "pupils")

def _apply(pose):
    def g(j, c):
        return pose.get((j, c), _default(j, c))
    for name, ob in _J.items():
        rl = REST_LOC[name]
        ob.location = (rl[0] + g(name, "dx"), rl[1] + g(name, "dy"), rl[2] + g(name, "dz"))
        ob.rotation_euler = (math.radians(g(name, "rx")), math.radians(g(name, "ry")), 0.0)
        ob.scale = (g(name, "sx"), g(name, "sy"), g(name, "sz"))
    for side, gob in _GLOVES.items():
        j = f"glove_{side}"
        gob.location = (g(j, "x"), g(j, "y"), g(j, "z"))
        gob.rotation_euler = (0.0, math.radians(g(j, "ry")), 0.0)
        s = g(j, "s"); gob.scale = (s, s, s)
    for slot, table in _VARIANTS.items():
        want = pose.get(slot, CATEGORICAL[slot])
        if slot in _BOOL_SLOTS:
            want = "on" if want else "off"
        for vname, obs in table.items():
            for ob in obs:
                ob.hide_render = (vname != want)
    for side, gob in _GLOVES.items():
        _solve_arm(side, Vector(gob.location))

def _constant(ob):
    """Every key CONSTANT — Blender 5.x: the slotted action API (`Action.fcurves` is gone), with the
    legacy accessor as the fallback for an older Blender."""
    ad = ob.animation_data
    if ad is None or ad.action is None:
        return
    act = ad.action
    try:
        for layer in act.layers:
            for st in layer.strips:
                for slot in act.slots:
                    cb = st.channelbag(slot)
                    if cb is None:
                        continue
                    for fc in cb.fcurves:
                        for kp in fc.keyframe_points:
                            kp.interpolation = "CONSTANT"
    except AttributeError:
        for fc in act.fcurves:
            for kp in fc.keyframe_points:
                kp.interpolation = "CONSTANT"

def _engine(v):
    """Blender (x, y, z) -> engine (x, z, -y): what the exporter writes."""
    return (round(v.x, 4), round(v.z, 4), round(-v.y, 4))

# what the exporter must find in the file, frame by frame — the marker check in export_strokes.py
EXPECT_MARKERS = {}

def bake():
    scene = bpy.context.scene
    scene.render.fps = FPS
    scene.frame_start = 0
    scene.frame_end = len(FRAMES) - 1
    for f, pose in enumerate(FRAMES):
        _apply(pose)
        for ob in _KEYED:
            ob.keyframe_insert("location", frame=f)
            ob.keyframe_insert("rotation_quaternion" if ob.rotation_mode == "QUATERNION" else "rotation_euler", frame=f)
            ob.keyframe_insert("scale", frame=f)
        for ob in _VIS_KEYED:
            ob.keyframe_insert("hide_render", frame=f)
        EXPECT_MARKERS[f] = {"glove_L": _engine(Vector(_GLOVES["L"].location)), "glove_R": _engine(Vector(_GLOVES["R"].location)),
                             "chin": _engine(_world(_J["head"]).translation), "crown": _engine(_world(_J["head"]) @ Vector((0.0, 0.0, 0.92)))}
    for ob in _KEYED + _VIS_KEYED:
        _constant(ob)
    _apply(FRAMES[0])
    scene.frame_set(0)
    # the strip table on the scene, for blender/render_strips.py (the exporter takes STRIPS from the namespace)
    scene["strips"] = {k: {"first": v["first"], "count": v["count"], "loop": v["loop"]} for k, v in STRIPS.items()}

bake()
print("boxer.py: %d frames in %d strips, %d transform objects, %d variant objects" % (len(FRAMES), len(STRIPS), len(_KEYED), len(_VIS_KEYED)))
