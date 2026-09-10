#!/usr/bin/env python3
"""THE ENGINE-EXACT STRIP PREVIEW — `boxer.x3s` drawn the way `GLRenderer.kt` draws it, on the desk.

    tools/.venv/bin/python tools/strip_sheet.py app/src/main/assets/models/boxer.x3s sheet_%s.png
    tools/.venv/bin/python tools/strip_sheet.py boxer.x3s sheet.png --strip wing_r [--cols 8] [--scale 0.5]
    tools/.venv/bin/python tools/strip_sheet.py boxer.x3s frame.png --frame 50 [--lean 0.4] [--pitch 12]

A `%s` in the output name writes one contact sheet per strip (`sheet_wing_r.png`, …); `--strip`
picks one; `--frame N` renders one ABSOLUTE frame at full size; with neither, every strip is
stacked into one tall sheet. Every frame of a strip is drawn — that is what the READ needs: the
tell frames, the extend, the fall — and TEST.md's `sheet_<strip>.png` spelling is accepted for `%s`.

WHAT IS EXACT (DESIGN.md §12.1, §12.4, §7.1): the camera at the eye (0, 1.65, 0) — `--lean` slides
it like the body's lean, `--pitch` nods it — looking down -Z; `perspectiveM(62°, 4:3, 0.15, 120)`
and `project() = 320 + ndc·320, 240 − ndc·240`; the figure at (0, 0, −2.6) billboarded through
`StripSet.headingTo` (the −z convention: `atan2(−(tx − px), −(tz − pz))`), then `walkFrame`'s
model transform; per-part colour from the manifest with the renderer's rest gains (outline 1,
detail 1 inside 3.4 m, hatch 0.35 skin / 0.55 gloves-and-trunks × √(2.6 / d)); the two passes —
a 4-px band at α 0.30 and a 1.5-px core at α 1.0 — summed ADDITIVELY per stroke with the
premultiplied clamp `min(1, rgb·a) · min(1, a)`, so a doubled contour's halos fuse into a bar
exactly as they do on the glass and a tint gain above 1 hots the hue without whitening it.

WHAT IS NOT: the tint pass (no flash, no crest hue — the engine writes those from `Boxer`'s
state; here every part is its authored colour), the secondary motion (sway, wobble, squash), the
waveguide's own bloom. The WHITEISH count per frame (pixels with every channel ≥ 0.7) is the
number DESIGN.md §7.2's one-white rule is policed with: on a tell frame it may rise by the glove
and the pupils only — here, with no flash, it should barely move between frames.

Markers are drawn as small crosses (`--no-markers` hides them) because they are what the engine
READS — the arc's origin, the hit spark, your punch's target — and a marker that drifted off its
glove would be invisible any other way. The canvas line (y = 0 at the figure's plane) is drawn
dim so the lying poses can be read against the floor.
"""
import argparse
import json
import math
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 640, 480
FOVY = 62.0
ASPECT = 4.0 / 3.0
NEAR = 0.15
EYE_H = 1.65
BOXER_Z = -2.6
WHITE_T = 0.7

# ---------------------------------------------------------------- the file
def load(x3s_path):
    """The manifest beside the .x3s, and every frame: (counts[nParts], segs (n, 6), markers (nMarkers, 3))."""
    man = json.load(open(os.path.splitext(x3s_path)[0] + ".json"))
    data = open(x3s_path, "rb").read()
    if data[:4] != b"X3S1":
        sys.exit("bad magic %r" % data[:4])
    n_p, n_m, n_f = struct.unpack_from("<III", data, 4)
    if n_p != len(man["parts"]) or n_m != len(man["markers"]):
        sys.exit("manifest/x3s disagree: parts %d/%d markers %d/%d" % (len(man["parts"]), n_p, len(man["markers"]), n_m))
    off = 16
    frames = []
    for _ in range(n_f):
        counts = struct.unpack_from("<%dI" % n_p, data, off); off += 4 * n_p
        n = sum(counts)
        segs = np.frombuffer(data, dtype="<f4", count=n * 6, offset=off).reshape(n, 6).astype(np.float64); off += n * 24
        mk = np.frombuffer(data, dtype="<f4", count=n_m * 3, offset=off).reshape(n_m, 3).astype(np.float64); off += n_m * 12
        frames.append((counts, segs, mk))
    if off != len(data):
        sys.exit("x3s has %d trailing bytes" % (len(data) - off))
    return man, frames

# ---------------------------------------------------------------- the camera (GLRenderer.kt)
class Camera:
    def __init__(self, lean=0.0, pitch_deg=0.0, yaw_deg=0.0, duck=0.0):
        self.pos = np.array([lean, EYE_H - duck, 0.0])
        self.pitch = math.radians(pitch_deg)
        self.yaw = math.radians(yaw_deg)
        # the billboard: StripSet.headingTo(px, pz, tx, tz) for a -z figure = atan2(-(tx - px), -(tz - pz))
        self.boxer_yaw = math.atan2(-(self.pos[0] - 0.0), -(self.pos[2] - BOXER_Z))
        self.dist = math.hypot(self.pos[0] - 0.0, self.pos[2] - BOXER_Z)

    def place(self, pts):
        """walkFrame's model transform (scale 1, no roll/pitch): yaw about Y, then translate to (0, 0, -2.6)."""
        cy, sy = math.cos(self.boxer_yaw), math.sin(self.boxer_yaw)
        x = pts[:, 0] * cy + pts[:, 2] * sy
        z = -pts[:, 0] * sy + pts[:, 2] * cy
        return np.stack([x, pts[:, 1], z + BOXER_Z], axis=1)

    def project(self, world):
        """World -> plate pixels through the look (yaw about Y, pitch about X) and perspectiveM(62°, 4:3)."""
        p = world - self.pos
        cy, sy = math.cos(-self.yaw), math.sin(-self.yaw)
        x = p[:, 0] * cy + p[:, 2] * sy
        z = -p[:, 0] * sy + p[:, 2] * cy
        cp, sp = math.cos(self.pitch), math.sin(self.pitch)      # positive pitch = nod down: the world rises
        y = p[:, 1] * cp - z * sp
        z = p[:, 1] * sp + z * cp
        f = 1.0 / math.tan(math.radians(FOVY / 2.0))
        valid = z < -NEAR
        zz = np.where(valid, z, -NEAR)
        xn = (f / ASPECT) * x / (-zz)
        yn = f * y / (-zz)
        return 320.0 + xn * 320.0, 240.0 - yn * 240.0, valid

# ---------------------------------------------------------------- the strokes (two passes, additive, premultiplied clamp)
def stroke(buf, x0, y0, x1, y1, hw, rgb, a):
    """One GL line of half-width `hw` px at alpha `a`: coverage × min(1, rgb·a)·min(1, a), added."""
    c = np.minimum(1.0, np.asarray(rgb) * a) * min(1.0, a)
    xmin = max(0, int(math.floor(min(x0, x1) - hw - 1))); xmax = min(W - 1, int(math.ceil(max(x0, x1) + hw + 1)))
    ymin = max(0, int(math.floor(min(y0, y1) - hw - 1))); ymax = min(H - 1, int(math.ceil(max(y0, y1) + hw + 1)))
    if xmin > xmax or ymin > ymax:
        return
    xs = np.arange(xmin, xmax + 1, dtype=np.float64) + 0.5
    ys = np.arange(ymin, ymax + 1, dtype=np.float64) + 0.5
    X, Y = np.meshgrid(xs, ys)
    dx, dy = x1 - x0, y1 - y0
    l2 = dx * dx + dy * dy
    if l2 < 1e-9:
        px, py = x0, y0
    else:
        t = np.clip(((X - x0) * dx + (Y - y0) * dy) / l2, 0.0, 1.0)
        px, py = x0 + t * dx, y0 + t * dy
    dist = np.hypot(X - px, Y - py)
    cov = np.clip(hw + 0.5 - dist, 0.0, 1.0)
    buf[ymin:ymax + 1, xmin:xmax + 1, :] += cov[:, :, None] * c[None, None, :]

def part_gain(part, dist):
    """GLRenderer.boxerScene's rest gains: outline 1, detail 1 (inside 3.4 m), hatch 0.35 / 0.55 × √(2.6 / d)."""
    cls = part.get("cls", "outline")
    if cls == "hatch":
        base = 0.55 if (part["name"].startswith("hatch_glove") or part["name"] == "hatch_trunks") else 0.35
        return base * math.sqrt(2.6 / max(0.3, dist))
    if cls == "detail":
        return 1.0 if dist < 3.4 else 0.0
    return 1.0

def render(man, frame, cam, markers=True, canvas=True):
    """One frame to a float buffer (H, W, 3); returns (image uint8, whiteish pixel count, drawn segments)."""
    counts, segs, mk = frame
    buf = np.zeros((H, W, 3), dtype=np.float64)
    if canvas:
        line = np.array([[-1.6, 0.0, 0.0], [1.6, 0.0, 0.0]])
        x, y, ok = cam.project(cam.place(line))
        if ok.all():
            stroke(buf, x[0], y[0], x[1], y[1], 0.75, (0.5, 0.5, 0.5), 0.35)
    a_pts = cam.place(segs[:, 0:3]); b_pts = cam.place(segs[:, 3:6])
    ax, ay, aok = cam.project(a_pts); bx, by, bok = cam.project(b_pts)
    drawn = 0
    at = 0
    for pi, part in enumerate(man["parts"]):
        n = counts[pi]
        if n == 0:
            continue
        g = part_gain(part, cam.dist)
        rgb = tuple(part.get("color", [1, 1, 1]))
        if g > 0.0:
            for i in range(at, at + n):
                if not (aok[i] and bok[i]):
                    continue
                stroke(buf, ax[i], ay[i], bx[i], by[i], 2.0, rgb, 0.30 * g)     # the wide pass: glLineWidth(4) at uAlpha 0.30
                stroke(buf, ax[i], ay[i], bx[i], by[i], 0.75, rgb, 1.0 * g)     # the core: 1.5 px at 1.0
                drawn += 1
        at += n
    img = np.clip(buf, 0.0, 1.0)
    white = int(np.count_nonzero(img.min(axis=2) >= WHITE_T))
    out = Image.fromarray((img * 255.0 + 0.5).astype(np.uint8), "RGB")
    if markers and len(mk):
        d = ImageDraw.Draw(out)
        mx, my, mok = cam.project(cam.place(mk))
        for j, name in enumerate(man["markers"]):
            if not mok[j]:
                continue
            x, y = float(mx[j]), float(my[j])
            col = (255, 230, 90) if name.startswith("glove") else (90, 255, 230)
            d.line([(x - 5, y), (x + 5, y)], fill=col); d.line([(x, y - 5), (x, y + 5)], fill=col)
    d = ImageDraw.Draw(out)
    d.rectangle([0, 0, W - 1, H - 1], outline=(70, 70, 70))
    return out, white, drawn

# ---------------------------------------------------------------- the sheets
def font(size):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()

def sheet_for(man, frames, strip, cam, cols, scale, markers, label=True):
    """A contact sheet of one strip: its frames left to right, a caption under each (local frame,
    absolute frame, events entered on that frame, segments, whiteish pixels)."""
    first, count = strip["first"], strip["count"]
    events = {}
    for k, v in strip.get("events", {}).items():
        events.setdefault(int(v), []).append(k)
    tw, th = int(W * scale), int(H * scale)
    cap = 16
    rows = (count + cols - 1) // cols
    head = 22 if label else 0
    sheet = Image.new("RGB", (cols * tw, head + rows * (th + cap)), (8, 8, 8))
    d = ImageDraw.Draw(sheet)
    f_small = font(11); f_head = font(14)
    stats = []
    for i in range(count):
        af = first + i
        img, white, drawn = render(man, frames[af], cam, markers=markers)
        stats.append((i, af, white, drawn))
        r, c = divmod(i, cols)
        x0, y0 = c * tw, head + r * (th + cap)
        sheet.paste(img.resize((tw, th), Image.LANCZOS), (x0, y0))
        ev = ",".join(events.get(i, []))
        text = "f%d #%d  segs %d  white %d" % (i, af, drawn, white) + ("  [%s]" % ev if ev else "")
        d.text((x0 + 3, y0 + th + 2), text, fill=(200, 200, 200) if not ev else (255, 220, 120), font=f_small)
    if label:
        d.text((4, 4), "%s  %d frames @%d fps  %.2f s  loop=%s  events=%s" % (
            strip["name"], count, man.get("fps", 12), count / float(man.get("fps", 12)), strip.get("loop", False),
            strip.get("events", {})), fill=(255, 255, 255), font=f_head)
    return sheet, stats

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("x3s"); ap.add_argument("out")
    ap.add_argument("--strip"); ap.add_argument("--frame", type=int)
    ap.add_argument("--cols", type=int, default=8); ap.add_argument("--scale", type=float, default=0.5)
    ap.add_argument("--lean", type=float, default=0.0, help="camera x, like the body's lean (m)")
    ap.add_argument("--pitch", type=float, default=0.0, help="camera nod, degrees, positive = down")
    ap.add_argument("--yaw", type=float, default=0.0); ap.add_argument("--duck", type=float, default=0.0)
    ap.add_argument("--no-markers", action="store_true")
    args = ap.parse_args()
    args.out = args.out.replace("<strip>", "%s")      # TEST.md writes the per-strip name as sheet_<strip>.png
    man, frames = load(args.x3s)
    cam = Camera(args.lean, args.pitch, args.yaw, args.duck)
    strips = {s["name"]: s for s in man.get("strips", [])}
    if args.frame is not None:
        img, white, drawn = render(man, frames[args.frame], cam, markers=not args.no_markers)
        img.save(args.out)
        print("frame %d: %d segments, %d whiteish px -> %s" % (args.frame, drawn, white, args.out))
        return
    names = [args.strip] if args.strip else list(strips)
    for n in names:
        if n not in strips:
            sys.exit("no strip %r; have %s" % (n, list(strips)))
    if "%s" in args.out or len(names) == 1:
        for n in names:
            sheet, stats = sheet_for(man, frames, strips[n], cam, args.cols, args.scale, not args.no_markers)
            path = args.out % n if "%s" in args.out else args.out
            sheet.save(path)
            ws = [s[2] for s in stats]
            print("%-11s %3d frames  segs %d..%d  whiteish %d..%d -> %s" % (
                n, len(stats), min(s[3] for s in stats), max(s[3] for s in stats), min(ws), max(ws), path))
        return
    sheets = []
    for n in names:
        sheet, stats = sheet_for(man, frames, strips[n], cam, args.cols, args.scale, not args.no_markers)
        ws = [s[2] for s in stats]
        print("%-11s %3d frames  segs %d..%d  whiteish %d..%d" % (n, len(stats), min(s[3] for s in stats), max(s[3] for s in stats), min(ws), max(ws)))
        sheets.append(sheet)
    wmax = max(s.width for s in sheets)
    tall = Image.new("RGB", (wmax, sum(s.height for s in sheets)), (8, 8, 8))
    y = 0
    for s in sheets:
        tall.paste(s, (0, y)); y += s.height
    tall.save(args.out)
    print("-> %s (%d x %d)" % (args.out, tall.width, tall.height))

if __name__ == "__main__":
    main()
