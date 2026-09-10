# The body, as the X3 Pro can feel it

Everything here was **measured on the glasses, on the owner, standing up** — three guided test runs
on 2026-09-09. Nothing in this file is inferred from a datasheet. Where a number was chosen rather
than measured, it says so. This is the authority for the motion model; `DESIGN.md` defers to it.

## What the hardware gives us

| Sensor | Type | Rate asked / max | Used for |
|---|---|---|---|
| `TYPE_GAME_ROTATION_VECTOR` | QTI fusion | 20 ms / 100 Hz | head yaw + pitch for looking (`HeadTracker`) |
| `TYPE_GYROSCOPE` | lsm6dsr | 10 ms / 416 Hz | angular speed → the clock; rate gates |
| `TYPE_LINEAR_ACCELERATION` | QTI fusion | 10 ms / 100 Hz | body acceleration → the clock; steps |
| `TYPE_GRAVITY` | QTI fusion | 20 ms / 100 Hz | roll + pitch, drift-free |

There is **no positional tracking and no step-counter sensor**. Everything about the body is
inferred from rotation and acceleration.

### Device frame (confirmed, not assumed)

`+X` = wearer's right · `+Y` = up · `+Z` = forward along the look axis.

Two sign relations were measured rather than guessed, and both were wrong on the first attempt:

- **`pitchG`**: `atan2(gz, hypot(gx, gy))` reads a nod DOWN as *positive*. It is negated. Before the
  fix, a full duck logged `pitchG=+52` and clamped to zero — the duck could not register at all.
- **`rollRate`**: the gyro's `z` is the *negative* of `d(roll)/dt` in this class's convention —
  regression slope **−0.88 over 56 samples** across the guided run. It is negated.

## The clock

```
motion  = max(normalise(|ω|, DEAD_W=0.08, W_REF=1.2), normalise(|a|, DEAD_A=0.25, A_REF=2.2)) ^ 1.3
        smoothed: attack 40 ms, release 160 ms      // motion is spent, not banked
timeScale = floor + (1 - floor) * motion            // floor = 5 % / 10 % / 20 %, a setting
```

*(Superseded by design, not by measurement: the knee ships as Set B — `W_REF` 1.9, γ 1.8, see the
last section — and the floor follows difficulty, 3 / 5 / 8 %, with the 3 / 5 / 8 / 12 / 20 % row
lab-only; DESIGN.md §1.1–1.2. The measurements above were taken under Set A and stand.)*

Measured behaviour: standing still gives `motion` **0.00–0.02** and holds the clock at its floor;
a head turn or a step drives it to **1.00**. A *held* posture (a lean at −39°) sits back at the floor,
which is the correct SUPERHOT reading — a pose is not motion.

## Posture → body (the extrapolation)

The glasses know the head's attitude and nothing about where the body went, so the body is inferred
from it. Both of these move **the camera and the collider by the same amount** — that is the whole
point, and it was the owner's note that made it so: *"tilting just appears as tilting but should
also give a slight dodge effect."*

**Lean** — shaped, not proportional. A proportional lean gave 19 cm at 15°, which is invisible
against the world's own counter-roll:

```
r = roll - roll0                                   // roll0 = rest posture, taken at the coin
k = clamp((|r| - 0.07) / (0.38 - 0.07), 0, 1)      // dead zone ~4°, full by ~22°
leanX = sign(r) * 0.55 * smoothstep(k)             // up to a whole sidestep's worth
```

**Duck** — the nod that always accompanies a crouch:

```
duckAmt = clamp((pitch0 - pitchG) / 0.5, 0, 1)     // full crouch at ~29° of nod
headY   = 1.65 - 0.42 * duckAmt                    // eye drops to 1.23 m
```

Measured: a real duck reaches `duckAmt = 1.00`, a real lean reaches `leanX = ±0.55`. Owner's verdict
on the duck before the lean was reshaped: *"gives impression of ducking."*

**The collider is the inferred body** — a capsule, half-width 0.26 m, from 0.12 m above the eye to
0.95 m below it, centred on `(headX, headY)`. A disc is thrown at where the body *was*; whether it
connects is decided by where the body *is*. No dodge is a threshold test.

## Steps

A step is a **velocity**, not a jolt. The first detector looked for an acceleration pulse ≥ 1.6 m/s²
and never fired once on the owner's deliberate, slow steps. So acceleration is integrated into a
leaky velocity (`τ = 0.6 s`) and a step is that velocity crossing `V_STEP` (0.45 / **0.32** / 0.22
m/s by STEP SENSE) — *provided* it survives four gates, every one of which was added in response to
a specific false positive that was logged:

| Gate | What it rejects | The evidence |
|---|---|---|
| `turning` — peak `\|ω_y\|` > 1.0 rad/s in a 400 ms window | the glasses swinging sideways on the neck during a head turn | a clean false sidestep at 3.2 rad/s; then a second one *after* the rate had decayed, which is why the window is peak-over-400 ms and not an instant |
| `leaning` — `\|rollRate\|` > 0.9, or roll range > 0.14 rad in the window, or held > 0.35 rad off rest | a tilt's own sideways travel, and gravity leaking into "linear" acceleration at large tilts | a phantom step at 39° of tilt |
| `nodding` — same three tests on pitch | the head arcing forward on a duck | **one nod produced three phantom steps**: forward down, back on the recovery, forward again |
| directional push — the signed acceleration peak *in the step's own direction* must reach 1.2 m/s² | a brake being counted as the next step's push; slow weight shifts | a return step credited to its own outbound shove; a step fired on a 0.5 m/s² weight shift |

Plus a 550 ms refractory period and hysteresis (the velocity must fall back under `V_STEP / 2` before
the same axis can fire again).

A recognised step drives the body 0.55 m sideways over 1.1 s (out fast, back slow) — a lunge and a
recovery, in *real* time, because it is the body and not the world.

**Pad fallback:** a horizontal swipe is a sidestep, logged as `PAD` rather than `BODY`, for anyone
whose real steps the glasses cannot feel. *(Superseded: in the shipping design a horizontal swipe
is the HOP to the adjacent platform and the PAD sidestep is removed — DESIGN.md §5, BUILD_PLAN
§1.4. The guaranteed pad escape for every lane is deflect / steal / cut / hop.)*

## Comfort

The horizon is **counter-rolled** (`ROLL_COMP = 1`): a tilted head sees a level arena. Rendering is
a measured **59.8–60.0 fps** with the arena, a Program and discs on screen.

## How to test this again

Turn MOTION LAB on (default) and start a run: the guide walks the wearer through hold-still, head
turn, tilt each way, nod, and a sidestep each way, then three fight cards, then `NOW FIGHT`. Swipe
up skips it. Every 50 ms sample is tagged with the phase and written to `cache/motion.log`:

```sh
adb shell run-as com.x3knockout cat cache/motion.log > motion.log
tools/.venv/bin/python tools/analyze_motion.py motion.log
```

`analyze_motion.py` segments by phase and prints roll/pitch extremes, velocity peaks and gate inputs
for each — which is how every number above was arrived at. Game events (throws, enemy throws, hits,
dodges with the posture that produced them, cuts, misses with their distance) go to logcat as
`EVENT` lines.

## The knee: what looking actually costs (measured, 2026-09-09)

`DESIGN.md` §1.1 offers two curves for how head rotation buys time and calls the choice the risk
that gates the whole design. The owner's recorded fight (233 samples, 12 s of real play, in
`tools/sample_motion_guided.log`) decides it without another test:

| | head speed | Set A (`W_REF` 1.2, γ 1.3) | Set B (`W_REF` 1.9, γ 1.8) |
|---|---|---|---|
| median | 3.4°/s — standing still | 0.05 | 0.05 |
| p75 — ordinary scanning | 42.6°/s | **0.53** | **0.20** |
| p90 | 78.9°/s | 1.00 (saturated) | 0.57 |
| p99 — a snap | 133.8°/s | 1.00 | 1.00 |

Under Set A a merely deliberate look runs the world at half speed and the top decile of frames is
already saturated: reading the room is taxed, which is the one thing the design exists to allow.
Set B charges that look 0.20 and still reaches 1.0 on a real snap. **Ship Set B as the default**,
keep `KNEE A / B` in the lab, and let the owner overrule it by feel.

Reproduce: `tools/.venv/bin/python tools/analyze_knee.py <motion.log>`.
