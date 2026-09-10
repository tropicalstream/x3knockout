# THE ONE BOXER — ROY "THE ROOSTER" RUDD

> **AMENDED 2026-09-09 — the owner's ruling (DESIGN.md §0).** Every STRIKE time below is **world**
> time, not real time. His punches and yours both CRAWL when you are still and close when you move:
> the SUPERHOT VR mechanic is the game, and it does not get an exception for the moment that matters.
> Columns headed `(real)` in the first draft now read `(world)`.

> **AMENDED 2026-09-10 — `docs/LAW.md`, which outranks this file on the clock.** **He no longer
> writes the floor.** `FLOOR_IDLE`, `FLOOR_DEEP`, `FLOOR_FUSE_TOP`, `FLOOR_OPEN`, `HANG_T`,
> `FUSE_T`, `floorNow()`, `burnHangAndFuse()` and `Listener.onFuseBurned` are all deleted, and
> everywhere below that says "the hang and the fuse stretch the tell for a still player", read
> instead: **the player's own stillness stretches it, without limit, and nothing takes it back.**
> A tell authored at 0.50 world seconds costs a moving player 0.50 real seconds and a perfectly
> still one **16.7** — not the 2.1 the fuse used to impose. His numbers in §3 and §8 are unchanged;
> what changed is that they are now the only clock on him.
>
> Two consequences that are easy to miss and are worked through below: the **stagger and every
> guard-opening window shrink by 1.82** (§5), because a landed punch now costs 0.083 world seconds
> instead of 0.150 and those windows are measured in punches; and the tell's **first beat has lost
> its floor snap** (§3), because the floor no longer changes when he winds up — the picture and the
> panel brackets have to carry it alone. His **pattern is denser** (`docs/CARD.md`,
> "THE ROOSTER, MADE A FIGHT") and his **voice lines have moved** (`docs/VOICE.md`, which replaces
> §9 below).



The prototype's only opponent. Everything here is fitted to `docs/DESIGN.md` (the verbs, the time
law, the collider) and to what is measured on disk (`docs/MOTION.md`); numbers marked **(tune)**
are starting values for the lab. He is designed in the spirit of the 1984 arcade *Punch-Out!!*'s
opponents — one signature move on a schedule, a tell per punch, a body language that can be read
without a caption, an escalation that removes the easy tell — and he is legally distinct from all
of them: no rooster, crest or "rise and shine" exists in that cabinet, and the caricature is of
VANITY, never of a nationality (no flag, no accent, no origin line beyond a weight and a corner).

**Trademark rule.** The cabinet is cited in the docs. Nothing from it reaches the glass: not its
title, not a character name, not "WVBA", not its catchphrases. The strings the glass shows are the
ones in this file and DESIGN.md §8.

---

## 1. THE CARICATURE

**Who he is.** A vain, strutting show-off who winds up BIG because he is playing to the crowd —
which is why he telegraphs. Bantamweight in build, heavyweight in ego. He crows before his best
punch because he cannot help it. His weakness is the same as his tell: he wants to be watched.

**The look, designed for the surface** (black = the room, additive strokes, no depth, a 62° field,
the same image both eyes): a 2D stroke sprite, 1.9 m tall at 2.6 m, billboarded — DESIGN.md §7.3
has the pipeline; this is the face.

- **Head** 37 % of the figure: a wide bulldog-jawed oval, jug ears, a broken-nose zigzag, six short
  five-o'clock-shadow strokes on the jaw. Doubled magenta contour, skin hatched at 0.35.
- **Eyes**: two large white rings with magenta pupils. The whites are a telegraph surface: the
  pupils go WHITE before a punch (the cabinet's eye flash, in its own form); slits (two flat
  strokes) before a hook; both at once only before the uppercut.
- **Mouth**: one smug arc at rest; a rectangle when he crows; an O when he is hit in the body; a
  grimace with six teeth when he is hurt; the tongue out when he taunts and when he is out cold.
- **Brows**: single strokes whose angle is the whole expression — flat idle, V wind-up, inverted V
  hurt, raised taunt.
- **THE CREST**: a pompadour drawn as five tall spikes rising from the forehead, VIOLET at rest. It
  is his second telegraph surface (GOLD for a hook, fanned wide for a high one, DROOPED forward for
  a low one; WHITE, every spike straight up, for the uppercut; AMBER and dim when he waits you out)
  and his health readout: spikes DROOP as he takes damage and he LOSES one at each knockdown, so the
  crowd can read him and the player learns to. Damage removes strokes; it never dims to grey.
- **Gloves**: fat red pumpkins, r 0.24 m, tripled contour, laces, a thumb bump. The glove that is
  coming goes WHITE-hot for the whole tell and grows 1.8× on the extend — "the glove fills the
  eye" is the incoming cue that needs no depth buffer: bigger and brighter reads as nearer.
- **Body**: a tiny torso, CYAN trunks with a waist stripe, spindly legs, boots — the feet ARE
  drawn, because the hooks' tell is the feet.
- **Colours** (DESIGN.md §7.2): outline MAGENTA, gloves RED, crest VIOLET, trunks CYAN, eyes WHITE
  rings; the telegraph flashes are WHITE tints over the base hue; exactly one thing is white at a
  time.

**Framing.** World-locked at 2.6 m in the ring; he re-squares to the player's yaw over 0.3 s if
they look away and back ("you can't look away from him; he follows"). The lean's parallax (up to
12° of billboard yaw) is the felt dodge; the camera moves with the body.

---

## 2. THE PLAYER'S ANSWERS (legend for the tables)

**Lr** = slip right (lean right, `leanX ≥ +0.30`) · **Ll** = slip left · **Du** = duck (`duckAmt ≥
0.6`) · **Bk** = guard (right-pad swipe DOWN held, or the 0.5 s flick) · **St** = step (swipe L/R or
a real sidestep) · **any-slip** = Ll or Lr. Every dodge is the collider leaving the line, never a
threshold (DESIGN.md §3.3); the numbers above are where the collider clears the authored band at
DODGE SENSE MEDIUM.

- **CLEAN** = already off the line when the strike began: safe, `DODGE +50`.
- **PERFECT** = on the line at the first strike frame, off it at contact — a dodge inside his
  0.25–0.33 s strike: `PERFECT +300`, hearts refilled, +3 meter, and the 0.6 s counter window.
- **GLANCE** = half in: half damage, no knockdown check.
- Each attack has exactly ONE cheap answer, one or two safe-but-uncredited answers so a beginner
  survives while learning, and exactly ONE fatal answer so the lesson bites.

---

## 3. THE MOVESET — five attacks, five telegraphs, five answers

Times are ROUND 1 at rate 1.0 (a moving player); a still player's own stillness stretches them
without limit (LAW.md §3); §6 shortens them by round. The STRIKE is world time like everything
else. Damage is on an open target; GUARD and GLANCE rules are DESIGN.md §4.1.

**The tell's first beat.** It used to be the floor snapping from 0.35 to 0.06, and that snap had a
sound. There is no snap any more — the floor was already 0.03 — so the whole of "he has started" is
now carried by the picture: the pupil, the white glove, the shoulder, the strip's `telegraph`
frame, the attack's own SFX **pitched down to the clock** (LAW.md §9), and the comic-panel brackets
coming in, which are gated on exactly this (LAW.md §8.4). If a tell ever fails to read on-head,
that gate is the first thing to look at.

| # | name (his voice) | attack | TELL — the high-contrast telegraph | tell (world) | STRIKE (world) | band / what it hits | cheap answer | safe answers | fatal answer | RECOVER (world) |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **LEFT PECK** | left jab, straight to the head | LEFT pupil goes WHITE and stays lit; LEFT glove goes WHITE-hot; left shoulder dips; head tilts to HIS right. SFX: a short cluck (`TELL_PECK_L`) | 0.50 s (6 frames) | 0.25 s | a 0.24 m glove across 1.50–1.80 m at `headX ± 0.10` | **Lr** (lean AWAY from the lit glove). Bk absorbs: 2 dmg, no heart | Ll (dodges, but lands you where #2 comes from → the pattern answers with #2 on a 0.25 s tell); Du (a jab goes over: safe, no PERFECT); St | stand: **8** | 0.50 s |
| 2 | **RIGHT PECK** | right cross to the head | RIGHT pupil WHITE; RIGHT glove WHITE-hot; right shoulder dips; head tilts to HIS left. A cluck a fourth higher (`TELL_PECK_R`) | 0.50 s | 0.25 s | as #1 | **Ll**. Bk absorbs (2) | Lr → branches to #1; Du; St | stand: **10** | 0.55 s |
| 3 | **RIGHT WING** | wide right hook to the head | CREST flares GOLD and fans wide; RIGHT glove drops to his hip and goes WHITE-hot; he LOADS onto his right foot — the boot plants, the canvas line under it bows (`STAMP`: a low thud, felt as much as heard); BOTH eyes to SLITS — no pupil flash, which is how you know it is not a jab | 0.67 s (8 frames) | 0.33 s | a 120° sweep from the player's LEFT edge to centre at **1.55–1.85 m**, ±0.60 m — no slip clears it | **Du** (PERFECT if you drop during the strike → he over-rotates and STAGGERS) | St (clears it, no PERFECT) | Ll / Lr: **15** + a 0.3 s stun (the plate jitters 4 px); Bk: **crushed** — 7 dmg and the guard forced down 0.4 s | 0.83 s — he shows his back shoulder: the biggest R1 opening |
| 4 | **LEFT WING** | low left hook to the body (the ribs) | CREST flares GOLD but DROOPS forward (the "low" cue); LEFT glove drops BELOW the plate's edge (out of sight = low); he loads onto the left foot — the canvas bows on the left; pupils drop to the bottom of the whites. SFX: the stamp, then a rising whistle (`WHISTLE`) | 0.67 s | 0.33 s | a sweep at 1.00–1.35 m from the player's RIGHT, ±0.60 m; the capsule runs to 0.95 m below the eye, so no slip clears it, and a duck DROPS THE HEAD INTO it | **Bk** — a blocked body hook is a GUARD-COUNTER: his glove bounces, he is open 0.6 s world, `GUARD +150`; 3 dmg | St | **Du: 18** — the worst mistake in the set; it teaches "not everything is a duck". Ll / Lr / stand: **12** | 0.60 s |
| 5 | **THE SUNRISE** | uppercut from below | He CROWS: the mouth goes rectangular and `rise_and_shine` plays — the voice line IS the tell, from 0 % to 80 % of it; CREST blazes WHITE, every spike straight up; BOTH gloves drop out of frame, then the RIGHT rises from the bottom edge growing (the sun coming up); BOTH pupils WHITE — the only attack where both flash | 0.83 s (10 frames) | 0.33 s | a vertical column `headX ± 0.25`, 1.00 → 1.90 m: it catches a ducked head as surely as a level one | **any-slip** ≥ 0.30 m (PERFECT if the slip happens inside the strike → he whiffs sky-high and STAGGERS, arms in the air) | St. In R3 the column TRACKS `leanX` for the first 40 % of the strike → St, or a slip begun INSIDE the strike | **Du: 25** + a knockdown check; Bk: **25** (unblockable — his glove comes under); stand: 25 | 1.17 s — chin up, arms up: the opening that leads to a knockdown |

**The grammar embedded in the table** — the beginner's mnemonics, spoken once each by the corner:
- The EYES say jab vs not-jab: a pupil flash = a jab and which hand; slits = a hook; both = the
  uppercut.
- The GLOVE says which hand (it is the white one).
- The CREST says hook (gold) vs uppercut (white), and high (fanned) vs low (drooped).
- The STAMP says a hook is loading. The feet sometimes lie (R3's half-stamp); the crest never does.
- The VOICE doubles the uppercut, because a ducking or leaning head can carry the crest out of the
  62° field.
- "Lean off the lit glove. Gold crest, get low. Never duck the low one. When he crows, get off the
  line."

**Hit reactions on HIM** (his strips, world time, DESIGN.md §7.3's secondary motion on top):
- head punch landed: the head snaps 25° away from the hand (`hit_head`: eyes X, mouth gasp, ears
  flap, one crest spike wobbles, four sweat strokes); a WHITE ring expands 0.15 s from the impact.
- body blow landed: the torso folds, the mouth goes O, the gloves fly outward (`hit_body`) — the
  "guard opens" picture.
- COUNTER or the SPECIAL: the head spins (three keyframes), the crest spikes scatter as eight sparks
  flung outward fading 0.4 s, the crowd roars.
- blocked (his guard): his glove bounces, a cyan spark at the glove, `that_all` at most once per 6 s.
- STAGGER (§5): gloves at his hips, the head wobbling ±20° at 3 Hz, eyes as two stroke spirals,
  crest spikes crossed, tongue out; `STUN_WARBLE` loops.

---

## 4. THE GUARD, AND WHAT OPENS IT

Neutral stance: both gloves up covering the face, drawn overlapping his jaw — the additive overlap
makes the closed guard visibly BRIGHTER, which is the readable "closed" state for free. While it
is up, head punches do 0, cost a heart and the full whiff-length `Forced.PUNCH`, and draw a cyan
spark. The guard OPENS (DESIGN.md §4.3): on a BODY BLOW (**0.33 s** world in R1, **0.28 / 0.22** in
R2 / R3; a second body blow inside it → STAGGER), on a PERFECT dodge (for the whole recover), on a
GUARD-COUNTER (blocking #4, **0.33 s**), briefly (**0.11 s** world) at the end of every recover,
and by the SPECIAL (**0.44 s** world). It re-closes on WORLD time — on your own punches, and those
four numbers are the old ones divided by 1.82 for the reason set out in §5: they buy the same
**four punches** they always did, now that a landed punch costs 0.083 world seconds.

His health readout is the crest: 5 spikes at full, one lost per knockdown, the rest drooping with
HP (spike angle = 90° × HP / max, minimum 40°). His HP bar on the scoreboard says the same thing in
numbers.

The stun (DESIGN.md §4.6): a RIGHT thrown over his incoming LEFT PECK before its strike frame lands,
interrupts, and stuns him 0.6 s world (hands low, dazed eyes); each hit resets the stun's timer up
to 1.6 s; a body blow wakes him.

---

## 5. STAGGER — HE IS OPEN

**Entered by**: a PERFECT dodge of #3 or #5; two body blows inside one open-guard window; the
SPECIAL landing on an open guard; a blocked #4 followed by a body blow inside its window.
**Look**: gloves at his hips, head wobbling ±20° at 3 Hz (the secondary matrix), eyes replaced by
two 12-segment spirals, crest spikes crossed, tongue out — unmistakable at a glance. `STUN_WARBLE`
loops, **pitched with the clock** (LAW.md §9), so a frozen stagger is audibly a frozen stagger.
The floor does not change: it is 0.03, like everything else in the fight.
**Duration (world)**: R1 **0.50 s**, R2 0.39, R3 0.30; each landed punch EXTENDS it **0.066 s** up
to a cap (**0.88 / 0.66 / 0.50**). In punches: "about five taps, seven if you're clean." Measured
in your punches, not in seconds: a still player watching a stagger loses nothing and gains nothing.

> **Those numbers moved and the stagger did not.** A landed punch is cut at its contact frame,
> 0.15 s of real time, and its forced rate fell from 1.00 to 0.55 (LAW.md §5): 0.150 world seconds
> became **0.083**. Dividing the window by 1.82 keeps the stagger at **six taps**, which is what
> this paragraph has always claimed. Leave the old 0.9 s in and the stagger becomes eleven taps and
> the Rooster gets easier at the moment the owner asked for him to get harder. The same divisor
> applies to all four `GUARD_OPEN_*` constants in §4 — LAW.md §6 has the full table.
**Damage in stagger**: × 2. **A SPECIAL in stagger = a knockdown, and he does not rise** — "the
Wake-Up Call puts him to sleep" (the announcer's line).
**Leaving it**: he straightens with a shake (0.4 s world), guard up — and in R2+ his NEXT attack is
always #5, the sucker uppercut on the greedy player who kept punching after the wobble stopped.
The player who is still tapping as he straightens is exactly where the Sunrise wants them: upright,
centred, hands busy.

---

## 6. ESCALATION ACROSS THREE ROUNDS

| | R1 "THE STRUT" | R2 "THE RUFFLE" | R3 "THE COCKFIGHT" |
|---|---|---|---|
| tell length (world) | as §3 (peck 0.50 / wing 0.67 / uppercut 0.83) | × 0.75 | × 0.58 — the strip's tell segment plays faster; the strike segment always at 1× |
| strike (world) | 0.25 / 0.33 | −0.02 s | −0.05 s |
| recover (world) | as §3 | × 0.8 | × 0.65 |
| feints | none | **HALF-PECK**: the pupil flashes and the glove glows, but the glove stops at 30 % and never goes white — no strike. The tell of a feint: the pupil flashes only TWICE and the shoulder does not dip. If the player slipped, he throws the OTHER hand's peck with a 0.25 s tell into the side they leaned to | + **FALSE SUNRISE**: he crows "RISE—" and cuts off (`rise_cut`); the crest goes white then snaps violet; if the player slips he throws the WING into that side. + **HALF-STAMP**: the stamp without the crest — nothing follows; teaches "read the crest, not the feet" |
| tracking | none | none | the Sunrise's column follows `leanX` for the first 40 % of the strike; the answer is St, or a slip begun INSIDE the strike |
| new sequence | — | the 1-2: #1 then #2 with a 0.2 s tell on the second | the DOUBLE WING: #3 then #4 back to back (0.2 s tell on #4): duck, then come up into a guard — or one step clears both (`STEP +400`) |
| his HP at the bell | 120 | `max(current, 72)` | `max(current, 42)` |
| stall pressure (real time, while he is IDLE **only**) | the crest goes AMBER over the last second, then the crowd boos at 3 s | the same, and his NEXT tell is 20 % shorter | the same, at 2 s, and his NEXT tell is **30 %** shorter |
| after a STAGGER ends | (wait 0.6) → phrase D | (wait 0.4) → #5, always | #5-tracking immediately |
| under 25 % HP | — | — | every wait halved; `cluck` before each phrase — his tell for "I'm desperate" |

Reading it as a player: R1 is "one attack, one answer"; R2 is "the third flash is the truth"; R3 is
"the crest never lies, the feet sometimes do, and the crow can be a lie once per lap."

**THE STALL ROW CHANGED, AND THE CHANGE IS A RULING** (LAW.md §4.3). It stays on **real** seconds,
`[3, 3, 2]`, because on world seconds 3 world s at rate 0.03 is a hundred real seconds and the boo
would never arrive for the player it exists for. What makes a real clock legal here is the gate
that is already in `Boxer.stall()`: it returns immediately unless `phase == Phase.IDLE`, so
**a player reading a committed telegraph is never on a real clock** — the stall answers camping in
neutral and nothing else. Two edits so it is watched rather than sprung: the amber crest arrives
from **round 1** and over the last real second, before anything fires; and **R3's forced real-time
half-peck is deleted.** That was the only hostile *action* in the game running on real time, it was
a strike the player could not slow, and DESIGN.md §0.1 rule 4 forbids it. R3's answer becomes R2's
with a 30 % cut instead of 20 % — his tell, on his clock, readable.

---

## 7. THE PATTERN — a cabinet, not a coin

Deterministic. The only randomness is the ORDER OF PHRASES within a round after the scripted
opening, chosen by a fixed 4-slot rotation re-seeded per fight from the frame count at the coin —
so a run is learnable and no two runs are identical, and every phrase is memorised as a unit.
Branches key off PLAYER STATE at the moment the phrase ends, which the player can see and control;
a punished wrong-side lean costs −50 so the branch is legible in the tally. The AI plays this table
and nothing else.

Notation: `#n` an attack; `(wait n)` a neutral pause of n world seconds with the guard up; `→`
next; `[state]` a branch on the player's state at the end of the previous phrase; `FEINT` per §6.

**Round 1 — THE STRUT** (teach each answer, then ask for two of them in a row)
*Rewritten 2026-09-10 for density — `docs/CARD.md`, "THE ROOSTER, MADE A FIGHT", carries the
reasoning and the Kotlin. The tells are untouched; only the dead air and the phrase count moved.*

| phrase | sequence | branch |
|---|---|---|
| A (opening, always first) | #1 (wait 0.6) #1 (wait 0.6) #2 (wait 0.6) #2 | [ducked either peck] → the next phrase is C (he saw you duck: here is a low hook) |
| B | #3 (wait 0.7) #3 (wait 0.7) **#4** | [slipped into #3 and was hit] → repeat B once more (max twice), else → D |
| C | #4 (wait 0.7) #4 (wait 0.7) **#3** | [ducked #4] → the "wake up" line and repeat C (max twice) |
| D | #1 #2 (wait 0.5) #5 | [still upright and centred after #2's recover] → #5 with a 0.5 s tell; [slipped during the recover] → #5 as the table |
| **F (new)** | **#2 (wait 0.6) #3 (wait 0.6) #4** | [guarded through all three] → **#5** — the punch the guard cannot stop, thrown at the player who just proved they would rather block than move |
| E (only under 60 % HP) | #5 (wait 0.9) #5 | — |
| loop | A once, then rotate **B C D F** (E inserted after any phrase when eligible), **0.5 s** neutral between phrases | after any STAGGER ends → (wait 0.6) → D |

B and C now teach the wing pair as a **contrast inside one phrase** rather than as two drills: the
crest is gold all three times, and what changes is its HEIGHT — fanned, fanned, drooped. F is the
first thing in the game that asks for a different *kind* of answer twice in a row with no reset
(lean, duck, block), and it is the phrase that turns R1 from a lesson into a fight. Four rotating
phrases is also what `rotationFor()` has always shuffled: the pool was three, so one of its four
slots was silently discarded on every round-1 seed.

**Round 2 — THE RUFFLE** (chain and feint)
| phrase | sequence | branch |
|---|---|---|
| A2 | #1 #2 (0.2 s tell on #2) (wait 0.6) #1 #2 | [blocked both] → FEINT(#1) → #2 into the lean |
| B2 | FEINT(#1) (wait 0.3) → [slipped left] #2 / [slipped right] #4 / [ducked] #5 / [still or guarding] #3 | the feint teaches "wait for the third flash" |
| C2 | #3 (wait 0.5) #4 | [ducked #4] → `wake_up` → #5 |
| D2 | #4 (wait 0.4) #5 | [guard-countered #4] → he skips #5 and staggers 0.4 s instead |
| E2 | #5 (wait 0.9) FEINT(#5, the cut crow) → [slipped] #3 into that side / [stepped] nothing (he lost you: 0.8 s open) / [ducked] #5 for real | the crow feint appears once per rotation |
| loop | A2 once, then rotate B2 C2 D2 E2, 0.4 s neutral between phrases; after a STAGGER → (wait 0.4) → #5 | stall pressure per §6 |

**Round 3 — THE COCKFIGHT** (everything, fast, tracking)
| phrase | sequence | branch |
|---|---|---|
| A3 | #1 #2 #1 (0.15 s tells on the 2nd and 3rd) (wait 0.4) #5-tracking | [stepped] → (wait 1.0, he re-squares) / [slipped early] → the Sunrise follows and lands unless the slip began inside the strike |
| B3 (DOUBLE WING) | #3 → #4 back to back (0.2 s tell on #4) | [ducked #3 then guarded #4] = the intended read; [stepped once] clears both, `STEP +400`; [stayed ducked] → 18 dmg and `wake_up` |
| C3 | HALF-STAMP (nothing) (wait 0.3) #3 | teaches crest-over-feet |
| D3 | FEINT(#2) → [slipped right] #1 / [slipped left] #3 / [ducked] #5-tracking / [guarding] #4 / [stepped] 0.6 s open | — |
| E3 | #5-tracking (wait 0.6) #5-tracking | [both stepped] → he is winded: 1.5 s open, the crest droops — the round-3 knockdown setup |
| loop | A3 once, rotate B3 C3 D3 E3, 0.3 s neutral between phrases; after a STAGGER → #5-tracking immediately; under 25 % HP every wait is halved and `cluck` precedes each phrase | — |

**Authoring rule for the neck** (DESIGN.md §14): no phrase asks for two full ducks in a row, and
across a rotation a full duck is needed at most once per ≈ 6 world seconds; the guard answers one
threat in three.

---

## 8. THE POSE STRIPS — what the Blender artist authors

`blender/assets/boxer.py`, procedural bpy (no `.blend`), 12 fps, CONSTANT interpolation, object
origin = pivot, mouths / sweat / spirals as `hide_render` variants, markers as `m_*` empties
(DESIGN.md §12.6). Total ≈ 122 frames, ≤ 700 segments a frame (the exporter fails the build past
that). Every strip is a COMPLETE drawing per frame. Durations are at rate 1 in ROUND 1; the engine
plays the tell and recover segments faster in R2 / R3 and the strike segment always at 1×.

| Strip | Frames @12 | Duration | Loop | What is drawn | Events (frame) |
|---|---|---|---|---|---|
| `idle` | 8 | 0.67 s | yes | guard up, weight shifting foot to foot, brows flat, mouth grin | — |
| `guard` | 4 | 0.33 s | yes | gloves high over the face, eyes between them, brighter by overlap | — |
| `peck_l` | 6 tell + 3 strike + 6 recover = 15 | 1.25 s | no | glove pulls back and DOWN, brow V, left pupil forward; strike: glove 1.8×, arm straight, head tilted to his right; recover: the arm comes home | `telegraph` 0, `strike` 6, `done` 14 |
| `peck_r` | 15 | 1.25 s | no | the mirror (an x-flip is legal here and nowhere else — the crest is symmetric) | `telegraph` 0, `strike` 6, `done` 14 |
| `wing_r` | 8 + 4 + 10 = 22 | 1.83 s | no | glove to the hip, crest fans, right boot plants, eyes to slits; strike: the glove crosses the plate 1.8×, shoulder turns; recover: over-rotated, back shoulder shown | `telegraph` 0, `stamp` 2, `strike` 8, `done` 21 |
| `wing_l` | 8 + 4 + 7 = 19 | 1.58 s | no | glove drops below the frame, crest droops, left boot plants, pupils down; strike: the glove sweeps in low from the right; recover | `telegraph` 0, `stamp` 2, `strike` 8, `done` 18 |
| `sunrise` | 10 + 4 + 14 = 28 | 2.33 s | no | the crow (mouth rectangle), crest white and straight, both gloves drop; the right rises from the bottom edge growing to 1.8×; recover: arms in the air, chin up | `telegraph` 0, `crow` 0, `strike` 10, `done` 27 |
| `feint_peck` | 4 | 0.33 s | no | the first 30 % of a peck: pupil flashes twice, shoulder does NOT dip, the glove stops; back to `idle` | `telegraph` 0, `done` 3 |
| `feint_crow` | 5 | 0.42 s | no | the crow starts, the crest goes white, then snaps violet; the mouth closes | `telegraph` 0, `cut` 3, `done` 4 |
| `half_stamp` | 3 | 0.25 s | no | the boot plants, nothing else moves | `stamp` 0, `done` 2 |
| `hit_head` | 3 | 0.25 s | no | head stretched sideways, eyes X, mouth gasp, sweat drops, one crest spike wobbles | `flash` 0 |
| `hit_body` | 3 | 0.25 s | no | torso folds, gloves fly outward, mouth O | `flash` 0, `open` 0 |
| `stagger` | 8 | 0.67 s | yes | knees bent, gloves at the hips, spiral eyes, crossed crest, tongue out, head bobbing | `open` 0 |
| `stun` | 4 | 0.33 s | yes | hands low, dazed face (the stun of §4 — shorter than a stagger, no spirals) | `open` 0 |
| `knockdown` | 13 | 1.08 s | no | falls toward the camera, glove up, then flat: the last frame is the lying pose | `down` 12 |
| `down` | 4 | 0.33 s | yes | on the canvas, X eyes; the engine draws the stars at `crown` | — |
| `getup` | 8 | 0.67 s | no | to one knee, a slip, to standing, a shake of the head | `up` 7 |
| `ko` | 12 | 1.0 s | no | the fall, then a 4-frame flat pose with the tongue out; the engine fades the gain 1 → 0.3 | `ko` 11 |
| `taunt` | 10 | 0.83 s | no | a glove beckons, brows raised, tongue out; the intro's pose | — |
| `win` | 8 | 0.67 s | yes | both gloves up, bouncing (the `TIME - NO DECISION` card) | — |

Markers per frame: `glove_L`, `glove_R`, `chin`, `body`, `eye_L`, `eye_R`, `crown`. Parts (fixed
order, all frames): `head`, `hair` (the crest, 5 spike sub-objects so the engine can hide one per
knockdown), `brow_L`, `brow_R`, `eye_L`, `eye_R`, `nose`, `ear_L`, `ear_R`, `mouth` (5 variants),
`teeth`, `jaw_hatch`, `neck`, `torso`, `trunks`, `uarm_L`, `uarm_R`, `farm_L`, `farm_R`,
`glove_L`, `glove_R`, `leg_L`, `leg_R`, `boot_L`, `boot_R`, `hatch_head`, `hatch_head_x`,
`hatch_torso`, `hatch_trunks`, `hatch_glove_L`, `hatch_glove_R`, `sweat`, `spiral_L`, `spiral_R`
— 34; the tint table has 32 slots, so `brow_L/R` share one part and `sweat` rides on `detail`.

Two previews before the glass (DESIGN.md §12.6): the contact sheet per strip and the engine-exact
`tools/strip_sheet.py` with its whiteish-pixel count. Read the crop: is the outline unmistakably
heavier than the detail; is exactly one thing white on the tell frame; does the hatch read as a
block at 2.6 m; does the crown clear the top bezel at rest and may the extend frame's glove leave
the plate (that is the punch-in).

---

## 9. THE VOICE LINES — MOVED

**The script now lives in `docs/VOICE.md`**, which is the file `tools/extract_lines.py` parses.
Eighty-four clips across five speakers no longer belong in a document about one boxer, and the man
in the ring is five men: every line that depends on which of them is up is keyed
`<stem>_<fighter.id>` and resolved once per bout (VOICE.md §4), so adding a sixth fighter is a row
in `Fighter.CARD` plus eight rows in a table and no Kotlin at all.

Three facts belong here rather than there, because they are facts about **this boxer**:

- **The crow is the Sunrise's only audio tell.** `Attack.SUNRISE` carries `tellSfx = -1`
  (`Boxer.kt` line 278 — "the Sunrise is crowed, not played"), so if `crow_<id>` is missing, a
  25-damage unblockable arrives with no sound at all. That is why resolution falls back to the
  Rooster's clip rather than to silence, why the crow is `urgent` on the bus, and why
  `render_voices.py --check-lengths` caps every crow at **1000 ms**: it has to finish inside the
  tell it is announcing.
- **His crow is `crow_rooster`, "Rise and shine!", and the false crow `crow_cut_rooster`.** The old
  ids `rise_and_shine` / `rise_cut` are gone; `Boxer.kt` now speaks in **stems**
  (`Lines.Him.CROW`, `CROW_CUT`, `HIT`, `TAUNT`, `GUARD`) and never names a clip, which is the seam
  that keeps this class ignorant of the card.
- **The old `wake_up`, `cluck` and `that_all` are `hit_rooster`, `taunt_rooster` and
  `guard_rooster`.** Every `Lines.WAKE_UP` in the pattern tables of §7 becomes `Lines.Him.HIT`, and
  the stall's `Lines.CLUCK` becomes `Lines.Him.TAUNT`.

The announcer still calls EVERY landed punch, as the cabinet did — it is the game advertising
itself in a loud room — and those calls are still the first lines the bus drops when it is busy
(patience 300 ms: a call that arrives late is worse than no call).
