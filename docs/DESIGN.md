# X3 KNOCKOUT — GAME DESIGN DOCUMENT (prototype: one boxer)

> **STATUS 2026-09-10 — the one-floor law SHIPPED; the rest of `docs/LAW.md` has not.** What is in
> the build: `Boxer.floorNow()` collapsed from a four-branch table to one game-wide floor, and
> `Clock.FLOOR_STILL = 0.03` for every difficulty (LAW.md §1.2, and rule 1 of §0.1 below). Measured
> on the glasses the same afternoon: a still player, mid-round, reads `ts=0.06` where the old build
> could not go below 0.35. What is NOT in the build: LAW.md §1.3 (the yaw/dodge split), §5 (the
> forced rate), §6 (the ×1.8 window re-derivation) and §8 (the frozen-state legibility work). The
> hang and the fuse are still there and still real-time; they no longer touch the floor.
>
> **AMENDED 2026-09-10 — `docs/LAW.md` IS NOW THE AUTHORITY ON THE CLOCK, and §1 and §2 defer to
> it.** The owner played the build and reported that he could not see the SUPERHOT mechanic at all.
> He was right, and the cause is one number: `Boxer.FLOOR_IDLE = 0.35` meant the world never ran
> slower than a third speed while the opponent was idle, and a **real-time** fuse then ramped it to
> 0.60 whatever the player did. Measured over the Rooster's own R1 phrase A, a player who never
> moved experienced `worldT / realT = 0.230`. LAW.md replaces the whole phase-floor design with one
> constant for the entire game (`FLOOR_STILL` 0.03), splits the gyro so that **looking is free and
> only a slip or a duck is charged**, turns the forced punch from rate 1.0 into a forced *rate*,
> and re-derives every world-timed window that follows from that. **Where §2 below and LAW.md
> differ, LAW.md wins**; §2 is kept as the record of what was built and why it failed. MOTION.md
> still outranks both on what the body reads.

> **AMENDED 2026-09-09 — the owner's ruling (commit `13741bd`; applied to BOXER.md's tables and to
> the code).** Every punch travels on **world** time, his and yours. Where §0, §2.2, §2.4, §2.6 and
> §2.9 below call his STRIKE a forced real-time state (`Forced.STRIKE`, rate 1.0), read instead: the
> glove in flight advances on `wdt` under the same hang-then-fuse floor as the tell it came out of
> (`Boxer.floorNow`), so a still player watches it crawl and hang, and the fuse — still burning on
> real time through the strike — brings it the rest of the way. Your own punch keeps its
> `Forced.PUNCH` window: a forced rate the game declares openly on the rail is one of the honest
> pressures the ruling names. `Clock.forceStrike` remains for the tests and the lab; the fight no
> longer calls it, and a `forced=STRIKE` in a `CLK` line means somebody put it back.

RayNeo X3 Pro (ARGF20). A 1280×480 side-by-side surface, each eye 640×480, the same image on both
— never called 3D. Waveguide microLED: black is transparent, so the game is glowing strokes on the
real room; "dark" means a black ground and FULLY SATURATED hues with small white-hot cores, never
grey, never washed. OpenGL ES 3, additive blending (`SRC_ALPHA, ONE`), no depth buffer — nothing
occludes, everything sums, brighter reads as nearer. 60 Hz or it does not ship. 3-DoF head only
(game rotation vector), gyro / accelerometer / gravity at up to 200 Hz, no position, no step
counter. Input: **two temple pads** — RIGHT = kernel device `cyttsp5_mt`, LEFT = `cyttsp6_mt` —
and the body.

**What it is.** An arcade boxing cabinet in the spirit of Nintendo's 1984 *Punch-Out!!* (Takeda /
Miyamoto — cited here as the design object; **none of its trademarks or character names reach the
glass, the game speaks in its own names**), rebuilt for a pair of glasses you stand up in. One huge
glowing caricature stands 2.6 m in front of you in a ring of blue rope; he telegraphs every punch
with his eyes, his gloves, his crest and his feet; you slip, duck, block or step, and you hit him
back by tapping the temples of the glasses — because **both hands at the temples is a boxer's
guard**: left temple = left glove, right temple = right glove, both at once = the big one. All of
it runs under the law this fork inherits and keeps: **TIME MOVES ONLY WHEN YOU MOVE** — with the
floor handed to the boxer's own state, so that the world hangs exactly when there is something to
read and runs when there is not. Stand still and his wind-up freezes with its future drawn to the
point on your face it is aimed at; move, and your move throws his punch.

**The owner's brief, verbatim:** *"use the movement functionality towards a punchout (arcade)
clone. similar graphics as the arcade - exaggerated comic-book caricatures, high-contrast visual
telegraphs, and ingenious technical workarounds designed to overcome severe rayneo x3 processor
limitations. tap to punch, the left arm of the glasses actually registers a tap. head tilt to
dodge. look down to duck. lets use the same time cost mechanism but make it exciting. just one
boxer to get it working to test."*

**What it inherits (on disk, commit `6345b7b`, measured on the owner's head — `docs/MOTION.md`).**
`head/MotionTracker.kt` (the motion scalar, roll and pitch from gravity, the shaped lean — dead 4°,
full 0.55 m by 22° — the duck — 0.42 m drop by 29° of nod — the four-gated step recogniser, 150 ms
pad blanking, the 20 Hz raw recorder), `engine/Clock.kt` (`timeScale = floor + (1 − floor) ×
clamp(motion + act)`, Set B knee `W_REF 1.9 / GAMMA 1.8`, action quanta, forced states, `worldT`,
the 10 Hz `CLK` line), `head/HeadTracker.kt` (yaw/pitch look, the coin-tap recentre),
`gl/StrokeFont.kt`, `engine/StrokeModel.kt` + `blender/export_strokes.py`, `audio/{Sfx, Voice,
Music}.kt`, and `MainActivity.kt`'s temple-pad arbitration, which is **battle-tested and subtle**
and is extended at exactly the seams named in §1.6, never restructured. The former game files are
deleted; `Game`, `Boxer`, `Ring`, `GLRenderer`, `Hud` and the sprite engine are new. Constants are
tagged **[on disk]**, **[changed]** or **[new]** against that code. Where this document and
MOTION.md disagree on a body number, MOTION.md wins.

**Companion documents.** `docs/BOXER.md` — the one boxer, his moveset and his pose strips.
`docs/TEST.md` — what adb can verify alone and the owner's standing test in order. The glasses
(`A06B4A96A733283`) were NOT attached while this was written: every on-head number below is a
proposal with a test attached, and §15 lists the ones that decide the design.

---

## 0. THE DESIGN IN ONE PARAGRAPH

Hands at the temples are the guard. Left tap = left glove, right tap = right glove, head level =
you hit his head, head down = you hit his body, both temples inside 120 ms while the KO meter is
lit = the big punch. Tilt = slip, nod = duck, right-pad swipe DOWN held = guard, right-pad swipe
LEFT / RIGHT (or a real sidestep) = step. **The clock is x3discs' clock with one floor for the
whole game (`FLOOR_STILL` 0.03) and nobody allowed to raise it** (`docs/LAW.md`): stand still and
the world stops — his telegraph frame holds on the glass for nearly three seconds, his glove crawls
across at 7 cm/s trailing an 80 cm arc that hangs with it, the crowd drops to a hush and every
hostile sound groans a semitone-and-a-half down; move, and your move is what throws his punch. Only
a **roll or a pitch** is charged, because on this rig those are the slip and the duck; **looking
around is free**, exactly as SUPERHOT rules it. His glove flies on world time and yours does too;
your own punch forces a *rate* (0.55 jab / 0.65 hook / 0.90 special), never a rail, so the world
leans in as you throw and settles back the moment you stop; a landed hit stops both clocks for
4–12 frames with a white-hot glove; a PERFECT dodge — off the line inside his strike — buys 0.6
real seconds in which your next punch is a COUNTER thrown at the floor, in a genuinely frozen
world; knockdowns fall at 0.22 and the referee counts on the real clock. Three rounds of **36
world seconds** against ROY "THE ROOSTER" RUDD, whose five attacks each have one cheap answer, two
safe ones and one fatal one. The score gives you all the time you want and asks, at the KO, how
little of the world's you spent getting there.

---

## 0.1 AMENDMENT — WHO SETS THE PACE (the owner, 2026-09-10)

> *"in superhotvr its the enemy that initiates the pacing based on the difficulty of the level."*

This is the other half of the law, and getting it wrong is exactly how this game ended up with
`Boxer.FLOOR_IDLE = 0.35` — a world that never runs slower than a third speed, and an owner who
reported he could not see the mechanic at all.

**The clock is never the difficulty.** In SUPERHOT the time function is the same in the first room
and the last; what changes is the ROOM — how many enemies, where they stand, what they hold, how
many lines of fire cross the space you have to move through. Difficulty is authored as THREAT, and
the clock stays an honest instrument that answers only the player's own motion.

This game did the opposite: it made the floor a difficulty dial (0.35 at idle, a "deep" 0.04–0.10
only during a tell, a fuse ramping back to 0.60). So the pressure came from THE CLOCK RUNNING
WITHOUT THE PLAYER — which takes the frozen moment away at precisely the moment it was promised,
and hides the one mechanic the game is about.

**The rule from here:**

1. **The idle floor is a constant of the GAME**, not of the fight, the round or the fighter. Nobody
   on the card gets a higher floor for being harder. A statue faces the same nearly-frozen world in
   round one of the Rooster as in round three of the Metronome.
2. **Difficulty is pressure IN THE RING** — how often he throws, how much overlaps, how short his
   recovery, how little his guard opens, how fast a phrase follows the last, what a hit costs. All
   of it his, all of it visible.
3. **A statue is answered by the FIGHTER, never by the clock.** The crowd, the shortening tells, a
   forced rate the game DECLARES on the rail: those are the boxer and the referee applying pressure
   where the player can watch it arrive. A floor quietly raised behind their back is the designer
   applying pressure and dressing it up as the sport.
4. **The one exception, stated once:** a boxer must eventually reach you or there is no fight. That
   is his to apply, on his own clock, in a way the player can see coming — never a number the world
   clock adds while nobody is looking.

THE METRONOME IS THE DELIBERATE EXCEPTION, and he proves the rule: his own seconds run on the
player's motion, amplified. But that is HIS clock, it is announced in his billing, it belongs to the
one fighter whose entire character is that he has read the rules — and the WORLD's floor is
untouched even for him.

> **This amendment became constants on 2026-09-10: `docs/LAW.md`.** Note in passing that the
> champion's own clock, `w = r × (TEMPO_FLOOR + gimmickK × body.moving)` = `0.10 + 2.2 × motion`,
> is **already the correct SUPERHOT shape**, and its own KDoc says the quiet part out loud — *"a
> still player faces a still opponent, which is the whole promise."* `FLOOR_IDLE = 0.35` broke that
> promise for the other four men on the card while one fighter kept it as a gimmick. Under LAW.md
> he stays distinct without changing a number: he reaches rate 1.0 at motion 0.41 where the world
> reaches it at 1.00, so he answers the same movement about 2.2× as hard as the world does. His
> billing becomes true instead of ironic.

---

## 1. THE VERBS

### 1.1 The founding observation
The player's hands are physically up beside the head for the whole fight — that is a guard, and
the controller is the stance. Left hand on the left temple is the left glove; right hand on the
right temple is the right glove. Nothing in the mapping asks a hand to hover: a tap is a reach, so
the hands can drop between exchanges (§14, neck and shoulders).

### 1.2 The complete table (the fight)

| Input | Signal | Verb |
|---|---|---|
| LEFT pad tap (`cyttsp6`: finger-lift < 400 ms, travel < 115 px) | the TOUCH stream, `ev.device.name` | **LEFT PUNCH** — head shot if the head is level, BODY BLOW if the head is down (§1.3) |
| RIGHT pad tap (`cyttsp5`) | the touch stream | **RIGHT PUNCH** — same rule |
| both pads, second lift ≤ `SPECIAL_MS` 120 after the first | two per-pad lift timestamps, touch only | **THE SPECIAL** ("the Wake-Up Call") if the KO meter is LIT; otherwise the pair is an ordinary 1-2 (§1.4) |
| head down: `duckAmt ≥ 0.35` (≈10° of nod at the shipped DODGE SENSE), leave at 0.25 | `MotionTracker.pitchG − restPitch` (gravity, drift-free) | **AIM LOW** — every punch from here is a body blow; and from `duckAmt ≥ 0.6` the collider is under a head-high hook: **DUCK** |
| head tilt: the shaped lean | `MotionTracker.roll − restRoll` | **SLIP** — the eye and the collider move sideways (0.55 m at full); the punch misses geometrically, never by a threshold |
| RIGHT pad swipe DOWN, held | the existing `AXIS_DRIVE` path (`deflectorStart/End` → `guardStart/End`) | **GUARD** — gloves up until the finger lifts; a flick is a 0.5 s world pop. Head punches into the guard chip 2; a hook CRUSHES it (half damage, guard forced down 0.4 s real); the uppercut cannot be blocked |
| RIGHT pad swipe LEFT / RIGHT | the finger-up classifier | **STEP** — `Forced.STEP` 0.35 s at a forced FLOOR rate of **0.70** (LAW.md §5), the camera and collider slide 0.55 m, invulnerable for the flight, exposed on landing. The pad-only escape for anyone who cannot lean; the answer to the round-3 tracking uppercut |
| a real sidestep | `MotionTracker.step` (the four gates) | the same **STEP**, logged `BODY`; blanked 150 ms after any pad touch, so punch-then-step is a designed impossibility — the corner says so once (§10) |
| RIGHT pad swipe UP | one-shot crossing (the old recall path) | **THE SPECIAL by pad** — only with `LEFT PAD OFF` (§1.5); otherwise a soft click that still spends its quantum |
| RIGHT pad double-tap / triple-tap | the burst (off the arena only) | settings / re-centre + rest posture |
| keys `BUTTON_A` / `BACK` / D-pad | the key stream | echoes only, never attributed to a pad (§1.6) |

LEFT-pad SWIPES are deliberately not a verb: the OS reads them as system volume, and whether an
app that consumes the `MotionEvent` stops that is unknown until measured (TEST.md L1). The left
pad is TAP ONLY, and the title says so: `DON'T SLIDE THE LEFT PAD`. Yaw does nothing to a punch:
left / right is the hands' job, and a yaw rule would be a hidden third aim axis the player cannot
see.

**What every one of these verbs COSTS is now LAW.md §5, not §2.3 below.** The short version: a
punch forces a *rate* rather than rate 1.0 (jab 0.55, hook 0.65, special 0.90, step 0.70), and it
forces it as a **floor** — `max(lawRate, forcedRate)` — so a player who punches while moving hard
still gets their own motion in the answer. Forcing exists for one reason and it survives the
change: on the owner's recorded run a delicate cut-tap drove `motion` to 0.29 and a slap drove it
to 0.96, and a forced floor makes the two cost the same.

**And that last paragraph about yaw is now load-bearing twice over.** Because §1.3 makes the nod
the aim and §3.3 makes the tilt the slip, **pitch and roll are the body and yaw is the camera**,
which is exactly the distinction SUPERHOT draws when it says looking around does not advance time.
That is why the clock is charged on `hypot(ω_pitch, ω_roll) + 0.15 × |ω_yaw|` rather than on |ω|
(LAW.md §1.3). It is not a special case bolted onto the sensor; it falls straight out of the verb
table above.

### 1.3 Head and body are one axis: the duck is the aim
The boxer's head sits at your eye height and his body is below it, so looking at the body IS a nod,
and MotionTracker already turns a nod into a duck. Making them one rule removes an ambiguity rather
than adding a threshold:

- **Level (`duckAmt < 0.35`): a tap is a HEAD SHOT.** The default; no lift above rest is ever
  required, which matters for the neck.
- **Down (`duckAmt ≥ 0.35`): a tap is a BODY BLOW.** The low stance is both the dig to the body
  and — at 0.6 — the dodge under a hook: exactly the cabinet's counter dynamic (duck the hook, dig
  the body). Hysteresis: enter LOW at 0.35, leave at 0.25, so a head hovering at the line cannot
  flip a combo's second punch to the other level. The HUD tells the player which level is armed by
  which glove outline is lit: gloves at rest = head, gloves dropped to the guard slots = body (§8).
- Measured from `pitchG` (gravity), NOT from HeadTracker's rotation-vector pitch, so the aim and
  the duck can never disagree; HeadTracker's pitch keeps driving the *view* only.
- Why not a smaller "glance" threshold (6°)? The FIGHT phase of the owner's recorded run has pitch
  wandering +8…+15° while he is simply looking at things; a glance-sized threshold would sit inside
  that band and every jab would randomly become a body blow. 10° with hysteresis, on the same duck
  scale the owner has already felt, is the safe number.

`duckAmt` is MotionTracker's number (`clamp((restPitch − pitchG) / DUCK_FULL, 0, 1)`), but
`DUCK_FULL` and `LEAN_FULL` are now a setting — **DODGE SENSE** (§11): LOW = 29° / 22° (the on-disk
numbers), **MEDIUM = 22° / 16° (ships)**, HIGH = 16° / 12°. A boxing round asks for a dodge every
few seconds, not once a level, and the neck decides this row standing up (TEST.md D3).

### 1.4 The special: two pads inside 120 ms, upgraded in place
- Each pad's touch tap stamps its own lift time (`lastLeftLiftMs`, `lastRightLiftMs`). A tap from
  pad P commits its punch **instantly on finger-lift** — punches are never held back to wait for a
  partner; a 120 ms lag on every punch is the "cannon with a beat of lag" `MainActivity`'s KDoc
  rejects.
- If the other pad's lift lands inside `SPECIAL_MS` = 120 ms **and the KO meter is LIT**, the punch
  already in flight is **upgraded in place** into the special. Every player punch spends its first
  0.15 s real in wind-up (the jab's contact frame is frame 9 at 60 Hz, §4.2), so a conversion at
  ≤ 120 ms happens before any contact frame and is invisible. Meter not lit: no conversion — the two taps are a 1-2 from alternate hands, the
  second queued behind the first's active frames.
- Never commit on `ACTION_DOWN` to shrink the gap: a down without a lift is how a slide (volume)
  begins on the left pad.
- 120 ms is a guess to be measured: the `PAIR=` telemetry (§13) logs the inter-lift interval of
  every two-handed pair the owner throws; ship the p90 (TEST.md V4).

### 1.5 The fallback ships in the same build: `LEFT PAD` OFF
If the left pad fails verification (TEST.md L1–L3: a phantom key per tap, a volume panel, a slide
that changes the volume), the settings row `LEFT PAD` goes OFF: right-pad taps alternate hands
automatically (a 1-2 by rhythm, the hand shown on the lit glove), and swipe UP is the special. The
fight is fully playable one-handed; only the "both temples" beat is lost.

### 1.6 `MainActivity` — extended at exactly these seams, never restructured
The file's own rules stand: the touch stream is always there and always first; keys are late
echoes that only ever ADD a tap; a key within `ECHO_MS` (900) of a touch tap is dropped; taps off
the arena settle in a `BURST_MS` (320) burst (1 / 2 / 3 = tap / settings / re-centre, ≥ 4 =
nothing); on the arena a tap commits the instant the finger lifts. Keys carry no device name, so
**a key can never say which pad it came from: the TOUCH stream decides left vs right, keys stay
echoes.**

1. **The `cyttsp6` drop at the top of `dispatchTouchEvent` (line 486) becomes a ROUTE.** The left
   pad gets its own tiny gesture record — `lDownT`, `lDownX/Y`, `lFar` — because the existing
   `downX/downY/downT/farDx/farDy` are single fields and a left `ACTION_DOWN` while a right finger
   is still down would clobber the right gesture. On the left: `ACTION_UP` with travel < `thresh`
   and hold < 400 ms → `punch(LEFT)` (urgent, straight to the GL thread); anything else on the left
   is consumed and ignored — no swipes, no burst, no drive. `motion.padEvent()` is still called
   for left events: a tap is an impulse on either temple.
2. **`lastTouchTapMs` is stamped by LEFT taps too.** A `BUTTON_A` trailing a left tap is an echo of
   it; without the stamp the echo rule would count it as a RIGHT tap 100–300 ms later — a phantom
   right punch after every left punch if the service classifies both pads (unknown, TEST.md L1).
   A key with no touch behind it (remote, `adb`, a pad that stopped reporting) defaults to RIGHT
   and is logged `KEY→R`.
3. **`burstClamped` is set from `game.inFight` when the burst opens.** Today two arena taps inside
   320 ms = one urgent tap + `doubleTap()` → the settings open; a 1-2 combo cannot open the pause
   menu. The mechanism exists and is already read once at burst-open ("gloves up: no menus by
   tapping"); in a fight every burst resolves to nothing because its taps have already gone live.
   Off the arena the burst works exactly as today. In-fight menu access: the corner, or a BACK
   with no touches under it.
4. **`onBack()`'s touch-under-it test uses `max(downT, lDownT)`**, so a service BACK that describes
   a left double-tap is eaten too.
5. The 120 ms pair is decided from the two per-pad touch lift timestamps only, never from keys.
6. `onPause` additionally clears the left gesture record.
7. The 900 ms echo window and rapid punching: with taps every 200 ms the window is always open, so
   ALL `BUTTON_A` keys are dropped during a flurry — correct, the touch stream carries every punch.
   The only loss is a key-only remote, which this game does not have.
8. New telemetry at the top of both dispatchers (§13): one `PAD` line per touch event with the
   device name, and one `KEY` line per key with `deviceId` and `device?.name` — nobody has ever
   logged the key's device on this hardware; if the service uses a distinct virtual device per pad,
   keys become attributable and rule 2's default is refined then, not before.

### 1.7 The pad, in every state
One meaning per gesture per state. Never a long-press (the glasses reserve a temple hold for the
system shade; the swipe-down-and-hold guard is x3discs' documented, measured exception).

| State | LEFT tap | RIGHT tap | both ≤ 120 ms | double-tap | triple-tap | swipe L / R | swipe UP | swipe DOWN flick | swipe DOWN hold |
|---|---|---|---|---|---|---|---|---|---|
| TITLE / ATTRACT | — (silent: the left pad is fight-only, §1.2) | `INSERT COIN`: start; re-centres yaw + pitch, takes the rest posture | — | settings | re-centre + rest posture | — | — | — | — |
| INTRO (the announcer, ~6 s) | — | after 1.5 s: skip to the round card (never mid-line) | — | settings | — | — | — | — | — |
| ROUND CARD (1.2 s + the bell) | ignored (200 ms dedupe after the coin) | ignored | — | settings | re-centre | — | — | — | — |
| FIGHT | **LEFT PUNCH** | **RIGHT PUNCH** | **SPECIAL** (meter lit) else 1-2 | nothing (`burstClamped`) | nothing | **STEP** L / R | special only with `LEFT PAD OFF`, else a click (0.03 s quantum) | **GUARD POP** 0.5 s world | **GUARD HELD** until the lift |
| COUNT (he is down) | — | — | — | nothing | — | — | — | — | — |
| COUNT (you are down) | **RISE** tap | **RISE** tap (alternate hands; a same-hand double counts once) | — | nothing | — | — | — | — | — |
| CORNER (2.2 s + the tip) | — | skip the tip after 1.0 s | — | settings (pauses the rest) | — | — | — | — | — |
| KNOCKOUT (the win, tally) | — | after 1.2 s: skip the tally → title | — | settings | — | — | — | — | — |
| GAME OVER | — | after 1.2 s during the 9 s countdown: **CONTINUE** (round 1, score 0); after it: title | — | settings | — | — | — | — | — |
| SETTINGS | — | adjust / toggle the row; `RESET SETTINGS` and `QUIT` arm then confirm | — | close | — | adjust the value | selection up | selection down | (one step per gesture, however long the pad is held) |
| CREDITS | — | back to settings | — | close | — | — | scroll | scroll | — |

`INSERT COIN TO PLAY` on the title, `INSERT COIN TO CONTINUE` on the game-over card — the suite's
exact wording. `QUIT` is on the settings menu only on the title and the game-over card; its value
reads `END OF LINE`, then `TAP AGAIN TO CONFIRM`; confirming calls `finish()` after the sign-off
clip — never a force-stop (the Mercury drawer drops force-stopped apps). D-pad keys mirror the
right pad for desk testing (`DPAD_CENTER` = right punch, `DPAD_DOWN` held = guard, `DPAD_UP` = the
swipe-up verb, `DPAD_LEFT/RIGHT` = step); there is no key for the left pad by design — a key can
never name a pad.

---

## 2. THE TIME LAW, MADE EXCITING

> **SUPERSEDED 2026-09-10 by `docs/LAW.md`. Read that instead; this section is the record of what
> was built and of the reasoning that turned out to be wrong.** In one line: §2.2's phase-floor
> table made the CLOCK the difficulty, which DESIGN.md §0.1 (written the next morning, by the
> owner) forbids, and the result was a world that never froze. The specific claims below that are
> now false — because an implementer skimming will otherwise act on them — are: the floor is a
> function of the boxer's state (§2.2, all of it); the hang and the fuse (§2.2, §2.7 beat 4); a
> punch forces rate 1.0 (§2.3, §2.4); `Forced.STRIKE` exists (§2.4); the idle floor is high on
> purpose (§2.8); and the 3:00 real cap (§2.8, §2.9). **§2.1 is still true and still matters**:
> the law itself, the Set B knee, ATTACK/RELEASE, the quanta decaying on real time and the absence
> of any anti-wiggle patch are all kept whole. §2.5 (hit-stop) and §2.6 (the counter window) are
> unchanged and correct.

### 2.1 What stays [on disk]
`Clock.kt` is kept whole: `timeScale = floor + (1 − floor) × clamp(motion + act, 0, 1)`, Set B
(`W_REF 1.9`, `GAMMA 1.8` — an ordinary scan costs 0.20, a snap saturates), `ATTACK 0.04 /
RELEASE 0.16`, action quanta that decay on real time, `MAX_DT 0.05`, `worldT`, the 10 Hz `CLK`
line, no anti-wiggle patch. On x3discs stillness froze the world at 5 % and that was the point: a
reading game. A fight at a 5 % floor is a puzzle — he winds up and hangs for ten seconds while you
think. The fix is not to raise the floor globally; it is to make **the floor a function of the
fight**, so the world hangs exactly when there is something to read and runs when there is not.

### 2.2 The floor is the boxer's state [new: `Clock.floorOverride`]
The fight state machine writes `clock.floorOverride` every frame; `Clock.floor` returns it when it
is ≥ 0 and the difficulty floor otherwise. Everything else in the formula is untouched.

| Boxer state | floor (NORMAL) | what a still player sees |
|---|---|---|
| IDLE / circling / taunting / feinting / guard up | **0.35** | he keeps circling at a third speed: the fight never looks paused |
| TELL (wind-up), the first `HANG_T` = 0.8 real s — **the hang** | **0.06** | the telegraph freezes: bullet time. You read which eye, which glove, the crest, the feet |
| TELL after the hang — **the fuse** | ramps linearly 0.06 → 0.60 over `FUSE_T` = 1.2 real s, then holds 0.60 | he does not wait forever; the punch comes to you |
| STRIKE (the glove in flight) | — (`Forced.STRIKE`, rate 1.0, §2.4) | not negotiable |
| RECOVER after a whiff, and OPEN / STAGGER | **0.12** | he is open; you can read the opening. It is measured in your punches (each tap spends world time), not in seconds |
| his KNOCKDOWN fall | `Forced.SLOW` 0.22 | the fall, at a quarter speed |
| the COUNT | `Forced.COUNT`, rate 0 | the referee is outside the bubble |

Difficulty scales the hang and the deep floor: EASY `HANG_T` 1.2 s / floor 0.04; **NORMAL 0.8 /
0.06**; HARD 0.5 / 0.10. The fuse ramp is the single most important number for "fight, not
puzzle": it puts an upper bound of ≈ 2 real seconds on any read. A tell authored at 0.50 s world
(the R1 peck) costs a moving player 0.5 s and a perfectly still one 0.8 s (the hang buys 0.05 s
of world) + 1.2 s (the ramp buys 0.40 s) + 0.06 / 0.60 ≈ **2.1 real seconds** — the spread that
IS the game. The fuse is DRAWN: the
right rail was the REFLEX rail draining through them (§8). **Both are deleted; see LAW.md §2.**

The release is instantaneous by construction: the floor change is a state switch, so the moment
he goes from IDLE (0.35) to TELL (0.06) a still player's world snaps to the hang in one frame.
That snap IS the telegraph's first beat, and it has a sound (`Sfx.HANG`, a held breath: the crowd
bed drops with it, §9.3).

**"Your move throws his punch."** Because the tell advances on world time, a still player who has
read it completes it by moving — the dodge you begin is what finishes his wind-up. A held lean is a
pose, not motion (MOTION.md: a lean at −39° sits at the floor), so a player who leans early and
stops may leave the tell half-done with the collider already off the line; the fiction covers it —
he "waits you out", the crest dims to amber while the tell is stalled — and then the fuse throws it
anyway. Stillness in a fight is answered by the fight coming to you, never by the world waiting.

### 2.3 The action quanta for punches — a punch forces the world to run [new]
`Clock.Verb` becomes `{ JAB, HOOK, SPECIAL, GUARD, STEP, EMPTY }` and a new forced state
`Forced.PUNCH` carries the player's punch.

| Verb | mechanism | ≈ world time spent |
|---|---|---|
| JAB (either hand, head or body) | `Forced.PUNCH` rate 1.0 for the punch's ACTIVE + RECOVERY (0.28 s real), then `pulse(1.0, τ 0.20)` | ≈ 0.48 s |
| HOOK (the right hand's heavier punch, §4.2) | forced 0.36 s, then pulse 1.0 / τ 0.25 | ≈ 0.60 s |
| SPECIAL | forced for its whole animation, 0.60 s, no pulse — it ends in hit-stop | 0.60 s: the biggest honest cost, exactly the hop's role on x3discs |
| WHIFF (air or his guard) | the forced window is EXTENDED by 0.15 s and a heart is spent (§4.4) | ≈ 0.63–0.75 s: the mash tax |
| LANDED punch | the forced window is CUT to the active frames; recovery is cancellable into the next punch or a dodge | ≈ 0.15 s: landing is cheaper than missing |
| COUNTER (inside the perfect window, §2.6) | the forced window is WAIVED: the world stays at the floor while you throw it | ≈ 0 |
| GUARD raise / pop | pulse 0.5 / τ 0.20 (the deflector's numbers) | 0.10 s |
| STEP | `Forced.STEP` 0.35 s at rate 1.0 (`HOP_T`'s ladder 0.28 / 0.35 / 0.45 / 0.60 stays in the lab) | 0.35 s |
| empty tap (a click off the arena's verbs, swipe UP with the left pad on) | pulse 0.2 / τ 0.15 | 0.03 s |

This is the whole mash economy: four blind punches into his guard ≈ 3 s of world time at full
rate, during which his counter lands, and four hearts gone; four punches that LAND cost under a
second. Rhythm, not mashing, is what the clock rewards, and the player is told by feel, because a
whiff hangs the world open around them.

A punch is a forced state rather than only a pulse because the accelerometer already runs the
world for a tap, unevenly: on the owner's recorded run a delicate cut-tap drove `motion` to 0.29
and a slap with the arm coming up drove it to 0.96 for ≈ 0.8 s (|a| 3.25 m/s²). A forced state
makes the cost the same for a delicate tap and a slap, which is what fairness needs.

### 2.4 The forced states [changed: `Clock.Forced`]
`Forced { NONE, STEP, CORNER, MENU, PUNCH, STRIKE, HITSTOP, SLOW, COUNT }`. The timed-vs-latched
split is kept: MENU latches over every timer; a hit-stop must not close the menu and the menu must
not cancel a strike. `clearForced()` still cannot drop MENU. The `CLK` line's existing `forced=`
key carries the new names so the review tooling keeps parsing.

| State | rate | duration | why it is not the body's to spend |
|---|---|---|---|
| STEP | 1.0 | 0.35 s real | the world moved you; it moves too (was HOP) |
| CORNER | 1.0 | 2.2 s real | the ropes slide you to your corner between rounds (was DOCKING) |
| MENU | 0 | latch | outside the fiction |
| PUNCH | 1.0 | 0.28 / 0.36 / 0.60 s real (+0.15 on a whiff; cut to the active frames on a hit) | you swung; the swing takes time |
| STRIKE | 1.0 | 0.25 s (pecks) / 0.33 s (hooks, the uppercut) real | his glove is in flight and the punch is not negotiable; whether it lands is decided by where the collider IS at the contact frame |
| HITSTOP | 0 — and the player's own glove animation freezes | 70 ms jab / 110 hook / 130 counter / 200 special, real | the 1984 impact frame; the render loop never stops, only the clocks (§9.2) |
| SLOW | 0.22 (a knockdown) / 0.10 (the KO) | 1.1 s / 2.0 s real | the fall is a story beat you watch, not a read |
| COUNT | 0 | up to 10 real s | the referee is not inside your fight (§5.4) |

`forceStrike(s)`, `forcePunch(s)`, `forceHitstop(ms)`, `forceSlow(rate, s)`, `forceCount()` are the
new entry points beside `forceHop → forceStep` and `forceDock → forceCorner`. The forced timers
run on REAL time, as today, and are held while the menu is open.

### 2.5 Hit-stop [new]
On a landed player punch the clocks stop for a real-time count (4 / 7 / 8 / 12 frames). The HUD,
the flash, the crowd and the sound run on real time (they are on that clock already). Visually:
the landing glove's stroke goes to a WHITE tint at α 1.3 (not alpha alone — §7.1 explains why a
saturated hue never whitens by alpha), the boxer's head outline goes WHITE at α 1.5 for 2 frames,
and the whole drawn scene — not the camera — kicks 6 px away from the punch and eases back over
the stop. On an additive display with no depth this is the only "in front" cue there is: the
brightest thing reads nearest, and for 4 frames the glove is the brightest thing on the glass.
**Never stop the render loop**: 60 Hz head-tracked presentation continues (the owner's judder
ruling), only the clocks stop.

### 2.6 The counter window after a PERFECT dodge [new]
- **PERFECT** = the collider was ON the incoming line when the strike began (the contact point was
  inside the capsule at the first strike frame) and OFF it at the contact frame. The dodge happened
  inside his 0.25–0.33 s strike, which is forced real time — so a PERFECT is a real reflex inside a
  quarter-second window **that the player chose the moment to open** (their move completed the
  tell). That is the cabinet-not-coin feel: memorise the tell, pick your moment, then hit the
  reflex.
- **CLEAN** = the collider was already off the line when the strike began (the dodge came out of
  the read). Safe, scored 50, no counter.
- On PERFECT the boxer STAGGERS on the wings and the uppercut (BOXER.md §5) and the exposed target
  flashes WHITE (the body after you ducked a hook, the head after you slipped a straight), the
  crowd swells, and a **0.6 real-second window** (0.8 EASY / 0.45 HARD) opens in which your next
  punch is a **COUNTER**: double damage, +10 on the KO meter, and its `Forced.PUNCH` is WAIVED — the
  world stays at the floor while you throw it, so the counter feels like the bullet-time hit the
  whole design is selling.
- The window is REAL time on purpose. The read is slow; the answer must be fast. On world time a
  still player could stand in a stagger for a minute and the fight is gone again. This is the loop
  that makes stillness feel like reflex rather than pause: HANG (read) → MOVE (dodge) → 0.6 s (hit)
  → HIT-STOP.

### 2.7 The telegraph at the floor
The boxer's wind-ups are authored in world time (BOXER.md §3: R1 peck 0.50 s, wing 0.67 s, uppercut
0.83 s), so a moving player at rate 1 still has a human reaction window and the hang stretches it
for the still one. What the frozen frame says, in strokes, additive, saturated (§7.4):

1. The GLOVE that is coming goes from its saturated red to a WHITE tint at α 1.5 and its hatch to
   full gain — the one thing on the glass brighter than everything else is the thing about to hit
   you. The pupils go white with it (the eye flash: which hand, and that it IS a punch).
2. The LINE: from the glove to the point on YOUR collider it is aimed at, with a bead that slides
   along it as the wind-up ages — x3discs' future path and bead, reused verbatim. The line is the
   dodge instruction: it says high or low, left or right, and a lean or a duck visibly takes the
   collider off it. The bead is world-fixed and the camera moves with the lean and the duck, so a
   correct dodge is visible as the bead sliding off your face — the diagram tells you you have
   already escaped.
3. The CARICATURE tell: the arm draws a long way back, the crest changes hue, the shoulder dips,
   the feet load — big shapes that survive a hang because they are a POSE, not a motion. A feint is
   a wind-up whose glove never goes white and whose bead never leaves the glove; at the floor a
   feint and a real punch look identical for the first 0.15 s of world time, then diverge — the
   read is about whether the glove lights.
4. ~~The REFLEX rail drains through the hang; when it empties the fuse ramps and he comes.~~
   **The hang and the fuse are gone (LAW.md §2).** The rail becomes TIME-TO-IMPACT on the
   committed glove, on the world clock (LAW.md §8.6) — the same instrument telling the truth
   instead of counting down a timer the player could not affect.
5. The SOUND doubles what the eye can lose: a ducking or leaning head can carry the crest out of the
   62° field, so the uppercut is crowed and the hooks are stamped (BOXER.md §3).

### 2.8 How a fight keeps feeling like a fight — the checklist
**REPLACED. The list below is the old one struck through in prose; LAW.md §4 is the current one.**
- ~~The idle floor is high (0.35)~~ → **The floor is 0.03 and never moves.** He never stands still
  *because you froze him*, and there is nothing to think about until he winds up — so the answer to
  the empty frozen frame is **DENSITY**, not a floor: he throws more, waits less, and a frozen
  player is looking at a committed glove ≈ 43 % of the round (CARD.md's COMMITMENT column).
- ~~The hang has a fuse (0.8 + 1.2 s): a read is never free for long.~~ → **The read is free for as
  long as the player wants it**, and what ends it is the player deciding to move. A hidden
  real-time clock that shortens a read is exactly what §0.1 rule 4 forbids.
- Punches force the world — **but a rate, not a rail** (LAW.md §5). You still cannot punch and then
  freeze to admire it; his counter still runs; it runs at 0.55 rather than at 1.0.
- Whiffs cost more than hits; hearts refill on world time (§4.4). **Unchanged and still correct** —
  the heart budget, not the punch's price, is what actually taxes mashing.
- The stagger's opening is measured in punches; the counter window is real time. **Both unchanged
  in meaning**, and the stagger's *number* moves (÷1.82, LAW.md §6) precisely to keep the first
  half of that sentence true.
- Everything atmospheric is on the real clock: the crowd's LOUDNESS, the bell, the announcer, the
  idle bob of your own gloves at the bottom of the frame (so a frozen boxer reads as your reflexes,
  not a paused game — widened to ±5 px because at a 0.03 floor this is the cue that separates
  "frozen" from "crashed"), the round card, the corner. **What is NOT atmospheric and moves with
  the clock is the hostile mix**: his whoosh, his stamp, his whistle and his tell cues are all
  pitched by `0.5 + 0.5 × timeScale` (LAW.md §9), so the world is audibly slow. The music never
  slows, and the contrast is the point.
- ~~Rounds are 60 world seconds with a real-time cap of 3:00~~ → **Rounds are 36 world seconds and
  there is no cap** (LAW.md §7). At a 0.03 floor the round clock stopped being a clock and became
  the player's world *budget*; the honest way to keep a round a round is to spend the budget, not
  to raise the floor behind them. 36 is the Rooster's opening phrase plus one full lap.
- STILL detection is repurposed as before, and the stall stays on **real** seconds — legally,
  because `Boxer.stall()` only counts while he is IDLE, so **a read is never on a real clock**
  (LAW.md §4.3). R3's forced real-time half-peck is deleted; it was the one hostile action in the
  game that ignored the law.

### 2.9 The two clocks — the audit list [changed]
Every timer in the game is on exactly one of these lists; `TwoClocksTest` proves the wiring on the
real engine, and the on-glass check is that the rail sits at the floor while the announcer keeps
talking.

**World time (`wdt`)**: the boxer's strip frames in TELL and RECOVER (12 fps held frames advanced
by `wdt`); his guard's re-close timer and the open-guard window; the STAGGER's length and its
per-hit extension; the feint frames; the special's 0.8 s guard-open; the ROUND CLOCK (60 s,
counting down on the plate); heart refill; his idle circling and taunts; the crowd's bob TEMPO
(it swings with the clock, its LOUDNESS is real); the ropes' shake after a knockdown.

**Real time (`dt`)**: ~~the hang and the fuse~~ **(deleted — LAW.md §2)**; every forced timer
(punch, hit-stop, slow, count, step, corner, getup — ~~strike~~ is gone with `Forced.STRIKE`);
the 0.6 s counter window; the player's glove animation, the answer word's pop, the flash ramps,
the panel brackets; the KO meter's pour (its target is set by world events, the fill animates at
1 point per 133 ms real); the crowd's loudness (it is the rate meter); the bell's ring, the
announcer, the corner's tip; the referee's count; the 3 s stall timers, **which count only while
he is IDLE**; ~~the 3:00 real cap~~ **(deleted — LAW.md §7)**; music. Everything that belongs to
the player, the referee or the story.

**After LAW.md there are exactly TWO real-time timers that can change the outcome of a fight** —
the 0.6 s counter window and the stall — and both are drawn where the player can see them
(LAW.md §10). Everything else on the real list is feedback, cinema or atmosphere. `TwoClocksTest`
should gain a case that fails if any *hostile* timer takes `dt`, with those two as its declared
exceptions; that test is the only cheap way to keep this list honest through a fight's worth of
churn.

Two additions to the **world** list that follow from LAW.md §8: the glove **trails** (sampled on
`wdt` and faded by age in world seconds, so a frozen glove keeps its arc) and the **debris** —
sweat, crest sparks, impact stars — which move off `dt` onto `wdt` so they hang in the air when
the world stops. The *flash* at the moment of impact stays real: it is the plate's feedback to the
player, and it belongs to them.

---

## 3. THE BODY AND THE COLLIDER

### 3.1 The signals [on disk]
`MotionTracker`: `TYPE_GYROSCOPE` at 10 ms, `TYPE_LINEAR_ACCELERATION` at 10 ms, `TYPE_GRAVITY` at
20 ms on its own thread, smoothed on the GL thread. Device frame measured on the glasses: +X right,
+Y up, +Z forward along the look axis. `roll = atan2(−gx, gy)` (+ = the top of the head goes
right), `pitchG = −atan2(gz, √(gx²+gy²))` (+ = looking up), both τ 60 ms, drift-free. The rest
posture `(restRoll, restPitch)` is taken at the coin tap and the triple-tap **[on disk]** and —
**[new]** — re-declared at every ROUND CARD while the head is still (§14: twenty taps on one temple
push the frame on the nose, and the gravity references drift with it; yaw is already re-declared
there by `HeadTracker.recentreYaw`, which does not touch pitch).

### 3.2 The inferred body [on disk shape; DODGE SENSE new]
```
r        = roll − restRoll
k        = clamp((|r| − LEAN_DEAD) / (LEAN_FULL − LEAN_DEAD), 0, 1)     LEAN_DEAD 0.07 rad (4°)
leanX    = sign(r) × 0.55 × smoothstep(k)                                LEAN_FULL by DODGE SENSE
duckAmt  = clamp((restPitch − pitchG) / DUCK_FULL, 0, 1)                 DUCK_FULL by DODGE SENSE
headX    = bodyX + leanX          bodyX = the step's lunge (0.55 m over 0.35 s out, 0.75 s back)
headY    = 1.65 − 0.42 × duckAmt
collider = a capsule from (headX, headY + 0.12) to (headX, headY − 0.95), half-width 0.26
```
`DODGE SENSE`: LOW = `DUCK_FULL` 0.50 rad (29°) / `LEAN_FULL` 0.38 rad (22°) [on disk]; **MEDIUM =
0.38 / 0.28 rad (22° / 16°), ships**; HIGH = 0.28 / 0.21 rad (16° / 12°). The camera moves with all
three (`camX = headX`, `camY = headY`), horizon counter-rolled with `ROLL_COMP = 1`, τ 0.08 s —
the owner's ruling from x3discs ("tilting should also give a slight dodge effect") is kept: you see
yourself dodge because the world slides behind your fixed gloves.

### 3.3 The dodge is geometric, never a threshold
A punch is thrown at where the collider WAS when the tell committed (the bead is placed then, on
the aim point in world space); whether it lands is decided by where the collider IS at the contact
frame. Authoring rule so the measured body works, in the boxer's aim bands (BOXER.md §3):

- A HEAD-HIGH straight (a peck) sweeps a glove of radius 0.24 m across a band 1.50–1.80 m high at
  `headX ± 0.10` at the throw. A full slip (0.55 m) clears it by 0.19 m; a half slip (0.28 m) is a
  GLANCE (half damage, no knockdown check); a full duck (eye 1.23, capsule top 1.35) clears it; a
  0.6 duck (top 1.52) glances. So the peck's cheap answer is the slip; the duck is safe but earns
  no PERFECT credit — the game says so with the feedback word.
- A HEAD-HIGH HOOK (the right wing) sweeps ±0.60 m — no slip clears it — across a band authored
  5 cm HIGHER than the pecks', **1.55–1.85 m**, so that a duck of 0.6 (capsule top 1.65 − 0.25 +
  0.12 = 1.52) clears it by 0.03 m and a duck of 0.5 (top 1.56) GLANCES; a step clears it. The
  band is the reason the hook's answer is the duck and the peck's is the slip, and it is why
  DODGE SENSE scales the duck: at MEDIUM a 0.6 duck is 13° of nod.
- A BODY HOOK (the left wing) sweeps ±0.60 m at 1.00–1.35: the capsule runs to 0.95 m below the
  eye, so nothing but the GUARD or a STEP answers it, and a duck DROPS THE HEAD INTO it (the head
  band 1.23 + 0.12 = 1.35 — inside): the fatal answer, by geometry.
- THE UPPERCUT rises through a column `headX ± 0.25`, from 1.00 to 1.90: it catches a ducked head
  as surely as a level one; any slip ≥ 0.30 m (leanX ≥ 0.30, ≈ 10° at MEDIUM) clears its edge; a
  step clears it. In round 3 the column TRACKS `leanX` for the first 40 % of the strike (BOXER.md
  §6), so only a slip begun inside the strike, or a step, escapes it.
- **Nothing is escapable only by a body the glasses cannot feel**: every attack has a pad answer
  (guard or step) as well as a posture answer — the same guarantee as x3discs' lanes.

### 3.4 Steps [on disk recogniser, changed lunge]
The four-gated velocity-crossing recogniser is unchanged (`V_STEP` 0.45 / 0.32 / 0.22 by STEP
SENSE, the turning / leaning / nodding / push gates, 550 ms refractory, 150 ms pad blank). A
recognised step is the same verb as the swipe: `Forced.STEP` 0.35 s, `bodyX → ±0.55` out over the
forced time and back over 0.75 s real. A step is blanked for 150 ms after any pad touch on either
temple — a slap's |a| 3.25 m/s² exceeds `STEP_PUSH` 1.2 and would integrate into a phantom step —
and therefore for the whole of a flurry. Acceptable: STEP is a swipe verb first and a body verb
second, and the corner tells the player once if three body steps are rejected during blanks
("Don't punch and run").

---

## 4. THE FIGHT RULES

### 4.1 Health
| | HP | notes |
|---|---|---|
| ROOSTER | **120** in round 1; at each bell set to `max(current, 72)` (R2) / `max(current, 42)` (R3) — he recovers in his corner, never fully | knockdown ladder at crossing 80 / 40 / 0 (§5.1) |
| YOU | **100**; +30 in each corner (cap 100); rise from a knockdown with 40 | three knockdowns in the fight = the loss |

Damage on you (R1): peck 8 / 10, right wing 15 (+ a 0.3 s stun: the plate's strokes jitter 4 px),
left wing 12 — **18 if you ducked into it** —, uppercut 25. Into the GUARD: pecks 2, a wing 7 and
the guard is crushed (forced down 0.4 s real), the uppercut is unblockable (25). GLANCE = half.

### 4.2 Your punches
| Punch | to land | recovery | damage on an open guard | notes |
|---|---|---|---|---|
| LEFT jab | 0.15 s real | 0.13 s | 6 (head) / 8 (body) | the fastest: the stun tool, the 1-2's first beat; 0.28 s in all (§2.3's JAB) |
| RIGHT (the cross) | 0.18 s | 0.18 s | 8 (head) / 10 (body) | heavier; the second beat; 0.36 s in all (§2.3's HOOK) |
| 1-2 (alternate hands) | — | overlapped | — | a 1-2 lands every 0.18 s, the same hand every 0.30 s: the one-two is taught by feel |
| in STAGGER | | | × 2 | |
| COUNTER (inside the perfect window) | | | × 2, +10 meter, `Forced.PUNCH` waived | |
| SPECIAL (both pads, meter lit) | 0.30 s | 0.30 s | 35 on an open guard; in STAGGER = knockdown; on a CLOSED guard 0 but it knocks the guard open 0.8 s world | the meter is spent to 4 (the seed); `Forced.PUNCH` 0.60 s; hit-stop 200 ms |

Accuracy drops with motion: a punch thrown while `m > 0.6` has a 25 % chance to WHIFF past his
head (the anti-wiggle rule for this game — x3discs' "throw spread grows with m"; a shaking head
cannot aim). Punches thrown while ducked below 0.35 go to the body (§1.3); the body is never
covered by his gloves, so a body blow lands through a closed guard and OPENS it (§4.3).

### 4.3 His guard
Neutral stance: both gloves up over his face, drawn overlapping his jaw — the additive overlap
makes the closed guard visibly BRIGHTER, which is the readable "closed" state for free (§7). While
it is up, head punches do 0, cost a heart (§4.4), spend the full whiff-length `Forced.PUNCH`, and
draw a cyan spark at his glove with "That all you got?" at most once per 6 s real. The guard OPENS:
1. on a BODY BLOW landing — his gloves fly out for **0.6 s world** (R1; 0.5 / 0.4 in R2 / R3), every
   head punch in that window lands, and a second body blow inside it converts the window into a
   STAGGER (BOXER.md §5);
2. on a PERFECT dodge — for his whole RECOVER, with a STAGGER on the wings and the uppercut;
3. on a GUARD-COUNTER — blocking his body hook bounces his glove: open 0.6 s world (+150);
4. briefly (0.2 s world) at the end of every RECOVER before it re-closes — the greedy player's
   window;
5. by a SPECIAL into the closed guard: open 0.8 s world.

The guard re-closes on WORLD time, so it closes exactly as fast as you spend time punching: **the
guard closes on your own punches.**

### 4.4 Hearts — the punch budget
Three hearts, drawn at the bottom of the plate (§8). The idiom is the NES sequel's rather than the
1984 cabinet's (the cabinet had no hearts); it is here because it is the honest cost of a whiff on
a clock the player owns, and the brief asked for it.

| event | hearts |
|---|---|
| a punch into his closed guard, or into air (a WHIFF) | −1 |
| you are hit (any damage, not a glance) | −1 |
| a knockdown of you | all three |
| refill | +1 per **1.0 s of world time** with no punch in flight; ALL on a PERFECT dodge; ALL at the bell |
| at zero: WINDED | no punches until one refills (the gloves on the plate dim and `WINDED` shows) — a still player does not heal |

### 4.5 The KO meter — the cabinet's economy [after the 1984 arithmetic]
One bar on the fight plane, filling from the RIGHT toward a `KO` box at the LEFT end, exactly as
the cabinet drew it (§8). Range 0..30; LIT (KO-capable) at **26+**; the box flashes and the corner
shouts `put_him_away`. Its target moves on world events; the fill animates toward the target at
**1 point per 133 ms real** so a combo visibly pours in and the player may wait a beat for the
flash.

| event | meter |
|---|---|
| the first hit of a sequence | +2 |
| every subsequent hit in an unbroken sequence (nothing blocked or dodged by either side in between) | +5 |
| a COUNTER | +10 |
| a PERFECT dodge | +3; a CLEAN dodge +1 |
| your punch blocked by his guard | −1 (no loss once LIT) |
| your punch dodged (he slips a punch during his own dodge frames) | −2 |
| you are hit | −8 (peck) / −12 (a wing or the uppercut) |
| a knockdown of either fighter | reset to 0 |
| the SPECIAL lands | spent to 4 — the seed; one big beat per cycle |

The cabinet's "one free KO punch" rule becomes a legibility rule: the special lands for full effect
only on an open guard, in a stagger, or while he is mid-action (the tell or the recover); into a
closed idle guard it only opens the guard. So the meter is a rhythm gate, not an ammo store: you
earn one big beat per fill and you spend it on a telegraph.

### 4.6 Stun
A stun is the arcade's counter-hit payoff, kept in one form: a RIGHT thrown over his incoming LEFT
PECK before its strike frame — the attract demo's lesson — lands, interrupts the peck, and STUNS
him for 0.6 s world (hands low, dazed eyes) with every hit inside it resetting the stun's timer up
to a cap of 1.6 s. Hitting him early during any other tell interrupts it but does not stun. A body
blow WAKES a stunned boxer (his hands come up) — the cabinet's rule, kept because it makes the
head/body axis a decision inside a stun rather than a free mash.

---

## 5. KNOCKDOWNS, THE COUNT, ROUNDS, THE TIMER, THE SCORE

### 5.1 His knockdowns
- He goes down when his HP crosses **80, 40 and 0** (KD1 / KD2 / KD3), and on any SPECIAL landed in
  a STAGGER regardless of HP, and on a COUNTER into a perfect-countered uppercut.
- The fall: `Forced.SLOW` 0.22 for 1.1 s real — he falls toward the camera, glove up, then flat
  (`knockdown` strip) — then `Forced.COUNT`: the world at rate 0, the referee's numerals on the
  real clock, one per second, each popping 0.5 → 5.0 over 80 ms with a click, spoken
  (`ref_1`…`ref_10`). The crowd counts along from 5.
- He RISES at **4 (KD1), 7 (KD2), 9 (KD3)** — dramatised: he pushes up on 3, slips, up on the count
  — UNLESS the knockdown came from a SPECIAL in a stagger or a COUNTER on his uppercut, in which case
  he does not rise: **KNOCKOUT**. A fourth knockdown in the fight is always a KO. Three knockdowns in
  ONE round stop the round: **TKO** (the cabinet's three-knockdown rule).
- On rising: HP set to 25 % of max, and for 2 s world he throws only pecks (a rest for the player
  and a chance to dodge for the meter); after KD2 his crest has lost two spikes (BOXER.md §4).
- A knockdown resets the KO meter to 0 and refills your hearts. The count is the only clock in the
  game the body does not own, and the fiction says why: the referee is not inside your fight.

### 5.2 Your knockdowns
At 0 HP you drop: the view sinks 0.4 m over 0.4 s real, the ropes rise past the frame, the count
runs in real time, and you RISE by alternating left / right taps — **8 alternated taps before
"10"** (a same-hand double counts once; taps are urgent on the arena so they count at the lift).
Rising sets HP 40, hearts 3, meter 0. The third knockdown in the fight = the loss → `GAME OVER` →
`INSERT COIN TO CONTINUE` (same fight, round 1, score reset to 0, 9 s countdown; unlimited — the
score chase stays pure, a coin buys the fight back).

### 5.3 Rounds and the bell
Three rounds — `THE STRUT`, `THE RUFFLE`, `THE COCKFIGHT` (BOXER.md §6) — of **36 world seconds**
each *(was 60; LAW.md §7)*, the ROUND CLOCK counting DOWN on the plate and visibly stopping when
you stop. The bell is on the world clock: three strokes to start a round, one to end it, three fast
for a KO; a wood-block clapper on each of the last **8** world seconds while the ropes pulse
("hurry up", and the moment to spend a lit meter). ~~A real-time cap of 3:00 per round~~ —
**deleted.** At a 0.03 floor the round clock stopped being a clock and became the player's world
BUDGET, so the honest way to keep a round a round is to spend less budget, not to raise the floor
behind their back; and the old cap was guaranteed to fire against precisely the careful player the
design wants. 36 world seconds is the Rooster's opening phrase plus one full lap of his rotation,
and the plate will therefore read `0:36` — an arcade cabinet's round clock has never been real
seconds. No knockout by the end of round 3 = **`TIME - NO DECISION`**, which is a
loss (the cabinet's rule: only a KO wins) and offers the continue.

Between rounds — **THE CORNER**: `Forced.CORNER` 2.2 s real (the ropes slide you to your corner;
when the world moves, time moves), +30 HP, hearts 3, and the corner man's ONE sentence, chosen from
what you were hit by most that round (§10). **The corner is a CONSTANT length whenever a tip
speaks — `CORNER_HOLD_TIP_T` 3.4 s, not `max(CORNER_T, tipMs + 1.05)`** — so the player learns its
rhythm instead of getting a different rest depending on which mistake they made; and the same
number is passed to `clock.forceCorner(cornerHold)`, because today `update()` gates on
`clock.forced != CORNER && stateT >= cornerHold` and the last ~1.2 s of a stretched corner runs
UNFORCED, answering the player's motion. A rest that is not a rest (VOICE.md §9). Round cards: `ROUND 2` + the round's name, 1.2 s, the
yaw and the rest posture re-declared while the head is still, then the bell.

### 5.4 The KO and the tally
`KNOCKOUT` (`(320,175)` sc 4.0), the bell three times, the crowd roars 2 s real, the announcer's
`winner_ko`, then the tally: `TIME 1:23` (real), `HITS 41`, `PERFECTS 6`, `KNOCKDOWNS 3`,
`TIME BONUS 5400`, `SCORE 18950`. The boxer's `ko` strip holds (tongue out, X eyes, crest flat);
the ring's seams flip from the fight's magenta to the player's acid green. A tap after 1.2 s → the
title, with `BEST KO 1:23` on the records line.

### 5.5 The score
The score rewards the game's own verbs, in this order of magnitude, so the table teaches the fight.

| | points |
|---|---|
| landed head punch / body blow through the guard | 100 / 150 |
| punch in STAGGER | × 2 |
| CLEAN dodge (any safe answer) | 50 |
| PERFECT dodge | 300 (+3 meter, hearts refilled) |
| COUNTER landed | 300 |
| GUARD-COUNTER (the body hook blocked) | 150 |
| STEP that clears a double | 400 |
| SPECIAL landed | 1000 |
| KNOCKDOWN | 2000 |
| KNOCKOUT | 5000 |
| TIME BONUS at the KO | **10 000 × max(0, 1 − worldSeconds / 108)** — *(changed; LAW.md §7)* 108 is three full 36-second rounds, so the bonus reads as the fraction of the sanctioned WORLD time you gave back. It needs a new fight-long accumulator (`fightWorldT += clock.wdt`), because `Clock.worldT` resets at every bell |
| multiplier | consecutive dodges without being hit: × 1 … × 4, shown as the rope glow and `X3` on the plate; reset on any hit or a punished wrong-side lean |
| punches on a closed guard | 0 |
| a wrong-side lean that he punished (the pattern's branches, BOXER.md §7) | −50, so the branches are legible in the tally |

The law gives you all the time you want; the bonus asks what you did with it — **and it must ask
in world seconds.** Scoring on real seconds was a hidden punishment for playing the game the way it
teaches: under any deep floor a thoughtful fight takes many real minutes, so the old bonus was
structurally zero for exactly the intended player. On the world clock it becomes a reward for
economy of movement, which is the thing the whole design is about. Records: `HIGH`,
`BEST KO` (the fastest real time to a knockout — that one stays real, because it is a boast about
a run and not a judgement on a play style), `FIGHTS` — never written from a debug launch
(`SettingsStore.recordsEnabled`, as on disk). Whole-run multiplier EASY 0.75 / NORMAL 1.0 / HARD
1.5.

### 5.6 Difficulty
**The clock is not on this table any more** (§0.1 rule 1, LAW.md §1.2): `HANG_T` is deleted and the
floor is `FLOOR_STILL` 0.03 on all three rows. Nobody gets a faster world for being on HARD, and
a statue faces the same nearly-frozen world in round one of the Rooster as in round three of the
Metronome. Everything else on the row is untouched, and there is plenty of it.

| | EASY | NORMAL | HARD |
|---|---|---|---|
| ~~the hang `HANG_T`~~ | — | — | — |
| ~~the deep floor~~ | 0.03 | 0.03 | 0.03 |
| the counter window | 0.8 s | 0.6 s | 0.45 s |
| his tells (world) | × 1.2 | × 1.0 | × 0.8 |
| his HP | 100 | 120 | 140 |
| damage on you | × 0.8 | × 1.0 | × 1.2 |
| feints | R3 only | R2 on | R1 on |

Default EASY until the first knockdown has ever been scored on this device (the suite's rule:
EASY until the first clear).

---

## 6. THE STAGE

- **The ring** [new `ring.json`]: a 6 × 6 m canvas at y = 0 centred 2.6 m ahead, so you stand
  inside it near your own rope; the boxer at `(0, 0, −2.6)`. Four posts (1.3 m, 4 strokes each),
  three ropes a side, each rope a 6-segment catenary sag (a straight rope reads as a fence),
  turnbuckle X's, the apron edge, a 6 × 6 canvas grid at α 0.18. ≈ 180 segments, BLUE. On a
  knockdown the ropes SHAKE: a 0.4 s decaying impulse on the rope group's sway (§12.5) — zero
  geometry. The multiplier is the rope glow (α 0.6 → 1.0 by × 1 … × 4).
- **The crowd** [new `crowd.json`]: three rows behind the far ropes at 6 / 7.5 / 9 m, 40
  silhouettes a row, stroke LOD by row (8 / 5 / 3 strokes: head arc + shoulders, then a hump),
  ≈ 640 segments, VIOLET at α 0.25 → 0.60 by the crowd meter. It sways in the vertex shader
  (§12.5): amplitude from the crowd meter, a phase across x so it reads as a wave, floor-clamped
  so feet stay planted. A standing ovation on a knockdown and the KO: amplitude 0.2, α 0.6, the
  front row bounces. The crowd costs the CPU nothing.
- **The referee** [`referee.json`, rebuilt 2026-09-10]: **a man, 92 strokes** at the far-left post
  — a face, a white shirt with a collar, a bow tie, short sleeves, a belt, doubled arms with
  fists, slate trousers and shoes. He was nine strokes (a diamond head, one line per limb) on the
  argument that at 5 m nothing more reads; that was wrong twice over, because a stick figure
  standing beside a fully drawn caricature does not read as economy, it reads as a placeholder,
  and the eye goes to it *because* it is wrong. He stays out of the boxer's way by TONE, not by
  poverty: shirt and face at white, trousers and shoes at a dim slate `(0.45, 0.55, 0.68)` /
  `(0.34, 0.42, 0.55)`, the whole man drawn at α 0.4 (title) / 0.55 (round) / 0.95 (count), and
  nothing on him ever flashes. On a knockdown he slides to centre over 0.6 s and alternates
  arm-up / arm-down per count — a `Pose.roll` on `arm_R` about the right shoulder `(0.20, 1.42)`
  swings the whole arm, elbow bend and fist included, with no strip; the numerals are the plate's.
- **Eye** 1.65 m standing [on disk]. The near plane is 0.15 m and there is no clipping; nothing in
  the world is ever drawn nearer than 0.30 m to the eye — the boxer's extend frame stops at 1.2 m
  and his glove growing 1.8× is how "it reaches you" is drawn (§7.5).
- Head look never stops: not during the count, not during the corner, not during the tally. A yaw
  beyond ±15° means the player has looked off him — never punished; the fight dims to α 0.5 and
  he re-squares to the player's yaw over 0.3 s when they look back ("you can't look away from him;
  he follows").

---

## 7. THE LOOK

### 7.1 Two facts of the pipeline that shape everything
**(a) Alpha > 1 does nothing at all, and alpha never whitens a hue.** *(Corrected 2026-09-10;
commit `b44b86d` changed the premise and the first draft's arithmetic was left behind.)* The
fragment now writes `fragColor = vec4(vColor.rgb, a)` — **not** premultiplied — and the blend is
`SRC_ALPHA, ONE` onto an RGBA8 surface, which clamps every fragment component to [0, 1] *before*
blending. So a stroke adds `clamp(rgb) × clamp(a)`, the ladder in α is **linear** (it used to be
squared, which is why everything dim read as murk), and **α 1.5 is byte-for-byte α 1.0**. MAGENTA
`(1, 0.15, 0.60)` at any alpha is magenta, and a zero channel stays zero. A stroke that must read
WHITE is drawn with a WHITE **tint** (§12.4 makes that a per-part colour write), never with alpha —
the conclusion the first draft reached by the wrong route, and the one place its number
(`α 1.5`) should be read as "a WHITE tint at full gain". Anything tuned by eye before `b44b86d`
was tuned against the squared curve and must be re-derived rather than reused. **(b) Brightness is
order-independent** — `min(1, Σ)` in any order — so draw order never matters and "brighter reads as
nearer" has no exceptions to manage.

### 7.2 Palette (fully saturated, black-is-room, suite-consistent)
Linear stroke units as `Hud.kt` defines them; reuse the constants, add nothing off-suite.

| Role | Colour | Core / flash |
|---|---|---|
| The boxer's outline (head, body, arms) | MAGENTA `(1.00, 0.15, 0.60)` — the suite's threat hue, ruled on-head against red and orange | on a hit: WHITE tint α 1.5, 2 frames |
| His gloves | RED `(1.00, 0.22, 0.18)` | the punching glove: WHITE α 1.5 for the whole tell; the extend: gain 1.6 |
| His CEL FILLS (head, torso, trunks, both gloves) | the part's own tint, flat, at 0.52 × the cel ramp (0.40 shade → 0.85 centre → 1.35 lit) | follows the tint table, so the telegraph glove and the impact white fill too |
| His colour blocks (hatch) | the part's hue at gain 0.20 (skin) / 0.30 (gloves, trunks) — down from 0.35 / 0.55 now that the fills carry the colour | never flashes, except the telegraph glove's hatch → 1.0 |
| His eyes | WHITE `(0.92, 1, 1)` rings, MAGENTA pupils | pupils → WHITE α 1.4 on a tell |
| His crest | VIOLET `(0.60, 0.20, 1.00)` | GOLD `(1.00, 0.78, 0.35)` for a hook, WHITE for the uppercut, AMBER dim when he waits you out |
| His trunks | CYAN `(0.35, 0.95, 1.00)`, filled, hatch at 0.30 | |
| Your gloves and forearms | ACID `(0.50, 1.00, 0.20)` at α 0.9 — the cabinet's green wireframe | on impact: WHITE α 1.3, 2 frames |
| Your special | AMBER `(1.00, 0.78, 0.35)` + a 12-point WHITE-GOLD starburst | |
| Ropes, posts, canvas grid | BLUE `(0.30, 0.50, 1.00)` at 0.6 / 0.18 | the multiplier glow |
| The crowd | VIOLET at 0.25–0.60 | |
| The referee, the count | WHITE at 0.55 / 1.0 | |
| The telegraph line and bead | RED at α 0.4 × (1 − rate / 0.35); the bead WHITE | |
| The answer word | MAGENTA outline + WHITE core | |
| Impact stars, onomatopoeia | WHITE-GOLD `(1.00, 0.90, 0.50)` | |
| Damage frame | `(1.00, 0.20, 0.15)` [on disk `Hud.DAMAGE`] | |
| Bezel | the fight tint (MAGENTA) at 0.30 / 0.16; ACID after the win | |

**The contrast rule for the whole frame: exactly one thing is WHITE at a time** — the telegraph
glove during a tell, his head for 2 frames on a hit, the count. Everything else stays in hue. That
is what "high-contrast telegraph" means on an additive display: not brighter, but the only white.
Damage never dims to grey: he loses STROKES (crest spikes), never saturation.

**And the rule that keeps that true once the figure has flat colour under it: when a part flashes
WHITE, its OUTLINE goes up and its FILL goes DOWN (× 0.25).** Never flash a fill. Measured on the
engine-exact desk renderer: a tell today is 1 723 whiteish pixels; with a fill present and the
glove's fill left alone it is 1 754 (+1.8 %); with the glove's fill flashed white too it is 5 321
(× 3.1), and a head hit becomes a 7 961-pixel white **egg** with the eyes, brows and crest lost
inside it. Dimming the fill instead gives 1 548 px and reads as the comic's impact panel. The
extend goes **hotter in its own hue** (× 1.3), never white. This matters more under LAW.md than it
would have before: a frozen frame is on the glass for *seconds*, so a white slab that was
forgivable for two frames at rate 0.35 is not forgivable at 0.03.

### 7.3 The boxer: a 2D stroke sprite, caricatured, billboarded
A flat figure in its own X–Y plane, **1.9 m tall at 2.6 m** (40° of a 48°-tall plate: he fills
the eye top to bottom with a margin, his head near the top where the telegraph lives), yawed each
frame to face the camera POSITION (`atan2(camX − x, camZ − z)` in the model −Z convention
`StrokeModel.kt`'s KDoc pins). The eye only moves by the lean (±0.55 m) and the step, so the
billboard's yaw changes by at most `atan(0.55 / 2.6) ≈ 12°` — enough parallax to sell the dodge,
not enough to expose the flatness.

Proportions (model metres, Y up, faces −Z): the head **0.70 m tall × 0.56 wide — 37 % of the
figure** (the cabinet's ratio pushed), chin at 1.20 m, crown at 1.90; eyes two ellipses 0.16 ×
0.11 m at (±0.12, 1.60), pupils 0.045; brows single 0.18 m strokes whose ANGLE is the whole
expression channel (flat idle, V wind-up, inverted V hurt, raised taunt); a 0.24 m mouth polyline
with five variants swapped per frame (grin, flat, gasp-O, grimace with six teeth strokes, the
crow's rectangle); jug ears, a broken-nose zigzag, a six-stroke five-o'clock-shadow hatch on the
jaw; THE CREST: five tall spikes rising from the forehead (BOXER.md §1); neck 2 strokes; a tiny
torso 0.50 m tall, 0.50 wide; trunks with a waist stripe; legs 2 × 3 strokes, boots 2 × 5 (the
feet are drawn because the hooks' tell is the feet); arms 0.28 + 0.28 m, each a doubled contour;
**gloves r 0.24 m** (a third of the head — pumpkin fists), 24-gon outlines, 4 lace strokes, a
thumb bump.

- **Thick comic outlines are doubled contours** — two parallel polylines 1.5 cm apart (three for
  the gloves), not `glLineWidth`. At 2.6 m the plate resolves 10.3 px/°, so the two cores sit
  3.4 px apart and their 4-px halos fuse into a ≈ 7-px ink bar with two bright rails: an outline
  three times the weight of an interior stroke, on any driver, at every distance in the fight band.
  Interior detail (brows, mouth, laces, seams) is single-weight. The weight difference IS the comic
  look; keep it strict.
- **Colour blocks are CEL FILLS** (2026-09-10, the owner: *"can the characters be filled with cell
  shading effects to give them more comic book color?"*). A stroke renderer has no fills, and the
  first answer was hatching — which is what a printer does when it cannot print colour. On a
  waveguide, where black is nothing at all and the picture is its own light, a flat block of
  saturated colour is the cheapest thing there is, so the flats are real now and they are built
  from geometry that already exists. `GLRenderer.fillPass` takes the five parts that are ONE
  closed loop about ONE centre — `head`, `torso`, `trunks`, `glove_L`, `glove_R` — and fans each
  from its own centroid, one `GL_TRIANGLES` triangle per segment, which needs no ordering
  assumption at all (a star-shaped loop fans correctly however its edges arrive). Two tones, per
  vertex: the outer ring runs 0.40 (shade) → 1.35 (lit) across the part's own width against the
  ring's key light and the centroid sits at 0.85, so a flat reads as a body with a lit side. The
  tone is divided by the contour's loop count (2, 2, 1, 3, 3) because the comic outline is drawn
  two or three times over. Colour comes from the TINT TABLE, not the asset, so the fighter's
  palette, the telegraph glove's white and the two-frame impact white all fill too — a filled
  white mitt is the most legible telegraph in the game. Cost: ≈ 750 vertices, no measured frame
  time, no new authoring in Blender. `boots` and `ears` are excluded: two loops with the centroid
  in the air between them, and a fan there would web the gap.
- **Hatching is now texture, not colour**: parallel strokes at 2.5 cm pitch (≈ 5.7 px) inside a
  contour, on a `hatch_<part>` object sharing the part's pivot, at gain 0.20 (skin) / 0.30
  (gloves, trunks) — down from 0.35 / 0.55, because the same numbers over a real flat read as
  scribble over paint;
  the shadow side cross-hatched (more ink = brighter here, and the eye still reads density as
  shading). Held constant over distance by `gain × √(2.6 / d)`. Cap: 320 hatch segments, and hatch
  never flashes — it is the first thing that can flood the whiteish-pixel test.
- **Pose strips, frames HELD**: every pose is a complete drawing baked per frame at **12 fps**, not
  per-part transforms interpolated at runtime — a wind-up squashes the head, a hit stretches it, a
  mouth changes shape, a glove grows as it comes at you; shape changes a transform cannot make, and
  the cabinet's artists drew every frame. Frames advance on WORLD time (`wdt`), so at the floor the
  telegraph frame hangs on the glass. Why stepping at 12 does not judder: the camera is
  head-tracked at 60 Hz and the sprite's placement is evaluated every frame; only the drawing
  inside the sprite steps, and the SECONDARY motion — the idle sway (±2° roll, ±3 cm bob on
  `sin(worldT)`), the stagger wobble, the hit squash (a non-uniform scale pulse) — is the model
  matrix at 60 Hz, so the figure never looks frozen on a held frame. The strip list is BOXER.md §8.
- **Markers**: every frame carries named points — `glove_L`, `glove_R`, `chin`, `body`, `eye_L`,
  `eye_R`, `crown` — for the telegraph line's origin, the hit spark, your punch's target, the
  stars and the motion trails (the last TEN `glove_*` positions on world time, drawn as
  segments fading by AGE IN WORLD SECONDS: speed lines with zero authoring that **hang when time
  freezes**, which is the whole point — LAW.md §8.1. The first draft faded them by rate and deleted
  them under 0.35, i.e. it removed the one cue that makes a frozen projectile legible at exactly
  the moment it becomes legible.)

### 7.4 The telegraph, in stroke terms — four beats, four channels
1. **The flash** (strip event `telegraph`): the punching glove → WHITE α 1.5, its hatch → 1.0, both
   pupils → WHITE, ramped in over 30 ms real, held for the whole tell on world time. The one white
   thing on the glass.
2. **The arc** (only while `timeScale < 0.35`; the fade is now `α 0.4 × (1 − rate / 0.35)` with
   the *floor* at 0.03 rather than 0.35, so it is at full strength through the whole frozen read):
   a 12-segment line from the `glove_*` marker to the aim point on your inferred body, RED, a WHITE
   bead (a 0.16 m cross + a point) on the target. A still player reads a diagram; a moving one watches a weapon.
3. **The word** (`CAPTIONS ON`, the first two appearances of each attack only — the cabinet
   teaches, then shuts up): `< LEAN` / `LEAN >` / `DUCK` / `BLOCK` / `STEP` at `(320, 330)` sc 3.0,
   popping 0.5 → 3.0 over 80 ms real, held on world time until the strike, dropped over 120 ms;
   MAGENTA drawn once at α 1.0 then WHITE at α 0.6 on top. The word names the ANSWER in the
   player's frame, never his hand: "LEFT!" would be ambiguous on a plate where left is a direction
   you lean.
4. **The extend** (from `strike`, 2 frames): the glove drawn 1.8× in the strip, its gain 1.0 → 1.6,
   and the wide pass for this draw at `glLineWidth(min(6, maxLine))` — the one place a width bump
   is spent, on two frames. A hit on you: the damage frame + the 0.18 camera shake + the plate's
   4-px stroke jitter. A miss: the glove passes the plate's edge and a 3-stroke whoosh trail fades.

Nothing strobes: every flash is a single ramp (attack 30 ms, hold, release) and every pulse is
`Hud.blink`-style between a floor and a ceiling — the plate's "nothing blinks to zero" rule and the
3–30 Hz photosensitive band apply (the `KO` box blinks at 4 Hz between 0.4 and 1.2).

### 7.5 The time law's look
At the floor: the frozen frame + the arc + the word + **comic panel brackets** at the plate's four
corners (L-shapes 40 px, inset 20 px, MAGENTA α 0.3) fading in over 200 ms real as the rate falls
under `FREEZE_MARK` 0.15 and out as it rises. The frozen fight is literally a panel. Time running
(rate > 0.7): contours thin, hatch off, the idle sway doubles, the crowd's sway runs at full
amplitude, the trails stretch. The world is loud because you are.

**One gate added, and it is not cosmetic (LAW.md §8.4).** Under the corrected law the frozen state
is the DEFAULT, so a marker of the default state carries no information and the brackets become
wallpaper. They come in only when rate < 0.15 **AND there is something to read** — a committed
strike in flight, or a tell past its telegraph frame. That gate does a second job for free: the
floor no longer snaps from 0.35 to 0.06 when he winds up, so the telegraph has lost its first
beat, and the brackets arriving IS that beat.

This is also the answer to the owner's request for comic-book colour, and the two requests share
one mechanism: **the world becomes a drawn panel when it freezes and a smear when it runs.**

### 7.6 The player: green wireframe gloves on the plate
Your gloves live in PLATE space (the ortho HUD batch), not in the world: they are your own hands
and must move with the head (plate space is head-locked for free); the near-plane rule never
arises; and it is exactly the cabinet's screen-space transparent boxer — the arcade's wireframe
existed BECAUSE its cheapest sprite was 2bpp and transparent, and on an additive waveguide the
boxer sums through your gloves for nothing. ACID at α 0.9, single-weight strokes (you are the
light thing; he is the ink thing).
- Rest: glove L centred `(170, 410)`, glove R `(470, 410)`, each a 16-gon r 44 px + thumb + two
  lace strokes + a forearm as two parallel strokes down off the plate's bottom (≈ 40 segments a
  hand). The **AIM LOW** state (§1.3) drops both gloves to `(225, 325)` / `(415, 325)` at 82 %:
  the gloves fall with the head, and that is how the player sees which level is armed.
- A punch is parametric: the glove translates along a quadratic from rest to the TARGET — his
  `chin` or `body` marker projected onto the plate with `project()` — over 90 ms real out and
  160 ms back, scaling 1.0 → 0.7 as it goes into the world (smaller = further, the reverse of his
  extend); the forearm is a rubber band from the plate's bottom anchor to the glove. The special:
  both gloves converge on the chin, AMBER, with the starburst. The guard: both gloves at the guard
  slots, 18 % smaller, drawn brighter (α 1.1) — overlap reads as "closed".
- On impact: the glove → WHITE α 1.3 for 2 frames; eight WHITE-GOLD points scatter from the
  marker; an onomatopoeia word (`POW`, `BAM`, `WHAP`, `THUD` — generic comic words only) at 2.4×
  at the marker's plate position drifting up 20 px over 300 ms; the hit-stop kick (§2.5).
- The lean and the duck need no glove animation: the camera moves, so the world slides behind the
  fixed gloves — the cabinet's own dodge picture.

---

## 8. THE HUD (640 × 480 per eye, y down, every stroke through `hl()`)

The cabinet had two monitors: the fight screen carried NOTHING but the KO meter, and every number,
portrait and bar sat on the scoreboard above it; the crisp portrait "stays in the player's head" so
the rough zoomed sprite below is forgiven. The plate follows that: a **scoreboard band** across the
top (y 40–96), the **fight plane** in the middle with only the KO meter and the telegraph on it,
and **your gloves and hearts** along the bottom. Additive rules as x3discs (nothing occludes;
brighter is in front; black is nothing; the plate stays sparse because the world is the readout).

| Element | Position | Notes |
|---|---|---|
| Bezel | `rect(6,6,634,474)` @0.30, `rect(14,14,626,466)` @0.16 | the fight tint |
| LEFT RAIL = THE PULSE | x 28, 62 → 418, r7 caps, r15 cross at 240 [on disk] | the vertical fill = `timeScale`: cyan at the floor to white-hot at 1.0; a tick at the floor; the 2 px white tick at 0.5 (the latency marker) **and a second at `FREEZE_MARK` 0.15, which is now the crossing that matters**; **`FROZEN`** beside it after 1 s under 0.08 — an affirmative in ACID, not the old violet `STILL` scold, because at a 0.03 floor that is the intended state and not a warning. At rest the fill is a sliver, which reads correctly as "almost nothing". **The bar every standing test reads** |
| RIGHT RAIL = THE REFLEX | x 612, 62 → 418 | **REPURPOSED (LAW.md §8.6): TIME-TO-IMPACT on the committed glove, on the WORLD clock** — full (RED) when the strike launches, draining as the glove closes, so it moves only when the world does and it gives the frozen frame a number. It also DRAWS the two declared real-time timers: the 0.6 s counter window and the stall, so every real clock left in the fight is one the player can see. ~~drains through the hang and the fuse~~ — both deleted. Idle: dim BLUE outline |
| Opponent name | `(52, 46)` sc 2.2 MAGENTA | `THE ROOSTER` |
| His HP bar | x 52 → 232, y 60 → 66, outline @0.5, fill from the LEFT | MAGENTA; RED under 25 %; the fill eases over 0.3 s real |
| His knockdown pips | rings r 5 at `(58 / 74 / 90, 80)` | filled per knockdown this round; a fourth would be the KO |
| Your name | right-aligned `(588, 46)` sc 2.2 ACID | `YOU` (`CHAMPION` after a win, the cabinet's promotion) |
| Your HP bar | x 408 → 588, y 60 → 66, fill from the RIGHT (the mirror) | ACID; RED under 25 % |
| Your knockdown pips | `(550 / 566 / 582, 80)` | |
| ROUND CLOCK | `(320, 52)` sc 3.0 in the fight tint | `1:00` → `0:00`, WORLD time, counting DOWN — it visibly stops when you stop; the last 10 s pulse with the clapper |
| Round | `(320, 74)` sc 1.4 | `ROUND 1 OF 3` |
| Real clock | `(320, 90)` sc 1.2 dim WHITE | `REAL 0:41` — shown small and unscored, the time-bonus's input |
| THE KO METER | bar y 104 → 110, x 128 → 512 (30 points × 12.8 px), outline BLUE @0.5; fills from the RIGHT toward the box; MAGENTA, WHITE-GOLD from 26 | the `KO` box `rect(96, 98, 124, 116)` with `KO` sc 1.4 inside, blinking 4 Hz between 0.4 and 1.2 while lit; a tone (`Sfx.KO_LIT`) and `put_him_away` on the crossing |
| Multiplier | right-aligned `(588, 436)` sc 1.4 | `X3`; also the rope glow |
| Answer word | `(320, 330)` sc 3.0 | §7.4 beat 3; `CAPTIONS` row |
| Feedback word | `(320, 300)` sc 2.6, 0.6 s real, one at a time | `PERFECT +300` · `DODGE +50` · `COUNTER +300` · `GUARD +150` · `BLOCKED` · `GLANCE` · `WINDED` · `SPECIAL +1000` · `KNOCKDOWN +2000`; the `+` is overdrawn by hand (`StrokeFont` has no `+`, as `Hud.plusses` does on x3discs) |
| Panel brackets | four L-shapes at the corners, inset 20 px, 40 px legs | §7.5 |
| Onomatopoeia | at the hit marker's plate position, sc 2.4, drifting up 20 px over 0.3 s | WHITE-GOLD |
| The count | `(320, 240)` sc 5.0 WHITE | one numeral per real second, popping 0.5 → 5.0 over 80 ms |
| Damage frame | rects at 4 / 10 px inset in DAMAGE, 0.25 s decay [on disk idiom] | plus the 0.18 camera shake and the 4 px stroke jitter on a wing's stun |
| Your gloves | L `(170, 410)`, R `(470, 410)`, r 44; guard / aim-low slots `(225, 325)` / `(415, 325)` at 82 % | §7.6; the lit outline says which hand is armed with `LEFT PAD OFF` |
| Hearts | three stroke hearts r 9 at `(296 / 320 / 344, 455)` | filled = available, hollow = spent; all hollow → `WINDED` `(320, 436)` sc 1.3 and the gloves dim to α 0.5 |
| Score | right-aligned `(588, 455)` sc 1.6 | |
| High | `(30, 455)` sc 1.3 | `HIGH 18950` |
| Corner caption | tag `(52, 390)` sc 1.3 `CORNER`; line `(52, 410)` sc 1.75 (two lines 404 / 422 sc 1.55 split on `\|`) | raised on the clip's line-start only; hold = clip + 0.75 s |
| Round card | `ROUND 2` `(320, 300)` sc 2.9; `THE RUFFLE` `(320, 330)` sc 1.8 | 1.2 s, then the bell |
| Intro | `THE ROOSTER` `(320, 175)` sc 4.0; `ROY RUDD` `(320, 215)` sc 2.0; `120 LB - FAR CORNER` `(320, 245)` sc 1.6 | over his `taunt` strip while the announcer speaks |
| Knockout / tally | `KNOCKOUT` `(320, 175)` sc 4.0; rows y 215 / 245 sc 2.0 and 300 / 320 / 340 / 360 sc 1.7 | §5.4 |
| `TIME - NO DECISION` | `(320, 175)` sc 3.0 | then the game-over card |
| Game over | `GAME OVER` `(320, 180)` sc 4; `SCORE n` `(320, 215)` sc 2; `NEW HIGH SCORE` `(320, 245)`; `INSERT COIN TO CONTINUE` `(320, 300)` sc 2.2 blinking after 1.2 s; `CONTINUE 9` `(320, 330)` sc 2.0 | [on disk idiom + countdown] |
| Title | `X3 KNOCKOUT` traced `(320, 150)` sc 6.0 (11 glyphs, 330 px; the power-on beam and `TRACE_T` 2.0 s as the base); marquee `(320, 46)` sc 1.6 `READ THE ROOSTER. THEN MOVE.`; subtitle `(320, 195)` sc 1.8 `YOUR BODY IS THE CLOCK. YOUR HANDS ARE YOUR GUARD.`; `INSERT COIN TO PLAY` `(320, 300)` sc 2.4 blinking; warning `(320, 340)` sc 1.3 `STAND UP. STAY IN PLACE. STOP IF UNWELL.`; hints `(320, 362)` sc 1.3 `TAP LEFT TEMPLE - LEFT. TAP RIGHT TEMPLE - RIGHT.` and `(320, 380)` `TILT TO SLIP - NOD TO DUCK - DON'T SLIDE THE LEFT PAD`; records `HIGH n` `(30, 450)` sc 1.6, `BEST KO 1:23` right-aligned 610; `CREDIT 01` `(320, 464)` sc 1.4 | the boxer's `idle` strip in the ring behind it as the attract, the crowd at 0.25; `HEAD TRACKING REQUIRED` at `(320, 300)` on a device with no rotation vector |
| Settings | `rect(150, 86, 490, 404)` [on disk]; a 9-row window scrolling with the selection; `DOUBLE-TAP TO CLOSE` `(320, 392)`; hint `(320, 428)` | §11 |
| MOTION LAB plate | `(470, 96)`, pitch 14, sc 1.15 [on disk] | rows `MOTION` `HEAD` `BODY` `ACT` `RATE` `FLOOR` `BLANK` `KNEE` `LEAN X` `DUCK` `AIM` (HEAD / BODY) `PAD L` / `PAD R` (ms since the last lift) `PAIR` (the last inter-lift ms) `HANG` / `FUSE` (real s left) `STRIP` (name:frame) `FORCED` `HP` `METER` `HEARTS` |

Every caption string is scanned against the `StrokeFont` glyph set (`0-9 A-Z ! . - , : / ? ' ( )
> <`) by a build script; there is no `+` and no `·`, so awards overdraw the plus and separators are
dashes.

---

## 9. THE AUDIO

### 9.1 Architecture [on disk]
`Sfx` (synthesised 22.05 kHz WAVs generated into the cache at first launch, a SoundPool on its own
thread, duck × 0.45 under a voice), two `Voice` tracks on one `VoiceBus` (`voice/` m4a and
`voice_hero/` mp3), `Music` on its own thread (one track, duck × 0.62 under a voice). The mix:
`v = volume / 10`; music 0.55 v, sfx 0.9 v, voice 1.0 v, hero 0.92 v. Owner rulings kept: SFX sit
UNDER the music, the voices never talk over each other, the music never slows.

### 9.2 The SFX bank [changed: a new bank on the same synthesiser]
`FIRE`…`POOL_TAKE` are replaced by: `JAB_WHOOSH` (yours), `HIT_HEAD` (a snap with a ring),
`HIT_BODY` (a thud, 55 Hz), `GUARD_THUD` (his glove on your guard), `BLOCKED` (yours on his: a
cyan spark's tick), `WHIFF`, `CROWD_OH` (a six-voice detuned burst), `HANG` (the held breath: the
snap into the deep floor), `TELL_PECK_L` / `TELL_PECK_R` (a short cluck; the right a fourth
higher), `STAMP` (a low thud felt as much as heard: a hook is loading), `WHISTLE` (rising: the
body hook), `EXTEND` (his glove in flight: a doppler whoosh), `GLANCE`, `STUN_WARBLE` (looping
while he is staggered), `KO_LIT` (the meter's tone), `SPECIAL` (a rising fifth into the hit-stop),
`BELL` (a struck sine with an inharmonic partial, 1.2 s decay), `CLAPPER` (a wood block),
`COUNT_CLICK`, `FALL`, `ROPES`, `TICK`, `SELECT`, `START`, `GAMEOVER`, `HISCORE`. Every hostile
cue is quiet by design (≈ −9 dB under the music), rate-limited centrally, and none fires on a
continuous contact.

### 9.3 The crowd is the rate meter — in REAL time
The crowd bed is a looping noise layer whose volume and low-pass follow `timeScale` with a 200 ms
lag: at the floor the arena is a held breath (a hush), at rate 1 it is a roar. The owner will hear
the world freeze without looking at a rail (TEST.md T5).

**The range was squeezed by the old floor and must be widened (LAW.md §9).** With the clock living
between 0.35 and 1.0, `level = 0.25 + 0.75 × crowdLevel` was a 6 dB swing — audible, not a hush.
Send **`level = 0.06 + 0.94 × crowdLevel`** (≈ 24 dB) and **`rate = 0.55 + 0.65 × crowdLevel`**.
SoundPool has no filter, so bake the low-pass: generate a **second, darker crowd bed** in the
existing synthesiser (the same `crowdbed` recipe with the saw partials removed and the noise shaped
at 400 Hz) and crossfade the two on `crowdLevel`.

**And the crowd was the only thing in the mix that moved with the clock, which is why the frozen
world never SOUNDED frozen.** Every cue that belongs to the WORLD — `EXTEND`, `STAMP`, `WHISTLE`,
`TELL_PECK_L` / `_R`, `GUARD_THUD`, `WHIFF`, `STUN_WARBLE` — is now played at
`pitch = clamp(0.5 + 0.5 × timeScale, 0.5, 1.0)`, which `Sfx.play(id, pitch, vol)` already forwards
to `SoundPool` and clamps to 0.5–2.0. At the floor they are half-speed groans. The player's own
sounds, the bell, the announcer, the referee, the crowd's LOUDNESS and the MUSIC all stay at 1.0
(§9.5's ruling holds), and the contrast between a brass chart at tempo and a world groaning
underneath it is the two-clock promise made audible.

`Sfx.HANG` fired on entering the tell's hang, a state that no longer exists. It now fires on the
rate **crossing DOWN through `FREEZE_MARK` 0.15 having been above 0.5**, rate-limited to one per
real second: the sound of the world freezing, which under the corrected law happens dozens of times
a round — exactly as often as the player should be reminded the mechanic is theirs.

On top: the hit sting, a boo after 3 real seconds of stillness **while he is IDLE**, the two-note
chant when you have been hit twice without answering, and the KO roar. ~~The count spoken along
from 5~~ is impossible at runtime — `VoiceBus` allows one speaker at a time — and is **baked into
`ref_5`…`ref_10` at render time** instead (VOICE.md §3). The visual crowd's bob TEMPO is world time;
its LOUDNESS is real.

### 9.4 The voices — MOVED to `docs/VOICE.md`
The roster, the five verified ffmpeg chains, the separation law, the per-fighter keying, the
referee's introductions and all **98 clips** are in `docs/VOICE.md`, which is the file
`tools/extract_lines.py` parses. What was here was a five-speaker table for a one-boxer prototype;
the card has five men now, so the fifth speaker is not a character but a **role — THE MAN IN THE
RING** — and every line that depends on which of them is up is keyed `<stem>_<fighter.id>`.

Four things from this section that survived and are still binding:

- **Priority on the bus.** The man's crow is a TELEGRAPH and is `urgent`; the referee's count is
  urgent; the announcer's punch calls are throwaway (patience 300 ms — a call that arrives late is
  worse than none); the corner's tip waits and only speaks in the corner. Every line is logged
  `say[dir] id (ms)`.
- **`Voice.kt` is complete and correct as inherited** and needs exactly one addition,
  `fun has(id) = durations.containsKey(id)`, surfaced on `GameHost`.
- **`Lines.OH` is dead** — `Fight.onStrike` and `updateCount` already fire the synthesised
  `Sfx.CROWD_OH`. Deleting it also stops the crowd competing with the announcer for the one bus.
- **`extract_lines.py`'s `SPEAKERS` set must gain `REFEREE`, `CORNER` and `BOXER` in the same
  commit as VOICE.md.** A row whose speaker is not in the set is skipped *silently*, so a `--check`
  run against today's set reports 25 lines and passes, having thrown away three quarters of the
  script.

### 9.5 Music
**[changed — the owner's ruling: "change the music for something appropriate for the game"]** The
disc game's IO Tower cover came across with the scaffold and was simply wrong here: a synthwave
Tron theme under a boxing cartoon. Five tracks now, all Kevin MacLeod / CC BY 4.0, brass and drums
because that is what an arcade prizefight sounds like — see `docs/MUSIC.md` for the verbatim licence
strings, which also go on the CREDITS page.

| State | Track | Why |
|---|---|---|
| title / attract | *Blue Ska* (110 BPM, horn section) | the gym, the swagger, the joke |
| rounds 1–2 | *Rollin at 5* (210, trumpet + trombone over a driving kit) | a cartoon prizefight |
| round 3 | *The Cannery* (178, aggressive, relentless) | the same brass with the comedy taken out |
| a knockdown count | *Past the Edge* (44, dark and sparse) | everything drops away but the referee |
| the win | *Mighty and Meek* (88, trumpets, trombones, horns, tuba) | a fanfare slightly too pleased with itself |

Cuts, never crossfades: a boxing cabinet changes music at the bell, the knockdown and the win, and
a fade between two brass charts is mud — the cut IS the punctuation. Opus in Ogg so the loops are
gapless (an MP3's encoder padding clicks at every loop point); verified decoding on these glasses.
The music never slows — it is not from inside the ring.

---

## 10. TEACHING — THE CORNER SAYS ONE SENTENCE

The cabinet teaches by pattern, not by caption. Three teaching devices, in order of quietness:
1. **The answer word** (§7.4) — the first two appearances of each attack, then never, unless
   `CAPTIONS` is turned on permanently.
2. **The feedback word** — every answer is named (`DODGE`, `PERFECT`, `GUARD`, `GLANCE`,
   `BLOCKED`, `WINDED`), so the player always knows what the game thinks they did.
3. **The corner** — between rounds the corner man says ONE sentence chosen from what the player was
   hit by most that round. **The texts are now `docs/VOICE.md` §6.3 and they are shorter**, because
   `Clock.CORNER_T` is 2.2 s and the first draft's tips measured up to 4.3 s, which stretched the
   rest to 5.35 s and made it a different length every round (§5.3). **And four of them name a
   colour channel, which is exactly what the card varies**, so the corner speaks one of three
   dialects — CREST, LIT, QUIET — chosen by `Fighter.tellDialect`: `tip_wing_r` says "Gold crest"
   and only the Rooster has a crest, while Silk has no colour at all, so today's tips would
   actively mislead on four of the five fights. `Lines.tip(stem, dialect, has)` tries
   `stem + suffix` and falls back to the bare stem, so only three tips need variants and a sixth
   fighter needs none.

The intro (`INSERT COIN` → the announcer) is the only scripted speech in the prototype; the attract
loop, the champion's belt and a second boxer are Phase 2.

---

## 11. SETTINGS ROWS (double-tap; name-keyed like the base; nine at a time)

| Row | Values | Notes |
|---|---|---|
| MUSIC | ON / OFF | |
| VOLUME | 0–10, default 7 | the §9.1 mix |
| VOICE | ON / OFF | |
| DIFFICULTY | EASY / NORMAL / HARD | §5.6 |
| DODGE SENSE | LOW / MEDIUM / HIGH | full duck 29 / 22 / 16°, full lean 22 / 16 / 12° (§3.2); MEDIUM ships |
| STEP SENSE | LOW / MEDIUM / HIGH | `V_STEP` 0.45 / 0.32 / 0.22 [on disk] |
| LEFT PAD | ON / OFF | OFF = right taps alternate hands, swipe UP is the special (§1.5) — the fallback ships in the same build |
| CAPTIONS | AUTO / ON / OFF | the answer word: first two of each attack / always / never |
| MOTION LAB | ON / OFF | the plate, the guided calibration and the rows below; ON in prototype builds, OFF at ship |
| TIME FLOOR | 3 / 5 / 8 / 12 / 20 % | lab only; overrides the IDLE floor's base and the difficulty floor |
| HANG | 0.5 / 0.8 / 1.2 s | lab only: `HANG_T` |
| PITCH COMP | 0 / 0.5 / 0.7 | lab only: how much of a nod the view un-pitches during a duck (§14.3); 0 ships until the owner rules |
| DRILL | OFF / PECK L / PECK R / WING R / WING L / SUNRISE / ALL | lab only: he throws only that attack every 2.5 world s, no damage to either side — the standing test's instrument |
| KNEE | A / B | lab only, debug builds only |
| CREDITS | > | |
| RESET SETTINGS | (blank) → `TAP AGAIN TO CONFIRM` | records kept |
| QUIT | `END OF LINE` → `TAP AGAIN TO CONFIRM` | title and game over only; always the last row |

Removed from the inherited list: `HOP` (the step is the swipe and the body; it is not required for
survival before round 3 and there is no platform to fix) — if the step's 0.55 m slide reads as
vection on-head (TEST.md V6) a `STEP OFF` row returns and the tracking uppercut falls back to a
late slip. Records (`HIGH`, `BEST KO`, `FIGHTS`) survive `RESET SETTINGS`; the story prefs file is
kept for the champion flag.

---

## 12. 60 Hz, THE RENDERER, AND THE WORKAROUNDS

### 12.1 The contract [on disk shape, from x3discs' `GLRenderer.kt`]
`GL_LINES` from a stream `Batch` of 7-float vertices (`x y z r g b a`), one `glBufferData(STREAM)`
per batch per frame; two passes per batch per eye — wide `glLineWidth(min(4, maxLine))` at
`uAlpha 0.30`, then core 1.5 px at 1.0 (HUD: 3 px @ 0.28, 1.2 px @ 1.0); blend `SRC_ALPHA, ONE`, no
depth test or mask, the fragment writes premultiplied `(rgb·a, a)`; EGL 8/8/8/0/16/0;
`perspectiveM(62°, 4:3, 0.15, 120)` drawn twice with the viewport shifted (`for e in eyes:
glViewport(e·vw, 0, vw, h)`) — **never a blit**: this Adreno driver rejects a default-framebuffer
self-blit with `GL_INVALID_OPERATION` (measured in TapMame), and an FBO round-trip costs more than
a second draw of 3 k vertices. HUD `orthoM(0, 640, 480, 0)`, `project()` = `320 + ndc·320, 240 −
ndc·240`. `maxLine` from `GL_ALIASED_LINE_WIDTH_RANGE`, logged as `surface: maxLine=`. Measured on
x3discs: 59.8–60.0 fps at 8–10 k line vertices through the CPU `walk` path.

### 12.2 The workarounds, in the cabinet's own spirit
The 1984 board had a sprite-and-tile chip and no 3D; its answers were: draw every frame by hand,
scale ONE big sprite instead of moving a camera, flash the palette, keep the opponent a single
object and the player the cheapest thing on the board. Every workaround here is one of those in
GL clothing.
1. **One big sprite** — the boxer is a flat billboard of held 12-fps frames (§7.3); his zoom is a
   scale on the model matrix and his "closing distance" is the glove growing 1.8× — the board's
   zoom register, without a camera move.
2. **The palette flash** — colour is per PART and set per frame from a small table (§12.4): the
   telegraph, the hit flash, the crest's hue and the LOD dimming are colour-table writes; the
   drawing never changes.
3. **The transparent player** — the cabinet's wireframe was its 2bpp sprite drawn last; ours is
   40 strokes a hand in plate space and the boxer sums through it for free.
4. **Per-row scroll → a vertex-shader sway** — the crowd wave and the rope shake are one uniform
   (§12.5), no CPU.
5. **The scoreboard off the fight plane** (§8).
6. **Deterministic clock-keyed scripting** — the pattern table (BOXER.md §7) is phrases and
   branches on visible player state, a 4-slot rotation seeded per fight; nothing rolls a die.
7. **Frames held, camera live** — 12 fps inside the sprite, 60 Hz head-tracked presentation
   outside it, with the secondary motion in the matrix.

### 12.3 The vertex budget, computed
| Group | segments / frame | line vertices | path |
|---|---|---|---|
| the boxer (one held frame) | ≈ 620 (outline ≈ 260, hatch ≤ 320, detail ≈ 40) | 1 240 | CPU emission into the stream batch: a copy with a per-part colour lookup and the billboard transform (12 madds a vertex) |
| ring 180 + crowd 640 + referee 25 | 845 | 1 690 | static VBOs uploaded once, drawn with the sway uniform |
| your gloves 100 + plate ≈ 350 + words / FX ≤ 150 + the arc 12 + trails 12 | ≈ 620 | 1 240 | the stream batch |
| points (stars, the bead) | ≤ 64 | | |
| **total** | **≈ 2 100** | **≈ 4 200**, one ≈ 35 KB upload a frame | a third of the load x3discs held at 60.0 fps |

Fill: a 4-px line of mean length 25 px is 100 px; ≈ 2 100 segments × 2 passes × 2 eyes ≈ 0.6 Mpx a
frame ≈ 36 Mpx/s — nothing on an Adreno. **60 Hz is not in question; headroom is ≈ 4× on
strokes, so the caricature can be lavish.** The cap to respect is the whiteish-pixel fraction
(§7.2's one-white rule), not vertices. The static-VBO sprite path (all strips in one `STATIC_DRAW`
buffer, a 4-float `x y z part` vertex, `uTint[32]` indexed in the vertex shader) is the
optimisation held in reserve — it moves the boxer's 1 240 vertices off the CPU entirely — and is
adopted only if `fps=` shows the stream path short of 59; the prototype ships the simpler path
because it is the one already measured.

### 12.4 Per-part colour: the tint table
`SpriteMaterial.tint[part] = (r, g, b, gain)`, 32 parts, written by the fight each frame and read
by the emitter: the flash is `tint[glove_L] = (1, 1, 1, 1.5)`, the hatch dimming is
`tint[hatch_head].a = 0.35 × √(2.6 / d)`, hiding a variant (a mouth, sweat, the spirals) is gain 0,
the crest's hue is `tint[crest]`. The strip's frames carry a part index per segment, so this is a
lookup per segment on the CPU path and a uniform array on the reserve path — the same table.

### 12.5 One sway uniform animates the crowd and the ropes
`uSway = (amp, freq, phaseScale, floorY)`; the vertex shader adds `amp · sin(freq · uT + x ·
phaseScale) · max(0, y − floorY)` to x for the static groups and `(0, 0, 0, 0)` for everything
else. The crowd wave and the rope shake are the same four floats.

### 12.6 The assets and their pipeline
- `blender/assets/boxer.py` (procedural bpy, no `.blend`, `program.py`'s idiom): object origin =
  pivot so keyframed object rotations bake about the joint; doubled contours; the `hatch()` helper
  (parallel lines clipped to a polygon, pitch 2.5 cm, a 2.0 cm variant one line away); mouths /
  sweat / spirals as variants keyframed on `hide_render`; markers as `m_*` empties with
  `ob["marker"] = True`; `ob["cls"] = outline | detail | hatch`; a `STRIPS` dict of frame ranges and
  events (BOXER.md §8). `scene.render.fps = 12`, CONSTANT interpolation.
- `blender/export_strokes.py` gains `export_strips()` — `export()` is untouched for the ring, the
  crowd and the referee — sampling `scene.frame_set(f)` per frame through the evaluated depsgraph
  (`Action.fcurves` is gone in Blender 5.x), writing `models/boxer.json` (the manifest: parts with
  `cls` / `flash` / pivot / colour, markers, strips with events, `"fps": 12`, `"fwd": "-z"`) and
  `models/boxer.x3s` (little-endian: `"X3S1"`, `u32 nParts nMarkers nFrames`, then per frame `u32
  segCount[part]`, `f32 × 6 × Σseg` in part order, `f32 × 3 × nMarkers`). ≈ 122 frames × ≈ 620
  segments ≈ 1.8 MB, ≈ 0.7 MB deflated in the APK; as JSON it would be ≈ 6 MB and a 1–2 s parse.
  `sys.exit(3)` on any frame over `MAX_SEGS` 700.
- `engine/StripSet.kt` (`parse(manifest, x3s)` JVM-testable like `StrokeModel.parse`),
  `engine/StripPlayer.kt` (`play(name)`, `update(wdt)`, events fired once, `marker(name)`; switching
  mid-strip is legal — sprites cut, they do not blend), `engine/SpriteMaterial.kt`.
- Previews before the glass: `blender/render_strips.py` (a contact sheet per strip from the fight
  camera through `strokes_render.py` + `glow.py`) and `tools/strip_sheet.py` (a pure-PIL render of
  the `.x3s` through the engine's own projection — 62°, 640 × 480, the doubled draw with a 4-px
  α 0.30 halo and a 1.5-px core, additive — with a whiteish-pixel count per frame).
- Unit tests: `StripSetTest` parses the shipping `boxer.json` + `boxer.x3s` and asserts every
  strip's `strike` event exists and that `glove_R` on `wing_r`'s strike frame is nearer the camera
  (+Z) than on its first frame — the forward-axis trap `StrokeModel.kt`'s KDoc describes, pinned
  for the sprite too; `StripPlayerTest` proves a frozen `wdt` holds the frame and an event fires
  exactly once; `ClockTest` gains the floor override and the five new forced states;
  `TwoClocksTest` gains §2.9's list.

### 12.7 Sub-pixel creep, near plane, LOD
At the deep floor his frames HOLD (nothing creeps inside the sprite) and only the secondary motion
moves — so the judder question x3discs faced at the floor does not arise for him; it arises for the
arc's bead and the trails, which are floats end to end. Nothing is drawn nearer than 0.30 m. Parts
flagged `detail` (laces, ear inners, sweat, teeth) get gain 0 beyond 3.4 m and 1 inside 3.0 m — a
hysteresis band so nothing flickers on a lean; the outline never LODs, the silhouette is the read.

---

## 13. TELEMETRY (tag `X3Knockout`)

The review process is logcat-driven; these lines are part of the first build, not a later polish.

| Line | Rate | Content |
|---|---|---|
| `CLK \|w\|= m= act= rate= floor= step= blank= knee= [forced=]` | 10 Hz [on disk] | `forced=` now also `PUNCH` / `STRIKE` / `HITSTOP` / `SLOW` / `COUNT` / `STEP` / `CORNER` / `MENU`; `floor=` shows the override in force |
| `MOTION [tag] m= w= a= lat= fore= vert= vlat= vfore= roll= pitchG= rr= steps L R F B peakW= peakA=` | 5 Hz [on disk] | the body |
| `PAD dev=<name> id= src= act=DOWN\|MOVE\|UP x= y= dt=` | per touch event, both pads [new] | the left-pad verification (TEST.md L1): the first build logs every `cyttsp6` event |
| `KEY code= act= devId= dev=<name> echo=<true\|false> hand=` | per key [new] | nobody has ever logged the key's device |
| `PUNCH hand=L\|R level=HEAD\|BODY result=LAND\|GUARD\|AIR\|COUNTER\|STAGGER dmg= hp= meter= hearts= forced=` | on event | |
| `PAIR ms=` | on every two-handed pair | the inter-lift interval; the p90 sets `SPECIAL_MS` |
| `SPECIAL lit=<true\|false> result=` | on event | |
| `TELL attack= round= floorAtStart= hangLeft=` / `FUSE burned` | on event | |
| `STRIKE attack= answer=SLIP_L\|SLIP_R\|DUCK\|GUARD\|STEP\|NONE result=HIT\|GLANCE\|CLEAN\|PERFECT\|BLOCK\|CRUSH dmg= \| head=(x,y) lean= duck= step= ts=` | on event | the collider test, not a threshold |
| `STAGGER open= extended=` / `GUARD open= by=` | on event | |
| `KNOCKDOWN who=HIM\|YOU n= count=` / `RISE at=` / `KO` / `TKO` | on event | |
| `ROUND n world= real=` / `BELL` / `CORNER tip=` / `NO DECISION` | on event | |
| `SCORE +n reason=` / `GAME OVER score=` / `CONTINUE` | on event | |
| `VERIFY` | 5 Hz | his state and strip:frame, HP, guard, the floor in force, your HP / hearts / meter, `leanX` / `duckAmt` / `bodyX`, and the current line's aim point vs the capsule |
| `say[voice\|hero] id (ms)` | per line [on disk `Voice.kt`] | the mix audit |
| `fps= boxer= static= stream= hud= ts=` | 1 Hz | with each group's vertex count |
| `surface: maxLine=` | once | §15.13 |
| raw recorder | 20 Hz to `cache/motion.log` [on disk] | pulled with `run-as`; the guide phases now include the fight's drills |

---

## 14. COMFORT — THE NECK, THE ARMS, THE FRAME ON THE NOSE

1. **The neck.** The on-disk full duck is 29° and the full lean 22°, tuned for occasional dodges;
   a round asks for one every few seconds. So: the body-blow aim needs only 10°, the default head
   shot needs no motion at all, DODGE SENSE ships MEDIUM (22° / 16°), the boxer is authored so a
   full duck is needed at most once per ≈ 6 world seconds and never twice in a row (high and low
   threats alternate; the guard answers one of every three), rounds are 60 world seconds with a
   real-time corner, and the count and the corner suspend the body's clock — a rest that pays.
2. **The arms.** Both hands at the temples for a round is a plank for the shoulders. Nothing needs a
   hand to hover: a tap is a reach; the corner exists for the rest.
3. **The duck takes him out of frame.** A 22° nod at 2.6 m puts his head 30°+ above the look axis
   — outside the 24° half-field — so at the bottom of a full duck you see his gloves, his trunks and
   the canvas, which is the cabinet's own duck picture (the fist goes over) and lasts a third of a
   second. `PITCH COMP` (lab: 0 / 0.5 / 0.7) un-pitches the view by that fraction of the nod so a
   ducked view still shows his gloves; it ships at 0 because a view that is partly head-locked in
   pitch is a vestibular mismatch nobody has tested here, and the owner rules on it (TEST.md D3).
   `ROLL_COMP` stays 1.
4. **Tapping shifts the frame on the nose.** Twenty taps on one temple push the glasses; yaw drift
   is already hidden at the round card, and now the gravity references (`restRoll`, `restPitch`)
   are re-declared there too, while the head is still. The symptom to watch in the log: a creeping
   `roll=` / `pitchG=` baseline at successive round cards.
5. **Standing safety.** The room stays visible, but the player slips, ducks and steps with a real
   room that stays put. Play in place; the step is 0.55 m of camera slide, not a cue to walk; the
   standing warning on the title; stop if unwell.
6. **Photosensitivity.** No full-depth strobe anywhere; every flash is a single ramp; the `KO`
   box's 4 Hz blink floors at 0.4.

---

## 15. WHAT COULD GO WRONG ON-HEAD — and the test for each

1. **The left pad injects a key.** If the RayNeo service classifies `cyttsp6` taps as
   `BUTTON_A`, every left punch is trailed by a key that only the `lastTouchTapMs` stamp keeps
   from becoming a phantom RIGHT punch; a left double-tap as `BACK` could send the launcher's back.
   TEST.md L1 (the `KEY` line's device after 10 left taps and 3 left double-taps).
2. **A left tap does something system-side** — a mute, a volume HUD that steals focus. L1's
   `dumpsys window` check after a tap.
3. **A slide on the left pad changes the volume mid-fight**, and consuming the event may not stop
   it. L2 (`dumpsys audio` before and after 3 deliberate left slides). Until settled, the title
   says `DON'T SLIDE THE LEFT PAD` and `LEFT PAD OFF` is one row away.
4. **The left pad's coordinates or report rate differ** (a system "volume mode"). L1's `PAD`
   lines: the extent and the inter-event timing; the 115 px travel threshold is normalised to it.
5. **The special's 120 ms is wrong.** A two-handed slap lifts its two fingers 30–80 ms apart on a
   guess. V4 logs `PAIR ms=` for twenty slaps; the p90 ships.
6. **The hang feels like a pause, or the fuse feels like a cheat.** The whole "exciting" claim
   rests on 0.8 + 1.2 s. T2 / T3 with `HANG` 0.5 / 0.8 / 1.2 and the deep floor 0.04 / 0.06 / 0.10.
7. **The strike is too fast to answer** or too slow to feel like a punch. T4: PERFECTs per ten
   drilled pecks at 0.25 s; the owner's word for it ("reflex" or "unfair").
8. **A punch tap runs the clock unevenly** — the slap's 0.96 vs the cut-tap's 0.29 (measured).
   `Forced.PUNCH` makes the cost uniform; V1 checks `forced=PUNCH` appears on every tap and the
   `CLK` rate during a flurry is flat.
9. **A slap fakes a step.** |a| 3.25 > `STEP_PUSH` 1.2; the 150 ms blank withholds the verdict.
   A0 (ten slaps, `steps L R` unchanged) — and a flurry blinds body steps for its duration, by
   design.
10. **Body blows fire by accident.** A fighting head wanders +8…+15° of pitch (measured); the
    10° / hysteresis line must sit above that noise on-head. V2: twenty head jabs at rest with
    `AIM` on the lab plate; zero unintended `level=BODY`.
11. **The duck takes him out of frame** (§14.3). D3 rules on `PITCH COMP`.
12. **Neck fatigue.** D3's ten minutes; DODGE SENSE HIGH is the fallback; the authoring rule (one
    full duck per 6 world s) is checked in the pattern table.
13. **`GL_ALIASED_LINE_WIDTH_RANGE` is narrow.** Only the two-frame extend halo (`glLineWidth 6`)
    depends on it; the doubled contours do not. `surface: maxLine=` on the first run.
14. **Hatch reads as stripes, not a block**, at 2.6 m on this halo. The 2.0 cm variant is one line
    away in `boxer.py`; the `eye.py` crop decides (D1).
15. **The frame washes toward grey.** Everything sums; the tell only works if the incoming glove is
    the brightest stroke on the glass. The boxer's body is budgeted at hatch 0.35 and only the tell
    glove reaches white; D1 counts whiteish pixels on the tell frame vs the idle frame — the tell
    must be higher only by the flashed parts.
16. **12-fps frames read as judder.** They must not, because the camera and the secondary motion
    are 60 Hz; D1 watches a held frame during a hang while turning the head.
17. **The crowd's hush/roar lags the rate.** 200 ms is a guess; T5 (eyes closed, say when the
    world stopped).
18. **The announcer talks over the crow.** The crow is a telegraph and is `urgent`; D4 listens for
    a crow cut short by a punch call (every line is logged).
19. **Drift and the frame on the nose.** C3-style: five minutes without a recentre; `roll=` /
    `pitchG=` at each round card.
20. **The step is vection.** 0.55 m in 0.35 s; V6 rules, and a `STEP OFF` row returns if it fails.
21. **The pad's latency chain** (pad firmware → dispatch → `queueEvent` → the next GL frame) is
    unknown; hit-stop (70 ms) sits at its scale. V1 measures the slap's accelerometer jolt against
    the `ACTION_UP` timestamp at 200 Hz.
22. **A bilateral slap is a vertical impulse** (`ay`), and `vertical` feeds nothing today. Noted in
    the KDoc now so no future "bob" verb is read from it.
23. **The two-clock split leaks.** One hostile timer on real time breaks the promise (the tell
    completing while the player stands still); one caption on world time freezes the corner.
    §2.9 is the audit; the announcer speaking at the floor is the check (adb, floor 0).
24. **Review evidence disappears.** If a refactor drops a §13 line or the `--es drill` launch, the
    standing tests regress to guesswork. §13 is a build requirement.
