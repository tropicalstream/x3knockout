#!/usr/bin/env python3
"""Left-eye crop of an X3 Pro SBS screencap: eye.py in.png out.png [zoom]"""
import sys
from PIL import Image
im = Image.open(sys.argv[1]).crop((0, 0, 640, 480))
z = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0
if z != 1.0: im = im.resize((int(640 * z), int(480 * z)), Image.LANCZOS)
im.save(sys.argv[2])
