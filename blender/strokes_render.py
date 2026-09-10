"""THE CUTSCENE LOOK, in Blender: every stroke asset becomes an emissive bevelled curve on black,
with a compositor glow — light on nothing, the same idiom the game draws live. Freestyle was the
first attempt and it draws faces; these assets have none.

Library, imported by the scene scripts:
    strokes_to_curves(objects, color, radius)  -> replaces edge meshes with glowing curves
    setup_scene(w, h)                          -> black world, eevee, glow compositor
    camera(loc, look_at, lens)
    render_frames(out_dir, start, end, fn(frame))
Run a test:  blender -b --python blender/strokes_render.py -- /tmp/test.png
"""
import bpy, math, os, sys
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))

def load_asset(name, offset=(0, 0, 0), rot_z=0.0, tag=None):
    """Build blender/assets/<name>.py into the scene, move it, return its objects."""
    path = os.path.join(HERE, "assets", name + ".py")
    before = set(bpy.data.objects)
    ns = {"bpy": bpy, "bmesh": __import__("bmesh"), "math": math, "Vector": Vector, "__file__": path}
    exec(compile(open(path).read(), path, "exec"), ns)
    new = [o for o in bpy.data.objects if o not in before]
    for o in new:
        o.location = (o.location.x + offset[0], o.location.y + offset[1], o.location.z + offset[2])
        o.rotation_euler.z += rot_z
        if tag: o["tag"] = tag
    # matrix_world is stale until the depsgraph runs; strokes_to_curves reads it, so run it now —
    # without this every placed asset converted at the origin, inside the camera, as giant slabs
    bpy.context.view_layer.update()
    return new

def emissive(name, color, strength):
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree
    for n in list(nt.nodes): nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial"); em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = (*color, 1); em.inputs["Strength"].default_value = strength
    nt.links.new(em.outputs[0], out.inputs[0])
    return m

def strokes_to_curves(objects, color=None, radius=0.012, strength=6.0):
    """Every edge of every mesh becomes a 2-point poly spline with a round bevel: a glowing wire."""
    made = []
    for ob in objects:
        if ob.type != "MESH": continue
        col = tuple(color) if color else tuple(ob.get("color", (0.35, 0.95, 1.0)))[:3]
        cu = bpy.data.curves.new(ob.name + "_cu", "CURVE"); cu.dimensions = "3D"
        cu.bevel_depth = radius; cu.bevel_resolution = 2; cu.use_fill_caps = True
        me = ob.data
        mw = ob.matrix_world
        for e in me.edges:
            a = mw @ me.vertices[e.vertices[0]].co; b = mw @ me.vertices[e.vertices[1]].co
            sp = cu.splines.new("POLY"); sp.points.add(1)
            sp.points[0].co = (a.x, a.y, a.z, 1); sp.points[1].co = (b.x, b.y, b.z, 1)
        co = bpy.data.objects.new(ob.name + "_wire", cu); bpy.context.collection.objects.link(co)
        co.data.materials.append(emissive(ob.name + "_em", col, strength))
        for k in ob.keys(): co[k] = ob[k]
        made.append(co)
        bpy.data.objects.remove(ob)
    return made

def setup_scene(w=640, h=480, glow=0.0):
    scene = bpy.context.scene
    scene.world = bpy.data.worlds.new("black"); scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs[0].default_value = (0, 0, 0, 1)
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = w; scene.render.resolution_y = h; scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"; scene.render.film_transparent = False
    scene.view_settings.view_transform = "Standard"
    # glow: compositor glare (bloom). Eevee's own bloom checkbox is gone in 4.2+, and in 5.0 the
    # compositor moved from scene.node_tree to a node GROUP on scene.compositing_node_group.
    if glow <= 0:
        # no compositor at all: crisp emissive wires; the halo is added in post by tools/glow.py
        scene.use_nodes = False
        return scene
    try:
        if hasattr(scene, "node_tree") and scene.node_tree is not None:
            scene.use_nodes = True
            nt = scene.node_tree
            for n in list(nt.nodes): nt.nodes.remove(n)
            rl = nt.nodes.new("CompositorNodeRLayers"); gl = nt.nodes.new("CompositorNodeGlare")
            out = nt.nodes.new("CompositorNodeComposite"); out_in = out.inputs[0]
        else:
            nt = bpy.data.node_groups.new("x3comp", "CompositorNodeTree")
            nt.interface.new_socket("Image", in_out="OUTPUT", socket_type="NodeSocketColor")
            rl = nt.nodes.new("CompositorNodeRLayers"); gl = nt.nodes.new("CompositorNodeGlare")
            out = nt.nodes.new("NodeGroupOutput"); out_in = out.inputs[0]
            scene.compositing_node_group = nt
            scene.use_nodes = True
        # LINK FIRST. A tree with an unlinked output composites to black, which is what an
        # exception between "create" and "link" left behind on the first attempt.
        nt.links.new(rl.outputs["Image"], gl.inputs[0]); nt.links.new(gl.outputs[0], out_in)
        for attempt in (lambda: setattr(gl, "glare_type", "BLOOM"), lambda: setattr(gl, "glare_type", "FOG_GLOW")):
            try: attempt(); break
            except Exception: pass
        for inp in gl.inputs:
            try:
                if inp.name == "Strength": inp.default_value = glow
                if inp.name == "Threshold": inp.default_value = 1.0
                if inp.name == "Size": inp.default_value = 7
            except Exception: pass
        for attr, val in (("quality", "HIGH"), ("mix", 0.0), ("threshold", 1.0), ("size", 6)):
            if hasattr(gl, attr):
                try: setattr(gl, attr, val)
                except Exception: pass
        print("glow compositor: ok (" + getattr(gl, "glare_type", "?") + ")")
    except Exception as ex:
        print("glow compositor: skipped —", ex)
        scene.use_nodes = False
        if hasattr(scene, "compositing_node_group"): scene.compositing_node_group = None
    return scene

def camera(loc, look_at, lens=24):
    cd = bpy.data.cameras.new("cam"); cd.lens = lens
    cam = bpy.data.objects.new("cam", cd); bpy.context.collection.objects.link(cam)
    cam.location = loc
    d = Vector(look_at) - Vector(loc)
    cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    bpy.context.scene.camera = cam
    return cam

def render_still(path):
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    return os.path.getsize(path)

def render_frames(out_dir, start, end, step_fn):
    os.makedirs(out_dir, exist_ok=True)
    for f in range(start, end + 1):
        step_fn(f)
        render_still(os.path.join(out_dir, f"f{f:04d}.png"))

if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else ["/tmp/x3knockout_strokes_test.png"]
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = setup_scene()
    strokes_to_curves(load_asset("arena"), radius=0.012, strength=1.4)
    strokes_to_curves(load_asset("program", offset=(0, -4, 0), rot_z=math.pi), color=(1.0, 0.55, 0.12), radius=0.014, strength=2.6)
    strokes_to_curves(load_asset("recognizer", offset=(3.5, -7, 3.0), rot_z=0.4), color=(1.0, 0.45, 0.1), radius=0.02, strength=2.2)
    strokes_to_curves(load_asset("disc", offset=(-0.6, -1.5, 1.4)), color=(0.9, 1, 1), radius=0.008, strength=4.0)
    camera((0, 0, 1.65), (0, -6, 1.2), lens=22)
    n = render_still(argv[0])
    print("rendered", argv[0], n)
