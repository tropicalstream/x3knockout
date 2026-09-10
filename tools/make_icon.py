#!/usr/bin/env python3
"""X3 KNOCKOUT launcher icon: THE ROOSTER, guard up, in the game's own palette and its own new
cel-shading (`GLRenderer.fillPass`) — not the Tron Recognizer this file drew for x3discs before
the fork, which had nothing to do with a boxing game and had been sitting in the launcher by
accident. Rendered with Pillow into every launcher density plus art/icon-512.png.

WHY THE ROOSTER AND NOT THE TITLE OR A GLOVE. He is the tutorial fight and the card's own grammar
(BOXER.md: colour IS his tell), so he is the one silhouette every player already recognises before
they have thrown a punch, and a face reads at 48 dp where a scoreboard or a fist does not. Guard
up — both gloves crossed at the chin — because that is his own idle pose already, not a new one
invented for the icon.

    tools/.venv/bin/python tools/make_icon.py   (Pillow)
"""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
S = 1024  # master canvas, alpha, matching the mipmap-anydpi-v26 foreground contract

# ---------------------------------------------------------------- THE GAME'S OWN PALETTE
# Straight out of Hud.kt / Fighters.kt.ROOSTER, not a new one picked for the icon.
MAGENTA = (255, 38, 153)     # his outline (the suite's threat hue)
MAGENTA_DIM = (140, 20, 84)  # the cel fill's shade side
MAGENTA_LIT = (255, 130, 190)  # the cel fill's lit side
RED = (255, 56, 46)          # his gloves
RED_DIM = (150, 28, 22)
RED_LIT = (255, 150, 120)
CYAN = (89, 242, 255)        # his trunks — a sliver at the very bottom
VIOLET = (153, 51, 255)      # his crest at rest
WHITE = (235, 255, 255)      # his eye rings, and every hot core in this game
BLUE = (77, 128, 255)        # the ring ropes


def layer():
    return Image.new("RGBA", (S, S), (0, 0, 0, 0))


def strokes(draw, segs, color, w):
    for (x0, y0, x1, y1) in segs:
        draw.line((x0, y0, x1, y1), fill=color, width=w, joint="curve")


def glow_strokes(master, segs, color, core_w, glow_w=None, glow_blur=None, core_blur=1):
    """THE GAME'S OWN COMIC OUTLINE: a wide blurred pass under a crisp one — the additive
    waveguide look, not a plain line. Same idiom the shipped `make_icon.py` used, kept because
    it is what makes strokes on black read as light rather than paint."""
    glow_w = glow_w or core_w * 2.6
    glow_blur = glow_blur if glow_blur is not None else core_w * 1.1
    g = layer(); gd = ImageDraw.Draw(g); strokes(gd, segs, color + (255,), int(glow_w))
    master = Image.alpha_composite(master, g.filter(ImageFilter.GaussianBlur(glow_blur)))
    g = layer(); gd = ImageDraw.Draw(g); strokes(gd, segs, color + (255,), int(core_w))
    if core_blur:
        master = Image.alpha_composite(master, g.filter(ImageFilter.GaussianBlur(core_blur)))
    return Image.alpha_composite(master, g)


def h_gradient(w, h, c0, c1):
    grad = Image.new("RGBA", (w, h), 0)
    row = Image.new("RGBA", (w, 1), 0)
    rd = ImageDraw.Draw(row)
    for x in range(w):
        t = x / max(1, w - 1)
        rd.point((x, 0), fill=(
            int(c0[0] + (c1[0] - c0[0]) * t), int(c0[1] + (c1[1] - c0[1]) * t),
            int(c0[2] + (c1[2] - c0[2]) * t), 255))
    return row.resize((w, h))


def cel_fill(master, mask_img, c_shade, c_lit, gain=1.0):
    """THE FLAT COLOUR (`GLRenderer.fillPass`'s own idea, shipped 2026-09-10): a shade-to-lit
    gradient across the shape's own width, masked by its silhouette — a comic panel's flat,
    not a solid fill, because the ring's key light in this game is always from the right."""
    grad = h_gradient(S, S, c_shade, c_lit)
    a = mask_img.split()[3].point(lambda v: int(v * gain))
    grad.putalpha(a)
    return Image.alpha_composite(master, grad)


def ellipse_mask(cx, cy, rx, ry, rot=0.0):
    m = layer(); d = ImageDraw.Draw(m)
    if rot == 0.0:
        d.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=(255, 255, 255, 255))
        return m
    # a rotated ellipse, approximated as a polygon — enough facets to read smooth at 1024 px
    import math
    pts = []
    for i in range(48):
        a = 2 * math.pi * i / 48
        x = rx * math.cos(a); y = ry * math.sin(a)
        cr, sr = math.cos(rot), math.sin(rot)
        pts.append((cx + x * cr - y * sr, cy + x * sr + y * cr))
    d.polygon(pts, fill=(255, 255, 255, 255))
    return m


def double_contour(master, mask_img, color, gap=10, w=7, glow=22):
    """Two parallel rings a few px apart, the comic outline every part in this game is drawn
    with — approximated on an ellipse mask by dilating it and drawing the two resulting edges.
    `mask_img` is an RGBA layer whose ALPHA channel is the silhouette (see `ellipse_mask`)."""
    alpha = mask_img.split()[3]
    outer = alpha.filter(ImageFilter.MaxFilter(gap * 2 + 1))
    inner = alpha
    edge_outer = outer.filter(ImageFilter.FIND_EDGES)
    edge_inner = inner.filter(ImageFilter.FIND_EDGES)
    g = layer()
    for edge in (edge_outer, edge_inner):
        col = Image.new("RGBA", (S, S), color + (255,))
        col.putalpha(edge.point(lambda v: 255 if v > 20 else 0))
        g = Image.alpha_composite(g, col)
    thick = g.filter(ImageFilter.MaxFilter(max(1, (w // 2) * 2 + 1)))
    master = Image.alpha_composite(master, thick.filter(ImageFilter.GaussianBlur(glow / 3)))
    master = Image.alpha_composite(master, thick)
    return master


master = Image.new("RGBA", (S, S), (0, 0, 0, 255))
cx, cy = S / 2, S * 0.40  # the head's centre; the whole figure hangs off this one point

# ---------------------------------------------------------------- THE ARENA, FAR BEHIND HIM
# A hint of the ring and the crowd — dim, so they read as depth at large sizes and vanish
# cleanly at 48 dp rather than turning to noise.
haze = layer(); hd = ImageDraw.Draw(haze)
hd.ellipse((S * 0.10, S * 0.06, S * 0.90, S * 0.86), fill=MAGENTA_DIM + (110,))
master = Image.alpha_composite(master, haze.filter(ImageFilter.GaussianBlur(170)))

ropes = layer(); rd = ImageDraw.Draw(ropes)
for i, y in enumerate((0.86, 0.90, 0.94)):
    rd.line((S * 0.0, S * y, S * 1.0, S * (y - 0.05)), fill=BLUE + (90,), width=6)
master = Image.alpha_composite(master, ropes.filter(ImageFilter.GaussianBlur(2)))

crowd = layer(); cd = ImageDraw.Draw(crowd)
pts = []
for i in range(0, 23):
    x = S * i / 22
    y = S * 0.06 + (18 if i % 2 == 0 else 0)
    pts.append((x, y))
cd.line(pts, fill=VIOLET + (80,), width=5)
master = Image.alpha_composite(master, crowd.filter(ImageFilter.GaussianBlur(2)))

# ---------------------------------------------------------------- HIS SHOULDERS AND TRUNKS
# Just enough of the torso to say "a whole fighter", mostly hidden behind the guard.
torso_mask = layer(); td = ImageDraw.Draw(torso_mask)
td.polygon([(cx - 300, S * 0.98), (cx - 240, cy + 260), (cx + 240, cy + 260), (cx + 300, S * 0.98)],
           fill=(255, 255, 255, 255))
master = cel_fill(master, torso_mask, MAGENTA_DIM, (200, 60, 120))
edge = torso_mask.split()[3].filter(ImageFilter.FIND_EDGES)
outline = Image.new("RGBA", (S, S), MAGENTA + (255,)); outline.putalpha(edge)
master = Image.alpha_composite(master, outline.filter(ImageFilter.GaussianBlur(2)))
master = Image.alpha_composite(master, outline)

trunks_mask = layer(); trd = ImageDraw.Draw(trunks_mask)
trd.polygon([(cx - 270, S * 0.94), (cx - 220, S * 1.02), (cx + 220, S * 1.02), (cx + 270, S * 0.94)],
            fill=(255, 255, 255, 255))
master = cel_fill(master, trunks_mask, (20, 90, 100), CYAN)

# ---------------------------------------------------------------- THE CREST — five spikes, VIOLET
# Five shards fanned across the top of the head, tallest in the middle (his own §4: the crest
# droops with his health, but at full health it stands straight up — this is the icon's "full HP").
import math
n_spikes = 5
base_y = cy - 168
tips = []
crest_glow = layer(); cgd = ImageDraw.Draw(crest_glow)
crest_core = layer(); ccd = ImageDraw.Draw(crest_core)
for i in range(n_spikes):
    d = i - (n_spikes - 1) / 2.0   # -2..2, 0 in the middle
    bx = cx + d * 78
    height = 235 - abs(d) * 42     # tallest in the middle, still tall at the flanks
    fan = d * 26                   # the outer spikes lean outward
    tx, ty = bx + fan, base_y - height
    poly = [(bx - 30, base_y + 10), (bx + 30, base_y + 10), (tx + 9, ty + 26), (tx, ty), (tx - 9, ty + 26)]
    cgd.polygon(poly, fill=VIOLET + (255,))
    ccd.polygon(poly, fill=VIOLET + (255,))
    tips.append((tx, ty))
master = Image.alpha_composite(master, crest_glow.filter(ImageFilter.GaussianBlur(30)))
master = Image.alpha_composite(master, crest_core.filter(ImageFilter.GaussianBlur(3)))
master = Image.alpha_composite(master, crest_core)
# a white-hot core at each tip — he is always a beat from throwing the uppercut
tip_glow = layer(); tgd = ImageDraw.Draw(tip_glow)
for (tx, ty) in tips:
    tgd.ellipse((tx - 12, ty - 12, tx + 12, ty + 12), fill=WHITE + (255,))
master = Image.alpha_composite(master, tip_glow.filter(ImageFilter.GaussianBlur(16)))
master = Image.alpha_composite(master, tip_glow.filter(ImageFilter.GaussianBlur(3)))

# ---------------------------------------------------------------- THE HEAD — cel-filled, doubled contour
head_mask = ellipse_mask(cx, cy, 195, 220)
master = cel_fill(master, head_mask, MAGENTA_DIM, MAGENTA_LIT)
master = double_contour(master, head_mask, MAGENTA, gap=9, w=8, glow=20)

# ears
for sx in (-1, 1):
    ear_mask = ellipse_mask(cx + sx * 190, cy - 10, 42, 58)
    master = cel_fill(master, ear_mask, MAGENTA_DIM, MAGENTA_LIT)
    master = double_contour(master, ear_mask, MAGENTA, gap=6, w=6, glow=14)

# jaw hatch: the five-o'clock shadow
jaw = []
for i in range(6):
    x = cx - 110 + i * 44
    jaw.append((x, cy + 145, x + 20, cy + 168))
master = glow_strokes(master, jaw, MAGENTA_DIM, 5, glow_w=10, glow_blur=6, core_blur=0)

# brows — angled down toward the centre: determined, not friendly
brows = [(cx - 145, cy - 95, cx - 45, cy - 60), (cx + 145, cy - 95, cx + 45, cy - 60)]
master = glow_strokes(master, brows, MAGENTA, 14, glow_w=30, glow_blur=10)

# eyes: white rings, magenta pupils looking straight out of the icon
for sx in (-1, 1):
    ex, ey = cx + sx * 82, cy - 8
    ring = layer(); rgd = ImageDraw.Draw(ring)
    rgd.ellipse((ex - 38, ey - 38, ex + 38, ey + 38), outline=WHITE + (255,), width=9)
    master = Image.alpha_composite(master, ring.filter(ImageFilter.GaussianBlur(10)))
    master = Image.alpha_composite(master, ring)
    pup = layer(); pgd = ImageDraw.Draw(pup)
    pgd.ellipse((ex - 16, ey - 16, ex + 16, ey + 16), fill=MAGENTA + (255,))
    master = Image.alpha_composite(master, pup.filter(ImageFilter.GaussianBlur(8)))
    master = Image.alpha_composite(master, pup)
    hi = layer(); hgd = ImageDraw.Draw(hi)
    hgd.ellipse((ex - 5, ey - 10, ex + 5, ey), fill=WHITE + (255,))
    master = Image.alpha_composite(master, hi)

# nostrils
for sx in (-1, 1):
    nx, ny = cx + sx * 16, cy + 48
    master = glow_strokes(master, [(nx - 6, ny, nx + 6, ny)], MAGENTA_DIM, 8, glow_w=14, glow_blur=5, core_blur=0)

# ---------------------------------------------------------------- THE GLOVES — his own idle guard
# Straight off the shipped sprite: two big mitts flanking the head at cheek height, RED,
# cel-filled, the doubled contour every part in this game draws with. Not crossed in front of
# the chin — that pose is invented; this one is his actual guard.
GLOVES = [(-1, 55), (1, 25)]  # (side, y-offset from head centre) — left leads a touch lower
for side, dy in GLOVES:
    gcx, gcy = cx + side * 300, cy + dy
    g_mask = ellipse_mask(gcx, gcy, 158, 145)
    master = cel_fill(master, g_mask, RED_DIM, RED_LIT)
    master = double_contour(master, g_mask, RED, gap=11, w=9, glow=24)
    # the thumb bump, toward the chin
    tx, ty = gcx - side * 118, gcy + 34
    thumb_mask = ellipse_mask(tx, ty, 46, 40)
    master = cel_fill(master, thumb_mask, RED_DIM, RED_LIT)
    master = double_contour(master, thumb_mask, RED, gap=6, w=6, glow=14)
    # laces: three short horizontal ticks on the glove's face
    laces = []
    for i in range(3):
        ly = gcy - 40 + i * 34
        laces.append((gcx - 34, ly, gcx + 34, ly + 4))
    master = glow_strokes(master, laces, WHITE, 6, glow_w=12, glow_blur=5, core_blur=0)

# a small white impact spark off the leading (left) glove — he is a beat from landing it
spark_cx, spark_cy = cx - 300 - 168, cy + 55 - 60
spark = layer(); sd = ImageDraw.Draw(spark)
for i in range(8):
    a = math.pi * i / 4
    r1 = 46 if i % 2 == 0 else 22
    sd.line((spark_cx, spark_cy, spark_cx + math.cos(a) * r1, spark_cy + math.sin(a) * r1),
            fill=WHITE + (255,), width=7)
master = Image.alpha_composite(master, spark.filter(ImageFilter.GaussianBlur(10)))
master = Image.alpha_composite(master, spark.filter(ImageFilter.GaussianBlur(2)))

# ---------------------------------------------------------------- SCANLINES — the waveguide screen
sl = layer(); sd = ImageDraw.Draw(sl)
for y in range(0, S, 6):
    sd.line((0, y, S, y), fill=(0, 0, 0, 60), width=2)
master = Image.alpha_composite(master, sl)

rgb = master.convert("RGB")
os.makedirs(os.path.join(ROOT, "art"), exist_ok=True)
rgb.resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, "art/icon-512.png"))


def fg(px):
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    canvas.alpha_composite(master.resize((px, px), Image.LANCZOS))
    return canvas


def legacy(px):
    return rgb.resize((px, px), Image.LANCZOS)


def legacy_round(px):
    im = legacy(px).convert("RGBA")
    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, px - 1, px - 1), fill=255)
    im.putalpha(mask)
    return im


dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for name, k in dens.items():
    d = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(d, exist_ok=True)
    fg(int(108 * k)).save(os.path.join(d, "ic_launcher_foreground.png"))
    legacy(int(48 * k)).save(os.path.join(d, "ic_launcher.png"))
    legacy_round(int(48 * k)).save(os.path.join(d, "ic_launcher_round.png"))
print("icon written")
