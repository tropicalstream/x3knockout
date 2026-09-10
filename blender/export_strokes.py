#!/usr/bin/env python3
"""Blender → x3knockout stroke exporter.

Run headless:  blender -b -noaudio --factory-startup --python-exit-code 3 --python blender/export_strokes.py -- <asset.py> <out.json>

An asset that defines STRIPS (a dict of strip name -> {first, count, loop, events}) is a SPRITE and
goes through export_strips() below, which also writes <out>.x3s beside the JSON and then READS IT
BACK to prove the layout (and, when the asset also defines EXPECT_MARKERS, that every marker landed
where the pose put it); anything else is a plain model through export().

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
4.x/5.x rather than edge.use_edge_sharp; `Action.fcurves` is gone, so animation is only ever
SAMPLED here (`scene.frame_set`), never read off curves.
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

def _eng(v):
    """Blender (x, y, z) -> engine (x, z, -y): Y up, +Z toward the viewer, the figure's +Y front on -Z."""
    return (v.x, v.z, -v.y)

def export_strips(objects, name, out_path, strips, fps=12, markers=None, parts_order=None, max_segs=700, fwd="-z", expect_markers=None):
    """THE SPRITE EXPORT (DESIGN.md §12.6, engine/Poses.kt's `StripSet`).

    Samples every frame of every strip through the evaluated depsgraph (`scene.frame_set(f)` —
    `Action.fcurves` is gone in Blender 5.x) and writes two files beside each other:

      <out>.json  the MANIFEST: parts (name, cls, flash, pivot, color — in `parts_order`, then
                  first appearance), markers, strips (name, first, count, loop, events), fps, fwd
      <out>.x3s   the FRAMES, little-endian: "X3S1", u32 nParts nMarkers nFrames, then per frame
                  u32 segCount[nParts], f32 x 6 x sum(segCount) in part order, f32 x 3 x nMarkers

    A part is `obj["part"]` (default the object name); `obj["cls"]` is outline | detail | hatch;
    `obj["flash"]` marks the parts the telegraph may whiten. Mouths, sweat and spirals are variants
    keyed on `hide_render` — a hidden object contributes no segments that frame, so several
    objects may share one part and only one is ever drawn (the check is on BOTH the original and
    the evaluated object: Blender writes animated values back to originals on `frame_set`, but a
    property that is not animated only exists on the original). Markers are EMPTY objects named
    `m_<name>` with `obj["marker"] = True`, sampled per frame through their evaluated world matrix,
    so a marker parented to a moving glove follows it. Objects whose name starts with "_" are
    helpers (the rig's joints). A frame over `max_segs` exits 3: the engine refuses it too.

    The forward axis: the figure is authored facing Blender +Y, which lands on engine -Z (the same
    map `export()` uses: (x, y, z) -> (x, z, -y)); `fwd` records that in the manifest so the
    engine's billboard heading is never a guess.

    THE READ-BACK. Writing a binary that a Kotlin reader parses is exactly the kind of thing that
    drifts silently (a count in the wrong place is still a file of the right size), so after
    writing, the .x3s is parsed again here by the layout `StripSet.parse` uses and compared with
    what was sampled — and when the asset hands over `expect_markers` (`{frame: {marker: (x, y, z)
    in ENGINE space}}`, boxer.py records the pose's own glove and head positions), every marker is
    checked against the pose that placed it. A mismatch exits 3 like an over-budget frame: a
    sprite that lies about where its glove is would pass every other test and then punch the
    telegraph arc from the wrong place on the glass.
    """
    import struct
    scene = bpy.context.scene
    scene.render.fps = fps
    meshes = [o for o in objects if o.type == "MESH" and not o.name.startswith("_")]
    empties = [o for o in objects if o.type == "EMPTY" and o.get("marker")]
    names = list(parts_order or [])
    for o in meshes:
        pn = o.get("part", o.name)
        if pn not in names:
            names.append(pn)
    # the part table is read off the REST pose (frame 0): the pivots are where the joints are at rest
    scene.frame_set(0)
    dg = bpy.context.evaluated_depsgraph_get()
    info = {}
    for o in meshes:
        pn = o.get("part", o.name)
        if pn not in info:
            pv = o.evaluated_get(dg).matrix_world @ Vector(o.get("pivot", (0, 0, 0)))
            col = list(o.get("color", (1.0, 1.0, 1.0)))
            info[pn] = {"name": pn, "cls": str(o.get("cls", "outline")), "flash": bool(o.get("flash", False)),
                        "pivot": [round(pv.x, 4), round(pv.z, 4), round(-pv.y, 4)], "color": [float(c) for c in col[:3]]}
        else:
            # the manifest has ONE row per part, read off the part's first object. A second object may
            # carry a different `cls` on purpose (a glove's laces are `detail` strokes on an `outline`
            # part: 32 slots, BOXER.md §8) and it simply inherits the part's — but a different COLOUR
            # would be silently repainted, so that is refused.
            col = o.get("color")
            if col is not None and [float(c) for c in list(col)[:3]] != info[pn]["color"]:
                print(f"export_strips: object {o.name} is {list(col)[:3]} but part {pn} is {info[pn]['color']}")
                sys.exit(3)
    def mname(o):
        return o.name[2:] if o.name.startswith("m_") else o.name
    marker_names = list(markers) if markers else [mname(o) for o in empties]
    marker_objs = {mname(o): o for o in empties}
    for mn in marker_names:
        if mn not in marker_objs:
            print(f"export_strips: marker {mn} has no m_{mn} empty in the scene")
            sys.exit(3)
    frames_total = max(int(v["first"]) + int(v["count"]) for v in strips.values()) if strips else 1
    for k, v in strips.items():
        for ek, ef in dict(v.get("events", {})).items():
            if not 0 <= int(ef) < int(v["count"]):
                print(f"export_strips: strip {k} event {ek} at local frame {ef} is outside 0..{int(v['count']) - 1}")
                sys.exit(3)
    x3s_path = os.path.splitext(out_path)[0] + ".x3s"
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    per_frame = []            # segments per frame
    sampled_markers = []      # per frame: [(x, y, z) engine] per marker, for the read-back
    with open(x3s_path, "wb") as f:
        f.write(b"X3S1")
        f.write(struct.pack("<III", len(names), len(marker_names), frames_total))
        for fr in range(frames_total):
            scene.frame_set(fr)
            dg = bpy.context.evaluated_depsgraph_get()
            per = {pn: [] for pn in names}
            for o in meshes:
                ev = o.evaluated_get(dg)
                if o.hide_render or ev.hide_render:
                    continue
                me = ev.to_mesh()
                mw = ev.matrix_world
                keep = None
                if o.get("sharp_only"):
                    bm = bmesh.new(); bm.from_mesh(me); bm.edges.ensure_lookup_table(); bm.normal_update()
                    keep = _sharp_set(me) | _face_angle_sharp(bm, 30.0)
                    bm.free()
                segs = per[o.get("part", o.name)]
                for e in me.edges:
                    if keep is not None and e.index not in keep:
                        continue
                    a = mw @ me.vertices[e.vertices[0]].co; b = mw @ me.vertices[e.vertices[1]].co
                    segs.append((a.x, a.z, -a.y, b.x, b.z, -b.y))
                ev.to_mesh_clear()
            total = sum(len(v) for v in per.values())
            per_frame.append(total)
            if total > max_segs:
                print(f"export_strips: frame {fr} has {total} segments, cap {max_segs}")
                sys.exit(3)
            f.write(struct.pack("<%dI" % len(names), *[len(per[pn]) for pn in names]))
            for pn in names:
                for sg in per[pn]:
                    f.write(struct.pack("<6f", *sg))
            row = []
            for mn in marker_names:
                p = _eng(marker_objs[mn].evaluated_get(dg).matrix_world.translation)
                f.write(struct.pack("<3f", *p))
                row.append(p)
            sampled_markers.append(row)
    doc = {"name": name, "fps": fps, "fwd": fwd,
           "parts": [info[pn] for pn in names], "markers": marker_names,
           "strips": [{"name": k, "first": int(v["first"]), "count": int(v["count"]), "loop": bool(v.get("loop", False)),
                       "events": {ek: int(ev) for ek, ev in dict(v.get("events", {})).items()}} for k, v in strips.items()],
           "frames": frames_total, "segs": os.path.basename(x3s_path)}
    with open(out_path, "w") as fo:
        json.dump(doc, fo, separators=(",", ":"))
    for k, v in strips.items():
        first, count = int(v["first"]), int(v["count"])
        counts = per_frame[first:first + count]
        print(f"  strip {k}: frames {first}..{first + count - 1}, segments/frame {min(counts)}..{max(counts)}")
    print(f"exported strips {name}: {len(names)} parts, {len(marker_names)} markers, {frames_total} frames, busiest frame {max(per_frame)} segs -> {out_path} + {os.path.basename(x3s_path)} ({os.path.getsize(x3s_path)} bytes)")
    _verify_x3s(x3s_path, len(names), marker_names, per_frame, sampled_markers, expect_markers)
    return doc

def _verify_x3s(x3s_path, n_parts, marker_names, per_frame, sampled_markers, expect_markers):
    """Parse the written file with the engine's layout and compare it with what was sampled."""
    import struct
    data = open(x3s_path, "rb").read()
    if data[:4] != b"X3S1":
        print("verify: bad magic"); sys.exit(3)
    np_, nm, nf = struct.unpack_from("<III", data, 4)
    if (np_, nm, nf) != (n_parts, len(marker_names), len(per_frame)):
        print(f"verify: header {(np_, nm, nf)} != {(n_parts, len(marker_names), len(per_frame))}"); sys.exit(3)
    off = 16
    worst = 0.0; worst_at = None
    for fr in range(nf):
        counts = struct.unpack_from("<%dI" % np_, data, off); off += 4 * np_
        if sum(counts) != per_frame[fr]:
            print(f"verify: frame {fr} has {sum(counts)} segments in the file, {per_frame[fr]} sampled"); sys.exit(3)
        off += 24 * sum(counts)
        for mi, mn in enumerate(marker_names):
            p = struct.unpack_from("<3f", data, off); off += 12
            s = sampled_markers[fr][mi]
            if max(abs(p[i] - s[i]) for i in range(3)) > 1e-4:
                print(f"verify: frame {fr} marker {mn} read back {p}, sampled {s}"); sys.exit(3)
            want = (expect_markers or {}).get(fr, {}).get(mn)
            if want is not None:
                err = max(abs(p[i] - want[i]) for i in range(3))
                if err > worst:
                    worst, worst_at = err, (fr, mn, p, want)
    if off != len(data):
        print(f"verify: {len(data) - off} trailing bytes"); sys.exit(3)
    if expect_markers:
        n = sum(len(v) for v in expect_markers.values())
        if worst > 2e-3:
            fr, mn, p, want = worst_at
            print(f"verify: marker {mn} on frame {fr} is at {tuple(round(c, 4) for c in p)}, the pose put it at {want} (err {worst:.4f})")
            sys.exit(3)
        print(f"verified {x3s_path}: layout round-trips, {n} expected marker positions within {worst:.5f} m")
    else:
        print(f"verified {x3s_path}: layout round-trips")

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
    strips = ns.get("STRIPS")
    if strips:
        export_strips(list(bpy.data.objects), name, out_path, strips, fps=int(ns.get("FPS", 12)),
                      markers=ns.get("MARKERS"), parts_order=ns.get("PARTS"), max_segs=int(ns.get("MAX_SEGS", 700)), fwd=ns.get("FWD", "-z"),
                      expect_markers=ns.get("EXPECT_MARKERS"))
    else:
        export(list(bpy.data.objects), name, out_path, ns.get("SHARP_DEG", 30.0))
