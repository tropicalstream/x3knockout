#!/usr/bin/env python3
"""Segment a motion.log (20 Hz RAW lines, tagged by guide phase) and report per phase:
   roll / pitch extremes, lateral & fore velocity extremes, and what the recogniser saw.
   usage: analyze_motion.py motion.log"""
import re, sys, math
from collections import defaultdict

PH = {"G0": "HOLD STILL", "G1": "TURN HEAD L/R", "G2": "TILT RIGHT", "G3": "TILT LEFT", "G4": "NOD DOWN",
      "G5": "SIDESTEP RIGHT", "G6": "SIDESTEP LEFT", "G7": "STEP FORWARD", "G8": "STEP BACK", "G9": "NOW FIGHT", "FIGHT": "FIGHT"}
pat = re.compile(r'^(\d+) RAW \[([^\]]*)\] ax=([+-][\d.]+) ay=([+-][\d.]+) az=([+-][\d.]+) wx=([+-][\d.]+) wy=([+-][\d.]+) wz=([+-][\d.]+) vlat=([+-][\d.]+) vfore=([+-][\d.]+) roll=([+-]\d+) pitchG=([+-]\d+) m=([\d.]+)')
rows = []
for l in open(sys.argv[1]):
    m = pat.match(l.strip())
    if m:
        g = m.groups()
        rows.append((int(g[0]), g[1]) + tuple(float(x) for x in g[2:]))
print("samples", len(rows))
by = defaultdict(list)
for r in rows: by[r[1]].append(r)
order = sorted(by.keys(), key=lambda k: (k != "FIGHT", k))
for k in order:
    rs = by[k]
    if not rs: continue
    t0 = rs[0][0]; dur = (rs[-1][0] - t0) / 1000
    ax = [r[2] for r in rs]; ay = [r[3] for r in rs]; az = [r[4] for r in rs]
    wx = [r[5] for r in rs]; wy = [r[6] for r in rs]; wz = [r[7] for r in rs]
    vl = [r[8] for r in rs]; vf = [r[9] for r in rs]; roll = [r[10] for r in rs]; pit = [r[11] for r in rs]; mo = [r[12] for r in rs]
    print(f"\n== {k} {PH.get(k, k)}  ({len(rs)} samples, {dur:.1f}s)")
    print(f"   roll  min {min(roll):+.0f}  max {max(roll):+.0f}   pitchG min {min(pit):+.0f} max {max(pit):+.0f}")
    print(f"   vlat  min {min(vl):+.2f} max {max(vl):+.2f}   vfore min {min(vf):+.2f} max {max(vf):+.2f}")
    print(f"   ax    min {min(ax):+.2f} max {max(ax):+.2f}   az min {min(az):+.2f} max {max(az):+.2f}   ay min {min(ay):+.2f} max {max(ay):+.2f}")
    print(f"   |w|   max wx {max(abs(x) for x in wx):.2f} wy {max(abs(x) for x in wy):.2f} wz {max(abs(x) for x in wz):.2f}   motion mean {sum(mo)/len(mo):.2f} max {max(mo):.2f}")
    # velocity peaks with sign, in time order (crossings of 0.25)
    ev = []
    for r in rs:
        if abs(r[8]) > 0.25: ev.append(("lat", (r[0] - t0) / 1000, r[8]))
        if abs(r[9]) > 0.25: ev.append(("fore", (r[0] - t0) / 1000, r[9]))
    if ev:
        # compress consecutive same-axis same-sign into one peak
        out = []
        for e in ev:
            if out and out[-1][0] == e[0] and (out[-1][2] > 0) == (e[2] > 0) and e[1] - out[-1][1] < 0.5:
                if abs(e[2]) > abs(out[-1][2]): out[-1] = (e[0], e[1], e[2])
            else: out.append(e)
        print("   velocity peaks:", ", ".join(f"{a} {t:.1f}s {v:+.2f}" for a, t, v in out[:12]))
