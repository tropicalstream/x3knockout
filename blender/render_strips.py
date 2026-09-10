"""THE BLENDER-RENDERED STRIP PREVIEW — the contact sheet per strip from the fight camera, through
Eevee, with every stroke a glowing tube (DESIGN.md §12.6; the engine-exact one is
`tools/strip_sheet.py`, which draws the EXPORTED file the way the glass does; this one draws the
SCENE, before export, the way `strokes_render.py` draws cutscenes — the same idiom, a different
witness, and the only one that shows the rig itself).

    blender -b -noaudio --factory-startup --python-exit-code 3 --python blender/render_strips.py -- \\
        [--out DIR] [--every N] [--radius R] [strip ...]

With no strip names every strip is rendered; `--every N` takes every Nth frame of each (the
sheet is for reading poses, not for timing). Frames land in DIR as `<strip>_f<local>.png` and each
strip's frames are tiled into `sheet_<strip>.png` (8 across) with Blender's own image buffers —
Blender's bundled Python has no PIL, and a preview that needs a second interpreter is one nobody
runs. `tools/glow.py` adds the halo in post if the crop is to be compared with a screencap.

WHY TUBES THROUGH GEOMETRY NODES AND NOT `strokes_to_curves`: that helper bakes the current
frame's world matrix into a curve and deletes the mesh, so the keyframes and the `hide_render`
variants — the whole point of a strip — are lost. A Geometry Nodes modifier (Mesh to Curve → Curve
to Mesh with a circle profile → Set Material) turns the same edges into tubes at EVERY frame, on
the animated object, and honours `hide_render` because the object is still the object.

THE CAMERA is the engine's: the eye at (0, 2.6, 1.65) in Blender's frame (the figure faces +Y, so
the camera stands on +Y and looks along −Y, level — no pitch), a 62° vertical field on a 4:3 frame,
which on Blender's 36 mm sensor (sensor fit AUTO = width) is a 22.5 mm lens. What you read here is
what `strip_sheet.py` draws from the file, minus the additive clamp.
"""
import bpy, math, os, sys
import numpy as np
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import strokes_render as SR

W, H = 640, 480

def wire_modifier(ob, radius, strength):
    """Every edge of `ob` becomes a tube of `radius`, emissive in the object's colour, at render time."""
    col = tuple(ob.get("color", (0.35, 0.95, 1.0)))[:3]
    mat = SR.emissive(ob.name + "_em", col, strength)
    ng = bpy.data.node_groups.new(ob.name + "_wire", "GeometryNodeTree")
    ng.interface.new_socket("Geometry", in_out="INPUT", socket_type="NodeSocketGeometry")
    ng.interface.new_socket("Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry")
    n_in = ng.nodes.new("NodeGroupInput"); n_out = ng.nodes.new("NodeGroupOutput")
    m2c = ng.nodes.new("GeometryNodeMeshToCurve")
    circ = ng.nodes.new("GeometryNodeCurvePrimitiveCircle")
    circ.inputs["Radius"].default_value = radius
    circ.inputs["Resolution"].default_value = 6
    c2m = ng.nodes.new("GeometryNodeCurveToMesh")
    setm = ng.nodes.new("GeometryNodeSetMaterial")
    setm.inputs["Material"].default_value = mat
    ng.links.new(n_in.outputs[0], m2c.inputs["Mesh"])
    ng.links.new(m2c.outputs["Curve"], c2m.inputs["Curve"])
    ng.links.new(circ.outputs["Curve"], c2m.inputs["Profile Curve"])
    ng.links.new(c2m.outputs["Mesh"], setm.inputs["Geometry"])
    ng.links.new(setm.outputs["Geometry"], n_out.inputs[0])
    mod = ob.modifiers.new("wire", "NODES")
    mod.node_group = ng
    return mod

def tile(paths, out_path, cols=8):
    """Tile rendered frames into one PNG with bpy's image buffers (float RGBA, bottom-up rows)."""
    imgs = []
    for p in paths:
        im = bpy.data.images.load(p)
        w, h = im.size
        px = np.array(im.pixels[:], dtype=np.float32).reshape(h, w, 4)
        imgs.append(px)
        bpy.data.images.remove(im)
    if not imgs:
        return
    h, w = imgs[0].shape[:2]
    rows = (len(imgs) + cols - 1) // cols
    sheet = np.zeros((rows * h, cols * w, 4), dtype=np.float32)
    sheet[:, :, 3] = 1.0
    for i, px in enumerate(imgs):
        r, c = divmod(i, cols)
        r = rows - 1 - r                      # bpy pixel rows run bottom-up: the first frame goes top-left
        sheet[r * h:(r + 1) * h, c * w:(c + 1) * w, :] = px
    out = bpy.data.images.new("sheet", cols * w, rows * h, alpha=True, float_buffer=False)
    out.pixels = sheet.ravel().tolist()
    out.filepath_raw = out_path
    out.file_format = "PNG"
    out.save()
    bpy.data.images.remove(out)

def main(argv):
    out_dir = os.path.join("/tmp", "x3knockout_strips")
    every = 1
    radius = 0.006
    names = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--out":
            out_dir = argv[i + 1]; i += 2
        elif a == "--every":
            every = max(1, int(argv[i + 1])); i += 2
        elif a == "--radius":
            radius = float(argv[i + 1]); i += 2
        else:
            names.append(a); i += 1
    os.makedirs(out_dir, exist_ok=True)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = SR.setup_scene(W, H, glow=0.0)
    SR.load_asset("boxer")
    for ob in list(bpy.data.objects):
        if ob.type == "MESH" and not ob.name.startswith("_"):
            cls = str(ob.get("cls", "outline"))
            wire_modifier(ob, radius * (1.0 if cls != "hatch" else 0.6), {"outline": 3.0, "detail": 2.2, "hatch": 0.9}.get(cls, 2.0))
    strips = _strips_from_scene()
    if not strips:
        print("render_strips: no strips found on the scene"); sys.exit(3)
    cam = SR.camera((0.0, 2.6, 1.65), (0.0, 0.0, 1.65), lens=22.5)
    cam.data.sensor_fit = "AUTO"
    if not names:
        names = list(strips)
    for n in names:
        if n not in strips:
            print(f"render_strips: no strip {n}; have {list(strips)}"); sys.exit(3)
        first, count = strips[n]
        paths = []
        for f in range(0, count, every):
            scene.frame_set(first + f)
            p = os.path.join(out_dir, f"{n}_f{f:02d}.png")
            SR.render_still(p)
            paths.append(p)
        sheet = os.path.join(out_dir, f"sheet_{n}.png")
        tile(paths, sheet)
        print(f"rendered {n}: {len(paths)} frames -> {sheet}")

def _strips_from_scene():
    """The strips as the asset recorded them on the scene (boxer.py writes `scene["strips"]`)."""
    raw = bpy.context.scene.get("strips")
    if not raw:
        return {}
    out = {}
    for k, v in raw.items():
        out[k] = (int(v["first"]), int(v["count"]))
    return out

if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    main(argv)
