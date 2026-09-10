# THE TIME LAW — the authority

> **BUILD STATUS 2026-09-10.** §1.2 is **shipped**: one floor for the whole game, `Clock.FLOOR_STILL
> = 0.03`, difficulty-independent, and `Boxer.floorNow()` is now two lines (−1 through a knockdown,
> the floor everywhere else). Measured on the glasses that afternoon a still player mid-round reads
> `ts=0.06`; the build this document was written against could not go below 0.35. **Everything else
> here is still specification**: §1.3's axis split, §5's forced rate, §6's ×1.8 re-derivation and
> §8's legibility work are not in the code, and the hang and the fuse were NOT deleted — they still
> run on real time, they simply no longer touch the floor.

> **This document outranks DESIGN.md §2 wherever they differ.** §2 described a floor handed to the
> boxer's state (0.35 idle, a 0.8 s hang at 0.06, a 1.2 s fuse ramping to 0.60). That design was
> built, played on the owner's head, and produced the verdict this document exists to answer:
> *"i dont really see the superhotvr motion mechanics."* He was right, and the reason is
> arithmetic, not taste. DESIGN.md §1 and §2 now defer here; MOTION.md still outranks everything on
> what the BODY reads, because those numbers were measured on his head and these were not.

---

## 0. THE RULING, AND THE ONE NUMBER THAT CAUSED IT

`Boxer.FLOOR_IDLE = 0.35`.

The world's clock never ran slower than 35 % while the opponent was idle, which is most of a round.
The deep floor (0.04–0.10) applied only during a tell's *hang*, and a 1.2 s fuse then ramped it to
0.60 — **on real time, so the player could not affect it.** Simulated over the Rooster's own R1
phrase A for a player who never moves at all, the measured ratio is

```
worldT / realT = 0.230
```

A statue's world ran at nearly a quarter speed. SUPERHOT's runs at about a twentieth
([the accessibility notes describe rest as "time will pass extremely slowly… until you use a
movement action"](https://gameaccess.info/superhot-non-vr-controls-walkthrough/); the community's
estimate is ≈ 0.05, and the studio shipped an unlockable FULLSTOP at exactly 0.00). SUPERHOT VR's
designer states it flatly: *"when you stop, it completely freezes."*

So we shipped **slow motion with variations**, which is a filter, where the design promised
**stopped**, which is a mechanic. The perceptual line between them is roughly whether a held
animation frame reads as a *held frame*: a 12 fps strip needs its frame to persist for about a
second of real time before the eye calls it frozen, and at 0.35 our frame held for 0.24 s. At the
floor this document sets, it holds for **2.8 s**. That is the whole difference between "the game is
running slowly" and "I stopped time."

Three further defects fell out of the same measurement and are corrected below: the hang and the
fuse burned on **real** time, so the read was on a wall clock (§2); the deliberate dodge was
**punished** rather than rewarded, inverting the mechanic's central pleasure (§2.1); and the
player's own punch forced rate **1.0**, making the commonest action in the game a full-speed button
(§5).

---

## 1. THE LAW, AND EVERY CONSTANT IN IT

### 1.1 The formula

```
timeScale = floor + (1 − floor) × clamp(motion + act, 0, 1)          [UNCHANGED — Clock.update]

motion    = max(m_w, m_a) ^ GAMMA                                     [shape unchanged]
m_w       = clamp((ω_eff − DEAD_W) / (W_REF − DEAD_W), 0, 1)
m_a       = clamp((|a|   − DEAD_A) / (A_REF − DEAD_A), 0, 1)

ω_eff     = hypot(ω_pitch, ω_roll) + K_YAW × |ω_yaw|                  [NEW — §1.3]
floor     = FLOOR_STILL, always, for the whole fight                  [NEW — §1.2]
```

`act` is the action charge, capped at 1.0 and decaying on **real** seconds, exactly as today.
`motion` is smoothed toward its target with `ATTACK` rising and `RELEASE` falling, exactly as today.
**The law itself is not touched.** Every correction below is to what is written into
`Clock.floorOverride`, to what `ω` means, and to what the forced states force.

### 1.2 The floor — one number for the whole game

| constant | now | **ships** | unit | reason |
|---|---|---|---|---|
| `Clock.FLOOR_STILL` | — | **0.03** | fraction of real time | One floor, whole game. SUPERHOT ships ≈ 0.05 and offered 0.00; x3discs shipped 0.03–0.08 **on this hardware** and its reading game worked. At 0.03 a 12 fps strip frame holds **2.78 s** of real time (unambiguously a held pose) and his glove crawls at **7.2 cm/s** — visibly inching, so the picture is alive rather than hung. |
| `FLOOR_EASY / NORMAL / HARD` | 0.03 / 0.05 / 0.08 | **all → `FLOOR_STILL`** | | DESIGN.md §0.1 rule 1. Nobody gets a faster world for being on HARD. The difficulty rows keep every other lever they have (§7). |
| `LAB_FLOORS` | 0.03…0.20 | **0.00 / 0.02 / 0.03 / 0.05 / 0.08** | | Lab only, for the on-head A/B. 0.00 is in the ladder so FULLSTOP can be *tried*; it must not ship — with no depth buffer and additive strokes a perfectly static frame is indistinguishable from a crashed application, and every world-timed sound would stop dead instead of groaning (§9). |

`Boxer.floorNow()` is **deleted** and the boxer no longer writes the floor at all. `Fight.floorFor()`
becomes:

```kotlin
private fun floorFor(): Float = when {
    debugFloor >= 0f -> debugFloor
    state == State.FIGHT || state == State.KNOCKDOWN_COUNT -> -1f   // the LAW rules the fight
    else -> 1f                                                       // title, cards, corner, tally
}
```

**Do the one-line version of this first** — return `-1f` for `State.FIGHT` and change nothing else.
That alone drops the fight onto `Clock.baseFloor` and the mechanic appears on the owner's head in
one build. Get that verdict back before the rest of the surgery.

### 1.3 What "you moving" means on a 3-DoF rig — the axis split

The X3 Pro has no positional tracking. Head **rotation** is all we get, and it carries two
completely different meanings that must be charged differently. SUPERHOT's flatscreen rule is
explicit and is the one finding that matters most here: *"Aiming, however, will not speed up time,
giving you time to look around, plan your next move and aim."* What advances time is enumerated as
*"firing or throwing a weapon, punching, jumping or moving your position on foot"* — **acts and
translation, never orientation.**

On this rig the transposition is unusually clean, because DESIGN.md §1.3 and §3.3 have already
ruled that **a pitch IS a duck and a roll IS a slip**: the nod that aims at the body is the nod that
drops the collider, and the tilt that looks sideways is the tilt that moves it. Those two axes are
the body and must be charged at full weight. **Yaw is the only axis on this rig that is pure
camera** — a yaw moves neither the collider nor the aim, by the design's own ruling that "yaw does
nothing to a punch". So yaw, and only yaw, is discounted.

```kotlin
// MotionTracker.update — the gyro in the head frame: X is the ear axis (pitch), Y the neck
// (yaw), Z the look axis (roll). The magnitude `rawW` stays as it is, for the CLK line and the
// lab plate; it is no longer what the clock is charged on.
val angEff = hypot(rawWx, rawWz) + K_YAW * abs(rawWy)
```

| constant | now | **ships** | unit | reason |
|---|---|---|---|---|
| `K_YAW` | — | **0.15** | dimensionless | What a look costs, as a fraction of what a dodge of the same speed costs. At 0.15 the owner's own measured p75 scan (42.6°/s = 0.743 rad/s, MOTION.md "The knee") contributes ω_eff = 0.111 rad/s — **below the dead band, so looking is free.** The same 0.743 rad/s spent as a *slip* contributes the whole 0.743 and buys rate 0.18. Same head speed, two different meanings, correctly priced. A violent look-away (300°/s) still costs rate 0.20, so the player cannot teleport their attention for nothing. |
| `DEAD_W` | 0.08 | **0.12** | rad/s (6.9°/s) | At a 0.03 floor the difference between m = 0.00 and m = 0.02 is rate 0.030 vs 0.049 — 63 % more world for *breathing*. A wider dead band makes "still" read **exactly** at the floor, and that matters far more for presentation than for fairness: a rate that flickers makes the frozen image shimmer and the comic-panel brackets breathe. A 20° move at 6.9°/s takes 2.9 s, slower than any dodge in the game, so nothing real is lost. |
| `DEAD_A` | 0.25 | **0.35** | m/s² | The same argument for postural sway. A brisk step still peaks at 3–5 m/s². |
| `W_REF` | 1.9 | **unchanged** | rad/s | Measured on the owner's head (MOTION.md, Set B). MOTION.md outranks this document on body numbers. |
| `GAMMA` | 1.8 | **unchanged** | | Measured. |
| `A_REF` | 2.2 | **unchanged** | m/s² | Measured. A real sidestep should saturate, because a real sidestep is unambiguously the body moving. |
| `ATTACK` | 0.04 | **unchanged** | s | The instant answer IS the agency. |
| `RELEASE` | 0.16 | **unchanged** | s | The snap back to frozen IS the mechanic. Anything longer reads as input lag rather than as control. |

**The synchronisation hazard, restated because it is now worse.** `Clock.DEAD_W` / `DEAD_A` /
`W_REF` / `GAMMA` and `MotionTracker`'s copies duplicate the same arithmetic, and `Clock.update`
takes the *mapped scalar* without re-deriving it. A build that moves one and not the other ships
one curve while `Clock.target()` and the `CLK` line's `knee=` both claim the other. With the axis
split added there is a second half to the same hazard: `Clock.target()` takes a single angular
scalar, and that parameter now means **ω_eff, not |ω|**. Rename it, say so in its KDoc, and keep
`ClockTest`'s pinning cases pointed at the same arithmetic `MotionTracker` runs.

### 1.4 The quanta, the still flag, the rail marks

| constant | now | **ships** | reason |
|---|---|---|---|
| `PULSE_JAB` / `TAU_JAB`, `PULSE_HOOK` / `TAU_HOOK` | 1.0 / 0.20, 1.0 / 0.25 | **deleted, with the `tail` mechanism in `Clock.forcePunch`** | The follow-through pulse exists to keep the world running *after* the punch, which is precisely what stops it settling back to frozen. The punch's own forced window (§5) is the whole cost now, and the cost is a rate rather than a rail. |
| `PULSE_GUARD` / `TAU_GUARD` | 0.5 / 0.20 | **0.25 / 0.20** | Raising the guard is a small act and must stay one; at a 0.03 floor 0.5 was worth ten times what it was worth at 0.35. |
| `PULSE_EMPTY` / `TAU_EMPTY` | 0.2 / 0.15 | **0.10 / 0.15** | Same. A wasted tap must still be felt as a real one — ≈ 0.015 world seconds — and no more. |
| `WHIFF_EXTEND_T` | 0.15 | **0.22** | The mash tax has to survive the cheaper punch rate. At forced 0.55, 0.22 s of extension costs **0.121** world seconds where 0.15 s at rate 1.0 cost 0.150 — 81 % of the old tax recovered. Going further (0.28 s, a full match) was rejected: it would stretch the whiff animation's forced window past the punch itself and make a mistimed jab feel like a stumble. |
| `STILL_RATE` | 0.10 | **0.08** | It must sit above the floor plus residual noise (0.049 at m = 0.02) and below anything deliberate. |
| `HALF_MARK` | 0.5 | **kept** | The latency tick the owner watches. |
| `FREEZE_MARK` | — | **0.15** | New, and the more important of the two now: the crossing worth flashing is the one *into* the freeze. It drives the rail's second tick, the comic-panel brackets (§8) and the retargeted `Sfx.HANG` (§9). |

---

## 2. WHAT IS DELETED, AND WHY EACH ONE HAD TO GO

| deleted | why |
|---|---|
| `Boxer.FLOOR_IDLE = 0.35` | §0. It left the player's motion only 65 % of its authority in the phase that occupies most of a round. |
| `Boxer.FLOOR_DEEP`, `FLOOR_FUSE_TOP`, `FLOOR_OPEN` | The floor is not a function of the opponent. DESIGN.md §0.1 rule 1. |
| `Boxer.HANG_T`, `HANG_LAB`, `hangLeft` | **Standing still IS the hang, and it lasts exactly as long as the player wants it to.** A hang is what you build when your floor is too high to give a read away for free; once the floor is 0.03 it is a second, redundant, hidden clock. |
| `Boxer.FUSE_T`, `fuseLeft`, `fuseBurned`, `burnHangAndFuse`, `Listener.onFuseBurned` | The fuse is a **real-time timer that raises the floor behind the player's back**, which is the precise thing DESIGN.md §0.1 rule 4 forbids. Its job — "he must eventually reach you" — is done honestly in §4. |
| `Boxer.floorNow()`, `Boxer.stillInFuse` | The boxer no longer writes the floor. |
| `Fighter.hangMul` | The card's cruellest number was "how long you are given to think", and nobody owns that any more. CARD.md replaces the column with a commitment lever. |
| `Fight.ROUND_REAL_CAP_S`, `Fight.realCapped` | A hidden real clock that slammed the floor to 0.35 for the last third of the round. At the measured still-player mean of 0.230 it bit at **world second 41 of a 60-second round** — guaranteed to fire against exactly the careful player the design wants. The legitimate intent behind it (a round should not run forever) is answered by §7, not by the floor. |
| `Clock.Forced.STRIKE` | Dead since the owner's ruling that every punch travels on world time. Delete the state; a `forced=STRIKE` in a `CLK` line then cannot mean anything. |
| `Fight`'s stall response for R3 (`beginFeint(..., realTime = true)`) | The only place in the fight where a **hostile action** ran on real time. See §4.3. |

### 2.1 The defect the deletions fix, in numbers

Under the shipped law, during a strike whose fuse had burned (floor 0.60), a 20° slip:

| slip | ω (rad/s) | motion | shipped rate | world advanced | outcome vs a 0.25 s strike |
|---|---|---|---|---|---|
| snap, 0.25 s | 1.40 | 0.56 | 0.82 | 0.21 s | just safe |
| easy, 0.50 s | 0.70 | 0.14 | 0.66 | 0.33 s | **HIT** |
| slow, 0.90 s | 0.39 | 0.04 | 0.62 | 0.55 s | **HIT** |

Every dodge slower than a snap got you hit, and it got you hit *because the floor ran the world
while you were being careful*. SUPERHOT's central pleasure — the slow dodge is the safe dodge — was
not merely absent; the sign was reversed. Under this document's constants, with the axis split:

| slip | ω_eff (rad/s) | motion | rate | world advanced | outcome |
|---|---|---|---|---|---|
| snap, 0.25 s | 1.396 | 0.550 | 0.563 | **0.141 s** | safe |
| easy, 0.50 s | 0.698 | 0.132 | 0.158 | **0.079 s** | safe |
| slow, 0.90 s | 0.388 | 0.033 | 0.062 | **0.056 s** | safest |

The slow dodge spends **2.5× less world** than the snap. The sign is correct, and that single
inversion is probably what the owner felt in his hands.

---

## 3. THE STILL PLAYER, SECOND BY SECOND

Round 1, NORMAL, `FLOOR_STILL` 0.03. The Rooster throws **LEFT PECK** (`#1`): tell 0.50 world s,
strike 0.25 world s, recover 0.50 world s; the strip is `peck_l`, 6 tell frames at 12 fps. The
player is standing perfectly still, hands at the temples, and does not move.

| real t | world t | the world | what he sees and hears |
|---|---|---|---|
| **0.00** | 0.000 | rate 0.03 (it was already 0.03: he was still) | The tell's first beat has to be carried by the picture, because the floor no longer changes. LEFT pupil WHITE, LEFT glove WHITE-hot, the shoulder dips, the head tilts to his right. `TELL_PECK_L` plays at **pitch 0.515** — a cluck stretched into a groan (§9). The comic-panel brackets fade in over 200 ms of real time, because for the first time this round there is something to read (§8.4). |
| 0.00 → **2.78** | 0.000 → 0.083 | strip frame 0, held | **One drawing, on the glass, for 2.78 seconds.** Not a slow animation — a held frame. Meanwhile his own gloves at the bottom of the plate bob ±5 px at 0.45 Hz on the real clock at 60 Hz, and the head-tracked presentation never stops. That separation — *my hands are at 60 Hz, the world is stopped* — is the entire subjective content of the mechanic. |
| 2.78 | 0.083 | frame 1 | One 12 fps step every 2.78 real seconds. The telegraph is a comic panel that turns a page every three seconds. |
| **16.67** | 0.500 | TELL → STRIKE | The glove leaves. `EXTEND` at pitch 0.515. The arc's bead begins to slide. The REFLEX rail — now time-to-impact on the committed glove, on the world clock (§8.6) — begins to drain at 3 % of its rate. |
| 16.67 → **25.00** | 0.500 → 0.750 | the glove crosses ≈ 0.60 m at **7.2 cm/s** | Ten trail ghosts are sampled every 1/30 of a **world** second — that is once every 1.1 real seconds — and fade over 0.4 world seconds, i.e. 13 real seconds. So the glove hangs in the air with an **80 cm luminous arc behind it that hangs with it.** A frozen glove with a tail says *this was fast and it is now stopped.* A frozen glove without one is a dot. This is the sentence the whole mechanic is trying to speak, and today the renderer deletes it exactly here (§8.1). |
| **25.00** | 0.750 | contact | The collider is still on the line. **HIT: 8 damage.** |

**He hits a statue at 25 real seconds of doing nothing, not at 2.5.** Under the shipped law the same
punch landed at **2.51 real seconds** whatever the player did, because the hang and the fuse burned
on a wall clock: 0.8 s of hang at 0.06, then a 1.2 s ramp to 0.60, then the strike at 0.60. The
frozen moment was a scheduled 0.8-second dip, not something stillness earned.

**Now the dodge.** At real t = 17.0 — 0.3 real seconds into the strike, with 0.241 world seconds of
glove travel left — the player slips 20° to his right over 0.9 real seconds:

- ω_eff = 0.388 rad/s (all roll; the yaw term is zero) → motion 0.033 → **rate 0.062**
- the slip advances the world by **0.056 world seconds**
- the glove is still **0.185 world seconds** from contact when the collider is clear

He is safe with an enormous margin, and *his move threw the punch* — the glove visibly surges as he
leans and settles again as he stops. Had he snapped the same 20° in 0.25 s he would have spent
0.141 world seconds and still been safe, but he would have given the glove **2.5× more of the
distance**. Deliberation pays, and it pays visibly.

**What a still player cannot do is win.** Nothing he wants happens while he is frozen: the round
clock does not advance, his score does not accrue, the opponent's HP does not move, and the fight
does not end. He is not invulnerable — the glove already in flight is deferred, not defused, and the
only way off its line is a movement, and that movement is exactly what brings it. This is SUPERHOT's
own structural answer and it needs no enforcement.

---

## 4. HOW THE FIGHT PRESSURES A STATUE — honestly, and where he can watch it arrive

DESIGN.md §0.1 rule 4 grants exactly one exception: *a boxer must eventually reach you or there is
no fight.* Three pressures answer it, and none of them is a number the world clock adds while
nobody is looking.

### 4.1 The committed glove IS the bullet — so throw more of them

A bullet is a **committed** threat: fired, irrevocable, on a line, with a legible time-to-impact.
A boxer spends most of his time **uncommitted** — circling. In SUPERHOT's terms a circling boxer is
an enemy who has not yet fired, and such an enemy is *genuinely harmless* to a frozen player. That
is correct and must be allowed.

What must change is **density**. Once a tell completes and the strike launches, the glove is on
world time, aimed at a specific point on the collider, and it will not go away. **A frozen player
should almost always be looking at at least one committed glove.** Under a deep floor an opponent
who rarely commits produces an *empty frozen frame*, and an empty frozen frame is boring — which is
a different way of being invisible, and it is why the Rooster gets easier before he gets harder if
this lands late.

The metric, and it is the one CARD.md now carries instead of `hangMul`:

```
COMMITMENT = (world seconds with a TELL or a STRIKE on the glass) / (world seconds in the round)
```

Measured on the shipped Rooster's round 1: **35 %.** Target: **≥ 40 % in R1, ≥ 45 % in R3.**
CARD.md §"THE ROOSTER, MADE A FIGHT" has the phrase-by-phrase arithmetic that gets there, and it
gets there by cutting neutral waits and adding attacks — never by shortening a tell.

### 4.2 Freezing is self-defeating, and for a boxer it is also fatal

The round clock is world seconds (§7). A frozen player's round never ends, he lands nothing, scores
nothing and wins nothing — and unlike a SUPERHOT camper he is also being hit by every punch on the
card, because a statue's collider never leaves a line. Sixteen unanswered attacks in a round 1 that
averages 12 damage apiece against a 100 HP player is a knockdown ladder and then a TKO. **The fight
ends; the statue loses.** That is a complete answer and it requires no anti-camping machinery.

### 4.3 The stall — the one hostile clock that stays real, and why that is honest

`Boxer.stall()` counts real seconds of stillness and answers with the crowd's boo (R1), the amber
crest and a 20 % shorter next tell (R2), and a half-peck (R3). Two rulings:

**It stays on REAL time.** The obvious correction — move `STALL_T` to world seconds — would delete
the mechanism, because 3 world seconds at rate 0.03 is 100 real seconds and the boo would never
arrive for the player it exists for. It stays real because DESIGN.md §0.1 rule 3 explicitly permits
it: *"the crowd, the shortening tells… those are the boxer and the referee applying pressure where
the player can watch it arrive."* The crowd is already declared to be on the real clock (§2.9: its
loudness is the rate meter).

**What makes it legal is the gate that is already in the code:** `stall()` returns immediately
unless `phase == Phase.IDLE`. **A player reading a committed telegraph is never on a real clock.**
The stall clock runs only while there is nothing to read — i.e. only against camping in neutral,
never against a read. That is the line, and it must not move.

Two changes so it is *watched* rather than sprung:

- The amber crest comes in from **round 1**, not round 2, and it comes in over the last real second
  before the response fires — so the answer is visible before it lands.
- **R3's forced real-time half-peck is deleted.** It is the only hostile *action* in the game that
  ran on real time, it is a strike the player cannot slow, and it is indefensible under rule 4.
  R3's stall answer becomes the R2 answer with a 30 % cut instead of 20 %, which is his tell, on his
  clock, and readable.

`STALL_T` stays `[3, 3, 2]` real seconds.

### 4.4 What was considered and declined: the step-in

A step-in — every N world seconds of idle stillness he takes an announced 0.35 m step forward — is
the tidy textbook answer to rule 4, and it is **declined**. `Boxer.X` and `Boxer.Z` are compile-time
constants (`0f`, `−2.6f`); the five attacks' bands are authored as absolute heights and reaches from
a fixed distance; the renderer places him from those constants and the billboard's yaw is derived
from them. Moving him is a change to the collider geometry, the band table, the arc's aim and the
sprite's placement, for a pressure §4.1 and §4.2 already carry. **Hold it in reserve.** If the
on-head verdict is that a statue is too safe, this is the lever to reach for — not the floor.

---

## 5. THE FORCED MOMENTS — a rate, not a rail

`Forced` splits into two kinds, and the split is the point:

```kotlin
// FLOOR-type  — the body's own act. The law may still be heard inside it:
//     timeScale = max(lawRate, forcedRate)
// CEILING-type — cinema. The law may not exceed it:
//     timeScale = forcedRate
```

A FLOOR-type window still does the only job forcing was ever for: **it makes a delicate tap and a
slap cost the same.** On the owner's recorded run a delicate cut-tap drove `motion` to 0.29 and a
slap with the arm coming up drove it to 0.96 for ≈ 0.8 s; a forced *floor* removes that unfairness
without also removing the player's own motion from the answer.

| state | kind | now | **ships** | duration (real) | reason |
|---|---|---|---|---|---|
| `PUNCH` jab | FLOOR | 1.0 + tail | **0.55** | 0.28 s | 0.154 world s against 0.48 today — **3.1× cheaper**. The world visibly leans in as you throw and settles back the moment you stop. At a 0.03 floor that is an 18× contrast, which is more legible than 1.0 ever was, not less. |
| `PUNCH` hook | FLOOR | 1.0 + tail | **0.65** | 0.36 s | 0.234 world s (2.6× cheaper). The heavier hand costs more world, as it should. |
| `PUNCH` special | FLOOR | 1.0 | **0.90** | 0.60 s | 0.54 world s — the biggest honest cost in the game, and the one earned near-full-speed moment. It should feel like the world catching up all at once. |
| whiff (air or his guard) | — | +0.15 s | **+0.22 s** at the same rate | | the mash tax, preserved in *world* seconds (§1.4). |
| landed punch | — | `cutForced(0f)` | **unchanged** | ends at the contact frame | landing stays cheaper than missing: ≈ 0.083 world s at the jab's rate. |
| COUNTER (inside the 0.6 s window) | — | window waived | **unchanged, and now spectacular** | | at a 0.03 floor the counter lands in a genuinely frozen world. This is the money shot: make sure the hit-stop, the white core and the trail sell it. |
| `STEP` | FLOOR | 1.0 | **0.70** | `stepT` 0.35 s | a real physical sidestep saturates the law anyway, so a FLOOR-type window costs a real stepper nothing and only equalises the pad-swipe step. |
| `CORNER` | FLOOR | 1.0 | **1.0** | `cornerHold` | between rounds, outside the read. FLOOR and CEILING are the same thing at 1.0; it is typed FLOOR for consistency. |
| `GETUP` | CEILING | *(fell through to 0.35)* | **0.60** | the `getup` strip's length | **new, and necessary.** With the phase floors gone, a still player would watch him rise over 22 real seconds. It is a story beat, like the count. |
| `HITSTOP` | CEILING | 0 | **unchanged** | 70 / 110 / 130 / 200 ms | the 1984 impact frame. The render loop never stops — only the clocks. |
| `SLOW` | CEILING | 0.22 / 0.10 | **unchanged** | 1.1 s / 2.0 s | the fall is cinema. |
| `COUNT` | CEILING | 0 | **unchanged** | ≤ 10 s | the referee is outside the bubble. |
| `MENU` | CEILING | 0 latch | **unchanged** | latch | outside the fiction. |
| `STRIKE` | — | dead code | **deleted** | | his glove travels on world time (the owner's ruling). |

Net: **the only things that still run the world at ≈ 1.0 are the corner slide between rounds and
the special.** Everything else is the player's own body.

**One interaction to re-test together.** A player who punches *while moving hard* now gets the law's
rate rather than the forced 0.55, so their whiff costs more world than a still player's whiff. That
is correct — they moved — but it meets DESIGN.md §4.2's rule that a punch thrown at `m > 0.6` may
whiff past his head, and the two must be measured on the same run.

---

## 6. EVERY WORLD-TIMED WINDOW MUST BE RE-DERIVED — the divisor is 1.8

This section is the one most likely to be skipped and the one most likely to break the fight.

DESIGN.md §2.2's promise about the opponent's openings is that they are **"measured in your punches,
not in seconds"**, and BOXER.md §5 states the stagger as *"about five taps, seven if you're clean."*
Those are statements about a *ratio*: the window's world seconds divided by what a landed punch
costs in world seconds. A landed punch is cut at its contact frame, 0.15 s of real time:

```
landed punch, today  =  0.15 s × 1.00  =  0.150 world s
landed punch, ships  =  0.15 s × 0.55  =  0.083 world s        divisor = 1.82
```

**If the windows are left alone, every opening yields 1.8× more punches and the Rooster becomes
dramatically easier at the same time as the owner asked for him to be harder.** Divide:

| window | now (world s) | **ships** |
|---|---|---|
| `Boxer.STAGGER_T` (R1/R2/R3) | 0.90 / 0.70 / 0.55 | **0.50 / 0.39 / 0.30** |
| `Boxer.STAGGER_CAP` | 1.60 / 1.20 / 0.90 | **0.88 / 0.66 / 0.50** |
| `Boxer.STAGGER_EXTEND` | 0.12 | **0.066** |
| `Boxer.GUARD_OPEN_BODY` (R1/R2/R3) | 0.60 / 0.50 / 0.40 | **0.33 / 0.28 / 0.22** |
| `Boxer.GUARD_OPEN_RECOVER` | 0.20 | **0.11** |
| `Boxer.GUARD_OPEN_COUNTER` | 0.60 | **0.33** |
| `Boxer.GUARD_OPEN_SPECIAL` | 0.80 | **0.44** |

Check: the R1 stagger at 0.50 world s divided by 0.083 world s a tap is **6 taps** — which is what
BOXER.md §5 has claimed all along, and which today's numbers also produce. The invariant is
preserved exactly; only the units moved.

**What does NOT get divided, and why:**

- `Fight.HEART_REFILL_T` (1.0 world s) stays. Its job is to bound the *sustained whiff rate per unit
  of world time*, and that budget should not change because punches got cheaper — if anything the
  cheaper punch makes an unchanged refill slightly stricter, which is the right direction for a mash
  tax. A player who has burned three hearts gets them back by **moving**, which is the game.
- Attack tells, strikes and recovers stay exactly as authored (BOXER.md §3). They are the teaching
  and the drawing, not a punch budget.
- `Boxer.PHRASE_GAP` and the pattern's `Wait`s are handled in CARD.md as a design change, not an
  arithmetic one.

**And the whole §2.9 audit list should be re-read once with a 0.03 floor in mind.** Any timer that
*feels* wrong at 25× is probably on the wrong clock. The two that were checked and are correct as
they stand: the guard's re-close (it is meant to be spent in punches) and the feint frames (they are
the drawing).

---

## 7. THE ROUND'S WORLD BUDGET — the finding the numbers force

At a 0.03 floor, **the round clock is no longer a clock; it is the player's world budget**, because
the only thing that advances it is the player acting or moving. This has a consequence nobody has
priced, and it is large.

A round is `ROUND_WORLD_S` = 60 world seconds. A round played the way the game teaches — long frozen
reads, small deliberate dodges, punches taken in the openings — produces a mean rate somewhere
around 0.10–0.15 once the axis split has removed the tax on looking. Sixty world seconds at 0.12 is
**500 real seconds — over eight minutes for one round, twenty-five for a fight.** That is not a
boxing round, it is not shippable on a head, and DESIGN.md §14 (the neck, the arms, the frame on the
nose) rules it out on its own.

`ROUND_REAL_CAP_S` was the previous answer, and it was the wrong one: it bought a short round by
raising the floor behind the player's back. The right answer is to **spend the budget, not the law.**

| constant | now | **ships** | reason |
|---|---|---|---|
| `Fight.ROUND_WORLD_S` | 60 | **36** | Not an arithmetic guess: 36 world seconds is exactly **the Rooster's opening phrase plus one full lap of his four-phrase rotation** under CARD.md's new timings (34.2 world s), so the bell falls within two world seconds of a phrase boundary and the round is a *memorable unit* — you see each phrase once. At a mean rate of 0.15 that is 4:00 real; at 0.20 it is 3:00. |
| `Fight.TIME_BONUS_S` | 180 (real, `fightRealT`) | **108 (world, a new `fightWorldT`)** | Three full rounds of 36. The bonus then reads as *"the fraction of the sanctioned world time you gave back"* — a reward for economy of movement, which is the thing the game is teaching. Scoring on real seconds is a hidden punishment for playing it correctly, and at any deep floor it is structurally zero for the intended player. `fightWorldT` is a new accumulator (`fightWorldT += clock.wdt`), because `Clock.worldT` resets every round. |
| `Fight.CLAPPER_FROM_S` | 10 | **8** | The wood block on each of the last N world seconds, kept proportional. |

**36 is a starting value with a measurement attached.** The acceptance test (§11) reports
`wt/rt` per round; set the shipped number from it:

```
ROUND_WORLD_S  =  target_real_seconds × measured_mean_rate          target_real ≈ 165 s (2:45)
```

Move that number, never the floor. And note that the plate draws the round clock through
`clockText()` as `m:ss`, so it will now read `0:36` — either accept that (an arcade cabinet's round
clock has never been real seconds; the 1984 machine's was not either) or draw it as a plain count.
One line in `Hud`, and the owner's call.

---

## 8. MAKING THE FROZEN STATE LEGIBLE — the eye

Half of *"I can't see the mechanic"* is §1. The other half is that at present **nothing on the glass
changes appearance as a function of the clock except a bar.** Six changes, in value order.

### 8.1 Trails must persist THROUGH the freeze — invert `trailsScene()`

The highest-value single change in this document. Today:

```kotlin
val k = (rate / 0.35f).coerceIn(0f, 1f) * fightDim
if (k <= 0.02f) return                                   // <- deleted at the floor
val reach = if (rate > 0.7f) trailN else min(trailN, 3)  // <- shortened at the floor
```

…and the KDoc says it out loud: *"speed lines with zero authoring, gone when time freezes."* That is
exactly inverted from SUPERHOT, where the trail is **most** important when frozen, because the trail
is the only thing that makes a frozen projectile legible.

- **Delete the `k = rate/0.35` fade and the `reach` shortening.** Fade by **age in world seconds**
  instead, so a frozen trail hangs and a moving one is short.
- Keep sampling on **world** time. This is load-bearing and is the opposite of the intuition: with
  world-time sampling the ghost *spacing* is constant in world space (8 cm at the glove's rate-1
  speed of 2.4 m/s), so the arc has the same length at every rate and simply **hangs when the world
  hangs**. Sampling on *real* time would pile every ghost on the same point the moment the world
  froze — the glove moves 0.24 cm between real-time samples at rate 0.03 — and there would be no
  tail at precisely the moment the tail is the whole point.
- `TRAIL_N` **4 → 10**, `TRAIL_DT` stays 1/30 world s, `TRAIL_LIFE_W` = **0.4 world s**. Ten samples
  cover 0.33 world seconds — the whole of a 0.25 s strike — as an **80 cm luminous arc**, which at
  the floor hangs on the glass for 13 real seconds.
- Colour: the tail in the glove's own saturated hue at α 0.45 falling to 0.05, and the **head of
  the trail at α 1.0 through the ordinary wide-then-core pair** — which is exactly how every other
  stroke in the game gets its bright core, and it costs nothing new. **Do not try to make the head
  white with α > 1.** Post-`b44b86d` the fragment is `fragColor = vec4(vColor.rgb, a)` and the
  blend is `SRC_ALPHA, ONE` onto an RGBA8 surface, so the fragment's alpha is **clamped to 1.0
  before blending**: α 1.5 and α 1.0 are the same pixel. A stroke that must read WHITE is drawn
  with a WHITE **tint** (DESIGN.md §7.1, whose conclusion is right even though its premise about
  premultiplication is now stale). What *is* true and newly useful is that the ladder in α is
  **linear** now rather than squared, so these numbers mean what they say — and any alpha carried
  over from a pre-`b44b86d` experiment must be re-derived, not reused, because it was tuned against
  a curve that no longer exists.

### 8.2 Particulate that hangs

- **Sweat.** On every landed punch and every hard hit, 6–10 points flung from the head or body
  marker, advancing on `wdt`, life **2.0 world seconds**. At the floor they hang for over a minute.
  A dozen glowing motes motionless in the room is the cheapest and most legible "time is stopped"
  signal an additive waveguide can draw, and it is ≈ 20 vertices. **Cap the pool at 24 points** and
  recycle the oldest, or a long frozen exchange accumulates a galaxy.
- The existing 8 crest sparks and the impact stars run on **real** time today. Split them: the
  *flash* stays real (it is the plate's feedback, and it belongs to the player), the *debris* moves
  to `wdt`.

### 8.3 Keep the player at 60 Hz, loudly

At a 0.03 floor the world image is nearly static, and on a waveguide **a static image is
indistinguishable from a hung application.** What says "the app is alive, the WORLD is stopped" is
the real-time layer:

- `Hud.GLOVE_BOB_PX` **3 → 5**, `GLOVE_BOB_HZ` **0.4 → 0.45**. Verify on-head that it is actually
  visible; this is the single cue that converts "frozen" from "crashed" into "powerful".
- Head-tracked presentation never stops (already ruled). The HUD, the meter's pour, the crowd's
  loudness, the announcer and the music all continue. **Never stop the render loop, even in
  `HITSTOP`.**

### 8.4 The frozen frame becomes a comic panel — and this is also the answer to the cel-shading note

The owner's request for *"cell shading effects to give them more comic book color"* and his
complaint about the time mechanic have one shared answer: **the world becomes a comic panel when it
freezes and a smear when it runs.**

- rate < `FREEZE_MARK` (0.15): contour weight up, hatch/halftone gain to full, the four MAGENTA
  panel brackets in (they already exist in `Hud.kt`).
- rate > 0.7: contours thin, hatch off, trails long, crowd sway at full amplitude, the idle sway
  doubled (DESIGN.md §7.5 already specifies this half).
- **Gate the brackets on there being something to READ**, or they become wallpaper: brackets in only
  when rate < 0.15 **AND** a committed strike is in flight or a tell has passed its telegraph frame.
  Under the corrected law the frozen state is the *default*, and a marker of the default state
  carries no information. This gate is also what restores the telegraph's lost first beat: the floor
  no longer snaps from 0.35 to 0.06 when a tell begins, so the brackets coming in *is* that beat.

The flat-fill work itself is a separate piece with its own measurements. The one rule that binds it
to this document, because it is the rule that keeps DESIGN.md §7.2's *"exactly one thing is WHITE"*
alive at a deep floor: **when a part flashes WHITE, its outline goes UP and its fill goes DOWN
(× 0.25).** Measured, flashing the fill too turns a 1 723-px white tell into a 5 321-px white slab
and a head hit into a 7 961-px white egg; dimming the fill instead gives 1 548 px and reads as the
comic's impact panel. A frozen frame is on the glass for seconds, so a white slab that was
forgivable for two frames is not forgivable now.

### 8.5 The left rail

The fill is a sliver at rest, which reads correctly as "almost nothing". Add the second latency tick
at `FREEZE_MARK` 0.15, and change the `STILL` label from a violet warning to an affirmative
**`FROZEN`** — it is now the intended state, not a scold.

### 8.6 The REFLEX rail, repurposed

It currently drains through the hang and the fuse, both of which are deleted. It becomes
**time-to-impact on the committed glove**, on the world clock: how far his glove has left to travel.
That is SUPERHOT's bullet legibility rendered as an instrument, it is honest (it moves only when the
world does), and it gives the frozen frame a number. Use the same rail to draw the two **declared**
real-time timers — the 0.6 s counter window and the stall (§10) — so that every real clock left in
the fight is one the player can see.

---

## 9. MAKING THE FROZEN STATE LEGIBLE — the ear

The owner must be able to say when the world stopped **with his eyes shut** (TEST.md T5).

- **Pitch every hostile SFX with the clock.** `Sfx.play(id, pitch, vol)` already forwards to
  `SoundPool.play(..., rate)` and clamps to 0.5–2.0. Pass

  ```kotlin
  pitch = (0.5f + 0.5f * clock.timeScale).coerceIn(0.5f, 1f)
  ```

  for everything that belongs to the WORLD: `EXTEND` (his glove in flight), `STAMP`, `WHISTLE`,
  `TELL_PECK_L` / `TELL_PECK_R`, `GUARD_THUD`, `WHIFF`, `STUN_WARBLE`. At the floor these become
  half-speed groans; at rate 1 they are normal. **Player sounds, the bell, the crowd's loudness, the
  announcer, the referee and the MUSIC all stay at 1.0** — DESIGN.md §9.5's ruling that the music
  never slows holds, and the contrast between a brass chart at tempo and a world groaning underneath
  it is the two-clock promise made audible.
- **Widen the crowd** now that the clock has range. `Fight.crowd()` currently sends
  `level = 0.25 + 0.75 × crowdLevel` and `rate = 0.7 + 0.5 × crowdLevel`, which with a clock living
  between 0.35 and 1.0 was a 6 dB swing. Send **`level = 0.06 + 0.94 × crowdLevel`** (≈ 24 dB) and
  **`rate = 0.55 + 0.65 × crowdLevel`**. SoundPool has no filter, so bake the low-pass: generate a
  **second, darker crowd bed** in the existing synthesiser (the same `crowdbed` recipe with the saw
  partials removed and the noise shaped at 400 Hz) and crossfade the two on `crowdLevel`. A hush at
  0.03 and a roar at 1.0 is the owner hearing the world stop.
- **Retarget `Sfx.HANG`.** It fires today on entering the tell's hang, a state that no longer exists.
  Fire it instead on the rate **crossing down through `FREEZE_MARK` having been above `HALF_MARK`** —
  the sound of the world freezing — rate-limited to one per 1.0 real second. Under the corrected law
  that fires dozens of times a round, which is exactly how often the player should be told the
  mechanic is theirs.

---

## 10. THE DECLARED CLOCKS — the complete list of real time left in the fight

After this document there are exactly **two** real-time timers that can affect the outcome of a
fight, and both are drawn:

| timer | length | why it is legal | where it is drawn |
|---|---|---|---|
| the COUNTER window after a PERFECT dodge | 0.6 s (0.8 EASY / 0.45 HARD) | It is a *reward* the player opened, not a pressure applied to them. The read is slow; the answer must be fast, and that contrast is the loop the design sells: HANG (read) → MOVE (dodge) → 0.6 s (hit) → HIT-STOP. On world time a still player could stand in a stagger for a minute and the fight would be gone again. | the REFLEX rail (§8.6) |
| the STALL, while he is IDLE only | 3 / 3 / 2 s by round | DESIGN.md §0.1 rule 3 — the crowd and the fighter answering a statue, where the player can watch it arrive. It never runs during a tell or a strike, so **a read is never bounded by real time.** | the crowd's rising murmur, then the amber crest, before anything fires |

Everything else on the real clock is the player's, the plate's, the referee's or the story's, exactly
as DESIGN.md §2.9 lists it: the forced timers, the glove animation, the flash ramps, the meter's
pour, the bell, the announcer, the corner, the count, the music. **`TwoClocksTest` should gain a case
that fails if any *hostile* timer takes `dt`**, with these two as its declared exceptions.

---

## 11. THE ACCEPTANCE TEST — the numbers a log line can show

**The owner can already read the headline number off the glass**, which is worth knowing before
anyone reaches for logcat: `GLRenderer` line 1038 draws `m.roundClock` (the world clock, counting
down) beside `m.realLine = "REAL " + clockText(fightRealT)`. Both clocks are on the plate at once,
so `wt/rt` is a glance, and T4 below can be answered standing up without a cable.

For the numbers, add `wt=` (`worldT`) and `rt=` (`realT`) to the `CLK` line, and `we=` (ω_eff)
beside the existing `|w|=`. The line becomes:

```
CLK |w|=0.74 we=0.11 m=0.00 act=0.00 rate=0.03 floor=0.03 wt=12.40 rt=372.1 step=+0 blank=0 knee=B
```

Six assertions, five of them scoreable off that line alone:

| # | do | pass | today |
|---|---|---|---|
| **T1** | **The freeze exists.** Stand still, no forced state, 5 real seconds inside a fight. | `rate ≤ 0.05` on every line; `wt` advances by **≤ 0.30 s** | advances 1.2–1.8 s depending on which phase he happens to be in, which is itself the defect |
| **T2** | **Looking is free.** Scan the room at a comfortable speed for 5 s (the owner's own p75, 42.6°/s). | `rate ≤ 0.06`; `we` stays under `DEAD_W` | 0.18 at a 0.03 floor; invisible under the 0.35 floor |
| **T3** | **Deliberation pays.** Slip 20° over 0.9 s against a committed strike, and again over 0.25 s. | both survive, and the slow one advances **less** `wt` (0.056 vs 0.141) | the slow one is a guaranteed hit |
| **T4** | **The mean.** `wt/rt` over a full round played thoughtfully; then over a deliberately motionless minute. | **≤ 0.15** played, **≤ 0.06** motionless | **0.230** for a statue |
| **T5** | **The round is a round.** The same run's real elapsed time for one round. | **2:30 – 4:00**. Outside that, move `ROUND_WORLD_S = target_real × measured mean` (§7) — never the floor. | 4:21 at the measured statue mean, and the 3:00 cap fired first |
| **T6** | **Commitment.** `VERIFY him=` sampled at 5 Hz: world seconds in TELL or STRIKE over world seconds in the round. Needs no new telemetry. | **≥ 0.40** in R1, **≥ 0.45** in R3 | 0.35 in R1 |
| **T7** | **Audible.** TEST.md T5, eyes shut. | the owner can say when the world stopped | — |

**Unit tests that must be rewritten, not deleted.** `ClockTest` lines 31–43, 48, 62, 81, 109 and
170–174 all assert against the phase-floor design; they become assertions about `FLOOR_STILL`, the
FLOOR/CEILING split and the deleted `Forced.STRIKE`. `BoxerTest` loses its hang and fuse cases and
should gain one that fails if `Boxer` writes a floor at all. Add a `MotionTrackerTest` case for the
axis split: the same |ω| as pure yaw and as pure roll must produce rate 0.03 and rate 0.18.

**One measurement is missing and must be taken before `K_YAW` is trusted.** `MotionTracker.raw()`
logs `rollRate` but not the pitch and yaw rates, so the axis split **cannot be validated against the
owner's existing recorded run.** Add `rawWx`, `rawWy`, `rawWz` to `raw()`, take one fresh guided run,
and re-run `tools/analyze_knee.py` with the split in it. Until then `K_YAW = 0.15` is a reasoned
proposal bounded by two computable cases (a p75 scan as pure yaw = free; the same speed as pure roll
= rate 0.18) and nothing more.

---

## 12. RISKS

1. **The Rooster gets easier before he gets harder.** A deep floor hands the player unlimited read
   time and a cheaper punch. CARD.md's density work and §6's divisor must land in the **same build**
   or the owner's third note gets worse rather than better.
2. **An empty frozen frame is boring.** The frozen moment is only good if there is something in it.
   This argues for §4.1 and §8.2 over everything else in the sensory list.
3. **Rate flicker.** If a standing head's residual motion keeps the rate wobbling between 0.03 and
   0.06, the frozen image shimmers and the panel brackets breathe. That is what the widened
   `DEAD_W` / `DEAD_A` are for; check it on a recorded `motion.log` before trusting it.
4. **0.00 is available and tempting.** Keep it in the lab ladder only (§1.2).
5. **The fight's real length is now a shipped constant, not an emergent one.** Whoever moves
   `ROUND_WORLD_S` must re-read §7's arithmetic and CARD.md's phrase lengths together; they are one
   number expressed twice.
