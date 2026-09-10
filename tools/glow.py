#!/usr/bin/env python3
"""Post glow for Blender stroke renders: glow.py in.png out.png [radius=6] [gain=1.6]
A blurred copy of the frame is screened back over it — the same trick make_icon.py uses — so thin
emissive wires get the halo the glasses' additive renderer gives them, and nothing floods."""
import sys
from PIL import Image, ImageFilter, ImageChops
src = Image.open(sys.argv[1]).convert("RGB")
r = float(sys.argv[3]) if len(sys.argv) > 3 else 6.0
gain = float(sys.argv[4]) if len(sys.argv) > 4 else 1.6
halo = src.filter(ImageFilter.GaussianBlur(r)).point(lambda v: min(255, int(v * gain)))
wide = src.filter(ImageFilter.GaussianBlur(r * 3)).point(lambda v: min(255, int(v * gain * 0.5)))
out = ImageChops.screen(ImageChops.screen(src, halo), wide)
out.save(sys.argv[2])
