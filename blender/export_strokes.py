#!/usr/bin/env python3
"""Blender → x3knockout stroke exporter.

Run headless:  blender -b --python blender/export_strokes.py -- <asset.py> <out.json>

The game draws nothing but additive line strokes, so a "model" is a list of edges. This
turns whatever the asset script built in bpy into that list, grouped by named part so the
engine can animate parts independently (a disc's ring vs. its core, a figure's limbs).

FORMAT (JSON, metres, Y up, right-handed, +Z toward the viewer at rest):
{
  "name": "disc",
  "parts": [
    { "name": "ring", "color": [r, g, b], "segs": [[x0,y0,z0, x1,y1,z1], ...],
      "pivot": [px, py, pz] },
    ...
  ]
}
A part's edges are in MODEL space; "pivot" is the point the engine rotates the part about
(a limb's joint). Colour is a hint — the engine tints per level and may override.

WHICH EDGES: by default every mesh edge. Mark an object with obj["sharp_only"]=True to
export only edges flagged sharp or with a face angle > 30°, which is how a dense surface
(a torus, a cone) becomes the few strokes that read as its silhouette. Objects whose name
starts with "_" are helpers and are not exported.

Blender 5.x notes: mesh data is read through obj.evaluated_get(depsgraph) so modifiers
(screw, array, mirror) are baked; edge sharpness lives in the "sharp_edge" attribute in
4.x/5.x rather than edge.use_edge_sharp.
"""
import bpy, bmesh, json, math, sys, os
from mathutils import Vector

def _sharp_set(me):
    attr = me.attributes.get("sharp_edge")
    if attr is None:
        return set()
    return {i for i, v in enumerate(attr.data) if v.value}

def _face_angle_sharp(bm, thresh_deg):
    out = set()
    cos_t = math.cos(math.radians(thresh_deg))
    for e in bm.edges:
        if len(e.link_faces) == 2:
            n0, n1 = e.link_faces[0].normal, e.link_faces[1].normal
            if n0.length and n1.length and n0.dot(n1) < cos_t:
                out.add(e.index)
        elif len(e.link_faces) <= 1:
            out.add(e.index)   # boundary edges always read as silhouette
    return out

def export(objects, name, out_path, sharp_deg=30.0):
    dg = bpy.context.evaluated_depsgraph_get()
    parts = []
    for obj in objects:
        if obj.type != "MESH" or obj.name.startswith("_"):
            continue
        ev = obj.evaluated_get(dg)
        me = ev.to_mesh()
        bm = bmesh.new(); bm.from_mesh(me); bm.edges.ensure_lookup_table(); bm.normal_update()
        keep = None
        if obj.get("sharp_only"):
            keep = _sharp_set(me) | _face_angle_sharp(bm, sharp_deg)
        mw = obj.matrix_world
        segs = []
        for e in bm.edges:
            if keep is not None and e.index not in keep:
                continue
            a = mw @ e.verts[0].co; b = mw @ e.verts[1].co
            # Blender is Z-up; the engine is Y-up with +Z toward the viewer: (x, y, z) -> (x, z, -y)
            segs.append([round(a.x, 4), round(a.z, 4), round(-a.y, 4), round(b.x, 4), round(b.z, 4), round(-b.y, 4)])
        pv = mw @ Vector(obj.get("pivot", (0, 0, 0)))
        col = list(obj.get("color", (1.0, 1.0, 1.0)))
        parts.append({"name": obj.get("part", obj.name), "color": [float(c) for c in col[:3]],
                      "pivot": [round(pv.x, 4), round(pv.z, 4), round(-pv.y, 4)], "segs": segs})
        bm.free(); ev.to_mesh_clear()
    doc = {"name": name, "parts": parts}
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    n = sum(len(p["segs"]) for p in parts)
    print(f"exported {name}: {len(parts)} parts, {n} segments -> {out_path}")
    return doc

if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if len(argv) < 2:
        print(__doc__); sys.exit(2)
    asset_py, out_path = argv[0], argv[1]
    # a clean scene, then the asset script builds into it and names the asset
    bpy.ops.wm.read_factory_settings(use_empty=True)
    ns = {"bpy": bpy, "bmesh": bmesh, "math": math, "Vector": Vector, "__file__": asset_py}
    with open(asset_py) as f:
        code = compile(f.read(), asset_py, "exec")
    exec(code, ns)
    name = ns.get("ASSET_NAME", os.path.splitext(os.path.basename(asset_py))[0])
    export(list(bpy.data.objects), name, out_path, ns.get("SHARP_DEG", 30.0))
