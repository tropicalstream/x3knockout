#!/usr/bin/env python3
"""What does looking cost? Reads motion.log RAW lines and prints, per guide phase and for the
fight, the head-speed distribution and the time-scale each candidate knee would charge for it.
    analyze_knee.py motion.log [more.log ...]"""
import re, math, sys
pat = re.compile(r'^(\d+) RAW \[([^\]]*)\].* wx=([+-][\d.]+) wy=([+-][\d.]+) wz=([+-][\d.]+)')
NAMES = {'G0': 'HOLD STILL', 'G1': 'TURN HEAD L/R', 'G2': 'TILT RIGHT', 'G3': 'TILT LEFT', 'G4': 'NOD DOWN',
         'G5': 'SIDESTEP R', 'G6': 'SIDESTEP L', 'FIGHT': 'FIGHT'}
rows = []
for fn in sys.argv[1:]:
    for l in open(fn):
        m = pat.match(l.strip())
        if m:
            g = m.groups()
            rows.append((g[1], math.hypot(math.hypot(float(g[2]), float(g[3])), float(g[4]))))
def pct(v, p):
    v = sorted(v); return v[min(len(v) - 1, int(len(v) * p))]
def rate(w, wref, gamma, dead=0.08, floor=0.05):
    return floor + (1 - floor) * max(0.0, min(1.0, (w - dead) / (wref - dead))) ** gamma
groups = {}
for tag, w in rows: groups.setdefault(tag, []).append(w)
print(f"samples {len(rows)}")
print(f"{'phase':16s} {'n':>5} {'med':>6} {'p90':>6} | {'A med':>6} {'A p90':>6} | {'B med':>6} {'B p90':>6}")
for tag in sorted(groups, key=lambda t: (t != 'FIGHT', t)):
    v = groups[tag]
    if len(v) < 20: continue
    med, p90 = pct(v, .5), pct(v, .9)
    print(f"{NAMES.get(tag, tag):16s} {len(v):5d} {med:6.2f} {p90:6.2f} | {rate(med,1.2,1.3):6.2f} {rate(p90,1.2,1.3):6.2f} | {rate(med,1.9,1.8):6.2f} {rate(p90,1.9,1.8):6.2f}")
f = groups.get('FIGHT', [])
if f:
    print(f"\nIN THE FIGHT ({len(f)} samples, {len(f)*0.05:.0f}s):")
    for p in (0.25, 0.5, 0.75, 0.9, 0.99):
        w = pct(f, p)
        print(f"  p{int(p*100):02d}  {w:5.2f} rad/s ({math.degrees(w):5.1f} deg/s)   A {rate(w,1.2,1.3):.2f}   B {rate(w,1.9,1.8):.2f}")
