"""THE REFEREE (DESIGN.md §6) — a man in a white shirt and a bow tie, not a stick.

MODEL SPACE: feet at the origin, the figure in Blender's X–Z plane (engine X–Y) facing the
viewer, 1.75 m tall. The renderer places him at the far-left post between rounds, slides him to
the centre over 0.6 s on a knockdown, and drives the COUNT by posing `arm_R`: the part's pivot is
the RIGHT SHOULDER (0.20, 1.42), so a `Pose.roll` on that one part swings the whole arm — upper,
forearm, fist, elbow bend and all — without a strip. Two angles alternating per numeral; the
numerals themselves are the plate's. That is why the arms are separate parts and why `arm_R`
carries a pivot and the rest do not.

WHY HE GREW A BODY. The first draft was nine strokes — a diamond head, one line for a torso, one
per limb — on the argument that at 5 m nothing more would read and that every stroke beyond nine
is light stolen from the boxer's tell. Both halves of that were wrong. He is the only OTHER
PERSON in the room; a stick figure beside a fully drawn caricature does not read as economy, it
reads as a placeholder, and the eye goes to it precisely BECAUSE it is wrong. Eighty-odd strokes
of shirt, bow tie, trousers and shoes cost about a fifth of a millisecond and buy the one thing
the ring was missing: somebody official standing in it. He still never competes with the boxer
for attention, because the way he stays out of the way is TONE, not poverty — see below.

THE PALETTE IS HIS DISTANCE. Every part is authored near-white and the renderer draws him at the
colour the part carries (0.4 at the title, 0.55 in a round, 0.95 during a count). The shirt and
the head are white, the bow tie a hair cooler, and the TROUSERS AND SHOES are a dim slate — so
the figure reads top-lit, the shirt carries the silhouette, and the whole man sits a stop under
the fighter's saturated magenta and red without ever being a different hue. Nothing on him
flashes, and that is the point: in a game whose entire language is "the bright thing is the thing
that matters", the referee is the one man who never gets brighter.

Read the numbers as: crown 1.75, chin 1.50, shoulders 1.44, waist 1.02, cuffs 0.09, +X is his
right (the arm that counts).
"""
ASSET_NAME = "referee"

WHITE = (1.00, 1.00, 1.00)
SKIN = (0.95, 0.98, 1.00)
TIE = (0.72, 0.88, 1.00)
CLOTH = (0.45, 0.55, 0.68)
SHOE = (0.34, 0.42, 0.55)

SHOULDER_Y = 1.42
SHOULDER_X = 0.20

def _obj(name, pts3, edges, part, pivot=(0.0, 0.0, 0.0), color=WHITE):
    me = bpy.data.meshes.new(name); me.from_pydata(pts3, edges, []); me.update()
    ob = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(ob)
    ob["part"] = part; ob["color"] = color; ob["pivot"] = pivot
    return ob

def strokes(name, part, segs, pivot=(0.0, 0.0, 0.0), color=WHITE):
    """`segs` is a list of (x0, z0, x1, z1) in the y = 0 plane — the sprite is flat."""
    pts3, edges = [], []
    for s in segs:
        k = len(pts3)
        pts3 += [(s[0], 0.0, s[1]), (s[2], 0.0, s[3])]
        edges.append((k, k + 1))
    return _obj(name, pts3, edges, part, pivot, color)

def chain(pts, closed=False):
    """A polyline through (x, z) points as a list of segments."""
    out = [(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]) for i in range(len(pts) - 1)]
    if closed:
        out.append((pts[-1][0], pts[-1][1], pts[0][0], pts[0][1]))
    return out

def ellipse(cx, cz, rx, rz, n=14):
    import math as _m
    pts = [(cx + rx * _m.cos(2 * _m.pi * i / n), cz + rz * _m.sin(2 * _m.pi * i / n)) for i in range(n)]
    return chain(pts, closed=True)

def mirror(segs):
    return [(-s[0], s[1], -s[2], s[3]) for s in segs]

# ---------------------------------------------------------------- the head: a face, not a diamond
HEAD_C, HEAD_RX, HEAD_RZ = 1.625, 0.098, 0.125
head = ellipse(0.0, HEAD_C, HEAD_RX, HEAD_RZ, 14)
head += chain([(-0.086, 1.668), (-0.045, 1.706), (0.045, 1.706), (0.086, 1.668)])   # the hairline
head += [(-0.058, 1.646, -0.026, 1.646), (0.026, 1.646, 0.058, 1.646)]              # eyes
head += chain([(0.0, 1.638), (0.0, 1.600), (0.020, 1.598)])                          # the nose
head += [(-0.032, 1.560, 0.032, 1.560)]                                              # the mouth
head += [(-0.036, 1.500, -0.036, 1.452), (0.036, 1.500, 0.036, 1.452)]              # the neck
strokes("head", "head", head, color=SKIN)

# ---------------------------------------------------------------- the shirt
shirt = chain([(-SHOULDER_X, SHOULDER_Y), (-0.176, 1.20), (-0.156, 1.02),
               (0.156, 1.02), (0.176, 1.20), (SHOULDER_X, SHOULDER_Y)])
shirt += [(-SHOULDER_X, SHOULDER_Y, -0.050, 1.462), (0.050, 1.462, SHOULDER_X, SHOULDER_Y)]  # shoulders
shirt += chain([(-0.050, 1.462), (-0.018, 1.372), (0.018, 1.372), (0.050, 1.462)])            # the collar
shirt += [(-0.205, 1.272, -0.124, 1.244), (0.124, 1.244, 0.205, 1.272)]                       # short sleeves
shirt += [(0.0, 1.300, 0.0, 1.062)]                                                            # the placket
shirt += [(-0.160, 1.020, 0.160, 1.020), (-0.160, 0.994, 0.160, 0.994)]                       # the belt
strokes("shirt", "body", shirt, color=WHITE)

# the bow tie: the one thing on him that says referee and not waiter, so it is its own part
tie = chain([(0.0, 1.360), (-0.072, 1.392), (-0.072, 1.328)], closed=True)
tie += chain([(0.0, 1.360), (0.072, 1.392), (0.072, 1.328)], closed=True)
tie += chain([(-0.014, 1.344), (0.014, 1.344), (0.014, 1.376), (-0.014, 1.376)], closed=True)
strokes("tie", "tie", tie, color=TIE)

# ---------------------------------------------------------------- the arms, doubled for weight
def arm(sx):
    inner = chain([(sx * SHOULDER_X, SHOULDER_Y), (sx * 0.258, 1.196), (sx * 0.242, 0.972)])
    outer = chain([(sx * (SHOULDER_X + 0.030), SHOULDER_Y - 0.006), (sx * 0.288, 1.188), (sx * 0.272, 0.968)])
    fist = ellipse(sx * 0.258, 0.926, 0.042, 0.046, 8)
    return inner + outer + fist

strokes("arm_L", "arm_L", arm(-1), color=SKIN)
strokes("arm_R", "arm_R", arm(1), pivot=(SHOULDER_X, 0.0, SHOULDER_Y), color=SKIN)

# ---------------------------------------------------------------- trousers and shoes
legs = chain([(-0.156, 1.020), (-0.166, 0.550), (-0.160, 0.090), (-0.052, 0.090),
              (-0.036, 0.520), (0.036, 0.520), (0.052, 0.090), (0.160, 0.090),
              (0.166, 0.550), (0.156, 1.020)])
legs += [(-0.112, 0.950, -0.118, 0.130), (0.112, 0.950, 0.118, 0.130)]   # the creases
strokes("legs", "legs", legs, color=CLOTH)

shoe = chain([(-0.186, 0.090), (-0.036, 0.090), (-0.022, 0.0), (-0.202, 0.0)], closed=True)
strokes("shoes", "shoes", shoe + mirror(shoe), color=SHOE)
