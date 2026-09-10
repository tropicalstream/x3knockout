#!/usr/bin/env python3
"""THE TWO PADS FROM THE DESK — a JSON command stream for Android's `hid -` tool.

The temple pads are capacitive: a finger needs a head, `sendevent` needs a writable evdev node
(SELinux refuses the shell on this user build), and `input tap` carries no device name. But the
shell holds the `uhid` group, so this registers two virtual HID touch screens NAMED after the
pads (cyttsp6_mt = LEFT, cyttsp5_mt = RIGHT); the InputReader names the InputDevice after the
uhid name, and MainActivity's `ev.device.name` route sees exactly what it sees from the real
pads. It proves the app's ATTRIBUTION and the dispatcher's two-device behaviour (MainActivity's
`cutLeft` KDoc) — never the pad hardware, and never RayNeo's gesture service, which watches the
real nodes and ignores these.

  export ANDROID_SERIAL=A06B4A96A733283
  tools/uhidpad.py fight > /tmp/f.json && adb push /tmp/f.json /data/local/tmp/f.json
  adb logcat -c; adb shell "hid - < /data/local/tmp/f.json"; adb logcat -d -s X3Knockout | grep -E " PAD | PUNCH | PAIR "

Scenarios: `probe` (register both, hold 4 s — for `dumpsys input`), `fight` (taps, a pair, the
double-taps, a slide, the pair the other way), `slaps` (every two-pad overlap ordering — the
cut rule's proof), `title` (the left pad silent, the right double-tap = settings).

TWO THINGS THE TOOL DOES THAT ARE NOT OBVIOUS: its reader wants a SEQUENCE of top-level JSON
objects, not an array; and `delay` is charged PER DEVICE, so every delay here goes to both ids or
the other pad's reports fire at parse time, before the kernel has finished probing the device.
"""

import json, sys

# Single-contact Windows-style touch screen: Tip Switch, Contact ID, X, Y (0..4095), Contact Count.
# Contact ID is what puts the device in HID_GROUP_MULTITOUCH so hid-multitouch binds it, and
# hid-multitouch is what sets INPUT_PROP_DIRECT (a touch SCREEN, not a pointer pad).
DESC = [
    0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01,
    0x85, 0x01,
    0x09, 0x22, 0xA1, 0x02,
    0x09, 0x42, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
    0x95, 0x07, 0x81, 0x03,
    0x75, 0x08, 0x09, 0x51, 0x95, 0x01, 0x81, 0x02,
    0x05, 0x01, 0x26, 0xFF, 0x0F, 0x75, 0x10, 0x09, 0x30, 0x95, 0x01, 0x81, 0x02,
    0x09, 0x31, 0x81, 0x02,
    0xC0,
    0x05, 0x0D, 0x09, 0x54, 0x95, 0x01, 0x75, 0x08, 0x15, 0x00, 0x25, 0x7F, 0x81, 0x02,
    0xC0,
]
L, R = 1, 2
NAME = {L: "cyttsp6_mt", R: "cyttsp5_mt"}
cmds = []

def register(dev):
    cmds.append({"id": dev, "command": "register", "name": NAME[dev], "vid": 4660, "pid": 22136 + dev,
                 "bus": "usb", "descriptor": DESC})

def delay(ms):
    # The tool keeps ONE send-time per device: a delay charged to one id leaves the other id's
    # reports firing at parse time, so every delay is charged to both.
    for dev in (L, R):
        cmds.append({"id": dev, "command": "delay", "duration": int(ms)})

def report(dev, tip, x, y):
    cmds.append({"id": dev, "command": "report",
                 "report": [1, 1 if tip else 0, 7, x & 0xFF, x >> 8, y & 0xFF, y >> 8, 1 if tip else 0]})

def down(dev, x=2048, y=2048): report(dev, True, x, y)
def up(dev, x=2048, y=2048): report(dev, False, x, y)
def tap(dev, hold=60, x=2048, y=2048): down(dev, x, y); delay(hold); up(dev, x, y)

def slide(dev, dx=1200, steps=8, step_ms=25):
    x0 = 1400
    down(dev, x0, 2048)
    for i in range(1, steps + 1):
        delay(step_ms); report(dev, True, x0 + dx * i // steps, 2048)
    delay(step_ms); up(dev, x0 + dx, 2048)

def vslide(dev, dy=1200, steps=8, step_ms=25):
    """A VERTICAL drag: the gesture the start screen's menu reads (Swipe.UP / Swipe.DOWN)."""
    y0 = 1400
    down(dev, 2048, y0)
    for i in range(1, steps + 1):
        delay(step_ms); report(dev, True, 2048, y0 + dy * i // steps)
    delay(step_ms); up(dev, 2048, y0 + dy)

def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "fight"
    register(L); register(R)
    if what == "probe":
        delay(4000)
    elif what == "fight":
        delay(2500)
        tap(L); delay(1500)                     # 1 title: LEFT is silent
        tap(R); delay(7000)                     # 2 the coin -> intro -> card -> FIGHT
        tap(L); delay(1200)                     # 3 PUNCH hand=L
        tap(R); delay(1200)                     # 4 PUNCH hand=R
        down(L); delay(40); down(R); delay(40); up(L); delay(40); up(R); delay(1500)   # 5 a pair, lifts 40 ms apart
        tap(L); delay(150); tap(L); delay(2000)  # 6 left double-tap: two punches, no menu, any KEY?
        tap(R); delay(150); tap(R); delay(2000)  # 7 right double-tap in a fight: two punches, clamped burst
        slide(L); delay(1500)                    # 8 left slide: MOVE lines, no punch
        tap(R, hold=60); delay(20); tap(L, hold=60); delay(1500)   # 9 R then L: the pair the other way
        delay(500)
    elif what == "slaps":
        delay(2500)
        down(L); delay(40); down(R); delay(40); up(R); delay(40); up(L); delay(1500)   # L first, R lifts first
        down(R); delay(40); down(L); delay(40); up(L); delay(40); up(R); delay(1500)   # R first, L lifts first
        down(R); delay(40); down(L); delay(40); up(R); delay(40); up(L); delay(1500)   # R first, R lifts first
        down(L); delay(300); down(R); delay(50); up(R); delay(200); up(L); delay(1500) # a long L hold under an R tap
        down(L); delay(300); down(R); delay(40); up(L); delay(100); up(R); delay(1500) # ... L lifting while R is down
        down(L); down(R); delay(50); up(L); up(R); delay(1500)                         # the same millisecond
        delay(300)
    elif what == "career":
        # THE CAREER, FROM THE DESK: a coin, then ninety seconds of alternating taps. With
        # `--ei hp 6` the man in the ring goes down on the punches that land while he is mid-tell,
        # which is enough to walk a bout to its knockout and put the RISE card on the glass —
        # the one screen that cannot be reached by launching into it.
        delay(2500)
        tap(R); delay(9000)                     # the coin, the ceremony, the round card
        for i in range(120):
            tap(L if i % 2 == 0 else R); delay(700)
        delay(1000)
    elif what == "open3":
        # Three right-hand taps, one file, one adb round trip — for a scripted capture running
        # alongside a concurrent scrcpy recording, where many small separate `adb shell` calls
        # were observed to silently drop (the transport appears to have a practical concurrency
        # limit on this hardware; see docs' capture notes). Fewer, larger calls is the fix.
        delay(400)
        tap(R); delay(550); tap(R); delay(550); tap(R); delay(400)
    elif what == "close2":
        delay(200)
        tap(R); delay(550); tap(R); delay(400)
    elif what == "tapr":
        # One right-pad tap, for a scripted demo that needs to interleave a punch with an adb
        # broadcast (a --ei command's DEBUG_MOTION override) between individual taps.
        delay(150); tap(R); delay(150)
    elif what == "tapl":
        delay(150); tap(L); delay(150)
    elif what == "spam":
        # Taps only, no coin: for a fight already in progress that needs finishing from the desk.
        delay(2000)
        for i in range(200):
            tap(L if i % 2 == 0 else R); delay(450)
        delay(500)
    elif what == "startmenu":
        # The start screen: swipe the RIGHT pad down twice, then tap. Should land on CREDITS.
        delay(2500)
        vslide(R); delay(1200)
        vslide(R); delay(1200)
        tap(R); delay(2500)
    elif what == "title":
        delay(2500)
        tap(L); delay(1500)
        tap(R); delay(150); tap(R); delay(1500)   # right double-tap on the title = settings
        tap(R); delay(150); tap(R); delay(1500)   # and again = close
        delay(500)
    # The tool's reader is a lenient JsonReader over a SEQUENCE of top-level objects, not an array.
    for c in cmds:
        sys.stdout.write(json.dumps(c) + "\n")

main()
