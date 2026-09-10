"""THE REFEREE (DESIGN.md §6) — nine white strokes: a head of four, a body, two arms, two legs.

MODEL SPACE: feet at the origin, the figure in Blender's X–Z plane (engine X–Y) facing the
viewer, 1.75 m tall. The renderer places him at the far-left post between rounds and slides him
to the centre over 0.6 s on a knockdown, and it drives the COUNT by posing `arm_R`: the part's
pivot is the right shoulder, so a `Pose.roll` on that one part swings the arm up and down without
a strip — two angles, alternating per numeral (the numerals themselves are the plate's). That is
why the arms are separate parts and why `arm_R` carries a pivot and the rest do not.

Nine strokes because at 5 m a stroke figure needs no more to read as a man in a white shirt, and
because everything beyond nine is light stolen from the boxer's tell.
"""
ASSET_NAME = "referee"
WHITE = (0.92, 1.00, 1.00)

SHOULDER = 1.40
HEAD_C = 1.60
HEAD_R = 0.15

def _obj(name, pts3, edges, part, pivot=(0.0, 0.0, 0.0), color=WHITE):
    me = bpy.data.meshes.new(name); me.from_pydata(pts3, edges, []); me.update()
    ob = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(ob)
    ob["part"] = part; ob["color"] = color; ob["pivot"] = pivot
    return ob

def strokes(name, part, segs, pivot=(0.0, 0.0, 0.0)):
    pts3, edges = [], []
    for s in segs:
        k = len(pts3)
        pts3 += [(s[0], 0.0, s[1]), (s[2], 0.0, s[3])]
        edges.append((k, k + 1))
    return _obj(name, pts3, edges, part, pivot)

# the head: a diamond of four strokes (a circle would be twelve)
strokes("head", "head", [
    (0.0, HEAD_C + HEAD_R, HEAD_R, HEAD_C), (HEAD_R, HEAD_C, 0.0, HEAD_C - HEAD_R),
    (0.0, HEAD_C - HEAD_R, -HEAD_R, HEAD_C), (-HEAD_R, HEAD_C, 0.0, HEAD_C + HEAD_R),
])
# the body: one stroke from the hips to the neck
strokes("body", "body", [(0.0, 0.85, 0.0, SHOULDER + 0.03)])
# the arms: down at rest; arm_R pivots at the shoulder for the count
strokes("arm_L", "arm_L", [(0.0, SHOULDER, -0.34, 1.02)], pivot=(0.0, 0.0, SHOULDER))
strokes("arm_R", "arm_R", [(0.0, SHOULDER, 0.34, 1.02)], pivot=(0.0, 0.0, SHOULDER))
# the legs
strokes("legs", "legs", [(0.0, 0.85, -0.14, 0.0), (0.0, 0.85, 0.14, 0.0)])
