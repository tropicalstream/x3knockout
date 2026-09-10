"""Cutscene look test: build the arena + a program in bpy, render ONE frame with Freestyle line art
over black at 640x480 (one eye), Eevee, with a bloom-ish glow via the compositor.

    blender -b --python blender/render_test.py -- out.png
"""
import bpy, sys, os, math

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else ["/tmp/x3knockout_render_test.png"]
out = argv[0]

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
HERE = os.path.dirname(os.path.abspath(__file__))

def run_asset(path):
    ns = {"bpy": bpy, "bmesh": __import__("bmesh"), "math": math, "Vector": __import__("mathutils").Vector, "__file__": path}
    exec(compile(open(path).read(), path, "exec"), ns)

run_asset(os.path.join(HERE, "assets", "arena.py"))
# a program standing at (0, -4) facing the camera; the asset builds at the origin facing -Y
before = set(bpy.data.objects)
run_asset(os.path.join(HERE, "assets", "program.py"))
for ob in set(bpy.data.objects) - before:
    ob.location.y += -4.0
    ob.rotation_euler.z = math.pi

# mesh edge-only objects render nothing in Eevee; Freestyle draws their EDGES when marked
for ob in bpy.data.objects:
    if ob.type == "MESH":
        me = ob.data
        # mark every edge as freestyle so wire-only meshes still draw
        attr = me.attributes.get("freestyle_edge") or me.attributes.new("freestyle_edge", "BOOLEAN", "EDGE")
        for v in attr.data: v.value = True

# camera at the player's eye, looking at the program
cam_data = bpy.data.cameras.new("cam"); cam_data.lens = 24
cam = bpy.data.objects.new("cam", cam_data); bpy.context.collection.objects.link(cam)
cam.location = (0, 0, 1.65); cam.rotation_euler = (math.radians(88), 0, 0)
scene.camera = cam

# world: black
scene.world = bpy.data.worlds.new("w"); scene.world.use_nodes = True
bg = scene.world.node_tree.nodes["Background"]; bg.inputs[0].default_value = (0, 0, 0, 1)

# render settings
scene.render.engine = "BLENDER_EEVEE_NEXT" if hasattr(bpy.types, "SceneEEVEE") and "BLENDER_EEVEE_NEXT" in [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items] else "BLENDER_EEVEE"
scene.render.resolution_x = 640; scene.render.resolution_y = 480; scene.render.resolution_percentage = 100
scene.render.film_transparent = False
scene.render.use_freestyle = True
scene.render.line_thickness = 1.6
fs = scene.view_layers[0].freestyle_settings
fs.use_culling = False
ls = fs.linesets.new("strokes") if not fs.linesets else fs.linesets[0]
ls.select_silhouette = True; ls.select_border = True; ls.select_crease = True; ls.select_edge_mark = True
ls.select_contour = False
# Blender 5.x: a new lineset has no linestyle until one is assigned
lstyle = ls.linestyle or bpy.data.linestyles.new("cyan")
ls.linestyle = lstyle
lstyle.color = (0.35, 0.95, 1.0); lstyle.thickness = 1.6
scene.render.image_settings.file_format = "PNG"
scene.render.filepath = out
print("engine", scene.render.engine)
bpy.ops.render.render(write_still=True)
print("rendered", out, os.path.getsize(out))
