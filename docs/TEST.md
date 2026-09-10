# X3 KNOCKOUT — THE TEST

Two kinds of test: what **adb can verify alone** at the desk, and what only the **owner standing
up in the glasses** can verify. The standing tests run in the order below; a later test is
meaningless if an earlier one failed, and **nothing downstream of a failed test is built**. Every
test is one standing session, one question, one number: the number is read on the MOTION LAB plate
(`(470, 96)`) and in logcat (`adb logcat -s X3Knockout`); the raw 20 Hz recorder (`adb shell run-as
com.x3knockout cat cache/motion.log`, cut up by `tools/analyze_motion.py`) is the evidence when a
feeling and a number disagree. The owner decides by feel; the log decides by number.

**The glasses were absent while the design was written** (`adb devices` listed nothing for
`A06B4A96A733283`). Every on-head number in DESIGN.md and BOXER.md is a proposal; the tests below
are where each one becomes a measurement. When the serial is absent, do §1 as far as it goes
(build, unit tests, exporter, font scan, manifests) and say so.

Device: `export ANDROID_SERIAL=A06B4A96A733283` (the owner's phone is often attached too). Build with
`tools/gbuild.sh` only — never `./gradlew` directly; agents share this checkout. Screenshots are
SBS: `adb exec-out screencap -p > shot.png && tools/.venv/bin/python tools/eye.py shot.png eye.png`
and READ the PNG. Drive the glasses off-head only with deep suspend disabled (`settings put global
deep_suspend_disabled_persist 1` and `…_tmporary 1`, wake first with `input keyevent 224`, restore
after) and STOP if a capture shows system UI — a blind tap lands in the system shade. Capture demos
with `scrcpy --record` (`adb screenrecord` has no audio on this hardware) — and never while a voice
test needs the microphone. Voice features are tested by speech on the glasses, never by injected
text, and the owner is asked before each voice test.

---

## 1. What adb can verify alone (before any standing test, and after every change)

| Check | How | Pass |
|---|---|---|
| Build, unit tests | `tools/gbuild.sh assembleDebug` · `tools/gbuild.sh testDebugUnitTest` | green: `ClockTest` (the floor override; `PUNCH` / `STRIKE` / `HITSTOP` / `SLOW` / `COUNT` timers run on real time and hold under `MENU`; `clearForced` cannot drop `MENU`), `TwoClocksTest` (DESIGN.md §2.9's list on the real engine), `StripSetTest`, `StripPlayerTest`, `StrokeModelTest` |
| Install, launch | `adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.x3knockout/.MainActivity` | the title traces; `surface: maxLine=` logged (record the number — DESIGN.md §15.13); `fps=` at 60 |
| Frame rate on the busiest state | `am start … --es drill sunrise --ef floor 1.0` (he throws uppercuts at full rate: the extend, the crowd at full sway, the arc, the word, a trail, the starburst) | `fps=` ≥ 59 sustained; `boxer=` ≤ 1 400 and `stream=` ≤ 3 000 vertices |
| The two clocks | `am start … --ef floor 0.0 --es drill wing_r`, glasses still on the desk | his strip:frame in `VERIFY` does not advance during the TELL; the hang and the fuse still elapse (`hangLeft` counts down on real time); the announcer's `say[…]` lines, the crowd bed and the round card still run; the round clock does NOT |
| The floor override | the same launch, read `CLK floor=` | 0.35 while he is idle, 0.06 the frame the tell starts (with `HANG` at 0.8), ramping to 0.60 over the next 1.2 s, 0.12 in his recover |
| Pad arbitration (right) | `input keyevent 96` (a key tap) · `input keyevent 4` (double) · `input swipe 900 240 600 240 150` (step left) · `input swipe 800 150 800 380 1500` (guard held) · `input swipe 800 380 800 150 120` (swipe up) | one `PUNCH hand=R` per key tap on the arena, never two; `KEY … echo=false hand=R` for a key with no touch; no settings menu from a single tap; `STEP L` / `GUARD open` + `GUARD close` / a click, one per gesture; off the arena the burst resolves 1 / 2 / 3 as before |
| Pad arbitration (left) — desk part | `adb shell getevent -lt /dev/input/event4` while a finger taps the LEFT temple 10× and double-taps 3× (glasses on a head, not a desk: the pad is capacitive) | the node reports a touch protocol; note any `EV_KEY` (`KEY_F*`, `BTN_*`, `KEY_VOLUME*`) mixed in; the app logs one `PAD dev=cyttsp6_mt` DOWN/UP pair per tap |
| Blanking | ten `input keyevent 96` with the glasses still | `CLK … blank=` shows the window; `MOTION … steps L R F B` unchanged |
| A punch is a forced state | `input keyevent 96` × 10 at 200 ms spacing during a fight | every tap is followed by `CLK … forced=PUNCH` for ≈ 0.28 s; the `rate=` during the flurry is flat at 1.00 (not the accelerometer's uneven 0.3–1.0) |
| The special's upgrade | with `LEFT PAD OFF`: `input keyevent 96` then `input swipe 800 380 800 150 120` inside 120 ms (a script) while `meter=` ≥ 26 | `SPECIAL lit=true` and ONE `PUNCH` line, upgraded in place — never a jab and a special |
| Hit-stop never stops the render loop | `am start … --es drill peck_l`, let him be hit (a `--ei hp` launch with a scripted landed punch: `--es script counter`) | `fps=` stays at 60 across `forced=HITSTOP`; the `CLK` line shows `rate=0.00 forced=HITSTOP` for 70 ms |
| Colour on-glass | `screencap` → `eye.py` on: the idle frame, a tell frame at the floor, a hit frame, the count | the whiteish-pixel fraction of the tell frame exceeds the idle frame's only by the flashed parts; no channel-balanced greys in the seams; exactly one white thing per frame |
| Hatch reads as a block | the same crops at 2× (`eye.py in out 2`) | stripes are not resolvable at 2.6 m; if they are, switch `boxer.py` to the 2.0 cm pitch and re-export |
| Exporter round trip | `blender -b -noaudio --factory-startup --python-exit-code 3 --python blender/export_strokes.py -- blender/assets/boxer.py app/src/main/assets/models/boxer.json` (writes `boxer.x3s` beside it); the same for `ring`, `crowd`, `referee` | the printed per-strip segment counts; every frame ≤ 700 segments; a broken asset exits 3 |
| Strip previews | `tools/.venv/bin/python tools/strip_sheet.py app/src/main/assets/models/boxer.x3s sheet_<strip>.png` per strip; read the PNGs | the outline heavier than the detail; the tell frame's white count higher only by the glove and pupils; the crown inside the top bezel on `idle`; `wing_r`'s strike glove nearer the camera than its tell glove |
| Voice manifests | `tools/extract_lines.py --check` against BOXER.md §9's ids; `python3 -c 'import json;print(len(json.load(open("app/src/main/assets/voice/manifest.json"))))'` per directory | every id present with a duration; no `{}` |
| Font safety | the build script that scans every plate string against the `StrokeFont` glyph set | zero dropped characters (no `+`, no `·`, no lowercase) |
| Records discipline | `adb shell run-as com.x3knockout cat shared_prefs/x3knockout.xml` before and after a `--es drill` launch | `hi`, `bestKo`, `fights` unchanged by debug launches |
| Settings survive | reset in the menu, then read the prefs | records kept; `LEFT PAD`, `DODGE SENSE`, `CAPTIONS` back to defaults |
| Forward axis | `tools/gbuild.sh testDebugUnitTest` (`StripSetTest`) | `glove_R` on `wing_r`'s strike frame has a larger +Z than on its first frame |

Desk harness (debuggable builds; never writes records): `am start -n com.x3knockout/.MainActivity
--ei round N --ef floor F --ei hp H --es drill peck_l|peck_r|wing_r|wing_l|sunrise|all --es script
<name>`. `input keyevent 96` = a right tap, `4` = double-tap, `input swipe` for the right pad's
gestures. There is deliberately no key for the LEFT pad: a key can never name a pad, so the left
pad is only ever tested with a finger.

---

## 2. The owner's standing tests, in order

Format: **DO** — **SHOULD** (the number) — **REPORT** (the question). Stop and ask before each
block. The MOTION LAB is on; `DRILL` is the instrument for Blocks T, V and F (the boxer throws one
attack every 2.5 world seconds, no damage either way, until it is set back to OFF).

### BLOCK L — THE LEFT PAD (first; nothing about the left hand is built on until this passes)

**L1 · Does a left tap reach the app, and does anything follow it?** DO: glasses on, in the fight
(`--es drill peck_l`, DRILL OFF is fine too); tap the LEFT temple ten times slowly, then double-tap
it three times, then tap the RIGHT temple ten times. SHOULD: ten `PAD dev=cyttsp6_mt … UP` lines and
ten `PUNCH hand=L`; the `KEY` lines that follow each left tap (if any) say `echo=true` and produce
NO extra `PUNCH`; the three left double-taps produce six left punches and NO `MENU open`, no
launcher back, no `BACK` that escaped; `adb shell dumpsys window windows | grep -iE
"volume|toast|overlay|dialog"` and `dumpsys activity activities | grep topResumed` immediately
after a left tap show no volume panel and the app still focused. REPORT: did every left tap throw a
left punch, and did anything else on the glasses react (a volume popup, a mute, a flash of system
UI)?

**L2 · The slide.** DO: `adb shell dumpsys audio | grep -A4 STREAM_MUSIC` before; twenty left taps;
three deliberate left SLIDES (a finger drawn along the left temple); the same dumpsys after.
SHOULD: the stream volume unchanged by the taps; whether the slides changed it is the answer —
either way the app logged `PAD dev=cyttsp6_mt … MOVE` and threw NO punch for a slide. REPORT: did a
slide change the volume with the app consuming the events? (Decides the title's warning and
whether `LEFT PAD OFF` is the shipping default.)

**L3 · A sloppy punch.** DO: twenty fast left taps the way you actually punch, not the way you
test. SHOULD: twenty `PUNCH hand=L`; `PAD … dt=` under 400 ms; travel under the threshold; no
slide-classified event; `steps L R` unchanged (blanking). REPORT: did any punch fail to register,
and did any register as something else?

**L4 · Both hands.** DO: twenty two-handed slaps (both temples at once), then twenty deliberate 1-2s
(left then right as fast as you can, meaning them as two punches). SHOULD: the slaps log `PAIR ms=`
with a p90 the log prints; the 1-2s log intervals clearly above it. REPORT: the two numbers; the
gap between them is `SPECIAL_MS` and it must sit between them with room.

### BLOCK A — THE SENSING (already measured on x3discs; a short re-check with the new dodge scale)

**A0 · Pad contamination.** DO: stand still; ten slaps on each temple, ten right swipes each way.
SHOULD: `STEPS L R F B` unchanged; `BLANK` ticking on each touch; `ACT` pulsing on each tap.
REPORT: did the plate ever count a step you did not take?

**A1 · The rest posture after a round of tapping.** DO: play round 1; at the round-2 card hold
still and read `ROLL` / `PITCH` on the plate against what they were at the coin. SHOULD: the
re-declaration at the card zeroes them; the raw log shows how far the frame had crept. REPORT:
did the horizon or the duck's rest ever feel off during the round?

**A2 · DODGE SENSE.** DO: at LOW / MEDIUM / HIGH, tilt to a comfortable slip and hold, nod to a
comfortable duck and hold, ten each, reading `LEAN X` and `DUCK`. SHOULD: MEDIUM reaches `LEAN X`
±0.55 and `DUCK` 1.00 at a tilt and a nod you could repeat every few seconds for three minutes.
REPORT: which row, and whether the picture leans and drops with you.

**A3 · The aim line.** DO: at rest, throw twenty head jabs while looking at his face; then twenty
body blows, dipping the head as little as you can. SHOULD: `AIM` on the plate reads HEAD for all
twenty of the first and BODY for all twenty of the second; `PUNCH … level=` agrees; no unintended
`level=BODY` while you fought with your head level. REPORT: did the body blow need more nod than
you wanted, or did head shots turn into body blows on their own?

### BLOCK T — THE TIME LAW IN A FIGHT (the test the design lives or dies on)

**T1 · Idle.** DO: `DRILL OFF`, stand still and watch him for twenty seconds. SHOULD: `RATE` 0.35;
he circles at a third speed; nothing looks paused; after 3 s the crowd boos and he feints (R1).
REPORT: did that read as a fight waiting for you, or a game paused?

**T2 · The hang.** DO: `DRILL PECK L`; stand perfectly still at each tell and read it (which eye,
which glove, the shoulder); do NOT move until you have read it. SHOULD: `RATE` snaps to 0.06 the
frame the tell starts (`HANG` sound), the REFLEX rail full and draining, the frozen frame holds,
the arc and the bead are on your face; `HANG` on the plate counts down 0.8 s; then `FUSE` counts
down 1.2 s as the rate ramps to 0.60 and the punch arrives on its own ≈ 2.3 s after the tell began.
REPORT: was the hang long enough to read him? Did the fuse feel like a fair "he won't wait
forever", or like a cheat? Rule on `HANG` (0.5 / 0.8 / 1.2) and the deep floor here.

**T3 · Your move throws his punch.** DO: the same drill; read the tell, then slip. SHOULD: the
moment you move the rate rises and the tell completes; the strike (`forced=STRIKE`, 0.25 s, rate
1.00) runs whether or not you keep moving; the bead has slid off your face if you slipped the right
way. REPORT: did it feel like YOUR move released the punch?

**T4 · The PERFECT.** DO: `DRILL PECK L`; ten times, stand on the line and slip only when the
glove goes (inside the strike). SHOULD: `STRIKE … result=PERFECT` at least 5 of 10 by the second
attempt at the drill; `PERFECT +300`; the crowd swells; the counter window opens and a tap inside
it logs `result=COUNTER` with `forced=` absent (the waived punch). REPORT: does a 0.25 s window
feel like a reflex you chose the moment for, or like unfair? Would you rather 0.33?

**T5 · The crowd is the rate.** DO: eyes closed, twenty seconds: stand still, move, stand still,
snap. SHOULD: the bed hushes and roars within ≈ 200 ms of the rail. REPORT: could you say when the
world stopped without looking?

**T6 · Punches spend time.** DO: `DRILL OFF`, stand still, throw eight blind punches into his
closed guard, then wait. SHOULD: each tap forces the world (`forced=PUNCH` ≈ 0.4 s with the whiff
extension), hearts drain to zero, `WINDED`, and hearts do NOT refill while you stand still (world
time). Then dig two body blows: the guard opens, head punches land, the meter pours in. REPORT:
did the mash feel expensive and the rhythm feel cheap — is the guard's lesson learnable in one
round?

### BLOCK V — THE VERBS

**V1 · The punch and the hit-stop.** DO: twenty jabs that land (guard open by a body blow first).
SHOULD: the glove goes white, the scene kicks 6 px, the clocks stop 70 ms (`forced=HITSTOP`) while
the picture keeps tracking the head; `fps=` 60 throughout. REPORT: did the hit land — and did the
stop read as impact or as a hitch?

**V2 · The 1-2.** DO: twenty left-right pairs at your natural speed. SHOULD: the second punch queues
behind the first (never dropped, never a special with the meter dark). REPORT: did the 1-2 feel
like two punches?

**V3 · The special.** DO: fill the meter (a body blow, then a combo), watch the KO box light, then
slap both temples. SHOULD: `SPECIAL lit=true`, both gloves converge, the starburst, 200 ms of
hit-stop; into a stagger it is a knockdown he does not rise from. Then try it with the meter DARK.
SHOULD: a 1-2, nothing more. REPORT: did the pair register reliably (the L4 number)? Did "both
hands" feel like the big one?

**V4 · The guard.** DO: swipe DOWN and hold on the right pad for 1 / 3 / 8 s, twenty times; then
hold it through a LEFT WING drill. SHOULD: the system shade never opens; the gloves rise on the
crossing and drop on the lift; a flick is 0.5 s; the blocked body hook logs `result=BLOCK` and
`GUARD +150`, and a head hook `result=CRUSH`. REPORT: did a hold ever feel like it might become the
long-press? Does guarding with the right hand while punching with the left feel like a stance?

**V5 · The duck picture.** DO: `DRILL WING R` at `PITCH COMP` 0 / 0.5 / 0.7; duck ten hooks at each.
SHOULD: at 0 his gloves and trunks stay in view at the bottom of the duck and his head leaves the
top; at 0.5 / 0.7 his head stays and the world moves less than your head. REPORT: which one
feels like ducking under a punch, and does any of them turn your stomach? (Decides `PITCH COMP`.)

**V6 · The step.** DO: swipe left, right, back, twenty times; then five real sidesteps at STEP
SENSE MEDIUM; then a step during a `DRILL SUNRISE` in round 3 (`--ei round 3`). SHOULD: `STEP L/R`
once per gesture; the camera slides 0.55 m in 0.35 s and returns; the body steps are recognised
(`BODY`) except inside a pad blank; the tracking uppercut is cleared by a step. REPORT: is the
slide vection? (If yes, `STEP OFF` returns and the tracking uppercut falls back to a late slip.)

### BLOCK F — THE FIGHT

**F1 · Five answers, five drills.** DO: `DRILL PECK L`, `PECK R`, `WING R`, `WING L`, `SUNRISE`,
ten of each, first with `CAPTIONS OFF` and the sound on, then with the sound OFF, then (for the
wings and the uppercut) with your eyes CLOSED and the sound on. SHOULD: each answer reads in one
try from the caricature alone; the stamp and the crow carry the hooks and the uppercut with the
picture off. REPORT: which tell did you read first each time — the eye, the glove, the crest, the
feet, the voice? Which one did you never notice?

**F2 · The fatal answers.** DO: duck the LEFT WING on purpose, three times; duck the SUNRISE once.
SHOULD: 18 and 25 damage, `wake_up`, the corner's tip at the next rest names it. REPORT: was the
lesson clear from the hit itself, before the corner spoke?

**F3 · A full round 1.** DO: `DRILL OFF`, play THE STRUT from the bell. SHOULD: phrase A opens; the
branches follow what you actually did (check `STRIKE answer=` against the pattern table); a
knockdown by the ladder (HP 80) inside the round; the count on the real clock; he rises at 4.
REPORT: did the pattern feel memorisable? Did the count feel like a rest or a wait?

**F4 · Rounds 2 and 3.** DO: play through; note the feints and the tracking uppercut. SHOULD: the
half-peck's two flashes are readable as a lie by the third one; the false crow fools you once; the
double wing is duck-then-guard; R3's Sunrise tracks a slip begun early and misses one begun inside
the strike. REPORT: did round 3 feel like the same opponent who learned, or like a new one?

**F5 · Your own knockdown and the continue.** DO: eat three uppercuts. SHOULD: the view sinks, the
count runs, eight alternated taps rise you before 10; the third knockdown → `GAME OVER` →
`INSERT COIN TO CONTINUE` with the 9 s countdown → round 1, score 0. REPORT: did rising by
tapping feel like a fight, and did the continue feel like a coin?

**F6 · The KO and the tally.** DO: win one (a special in a stagger, or the ladder). SHOULD:
`KNOCKOUT`, the three bells, the roar, the tally with `TIME` and the bonus; `BEST KO` on the title.
REPORT: is `BEST KO` worth chasing? Did the time bonus make you read instead of rush, or rush
instead of read?

**F7 · Time.** DO: stall a whole round deliberately (stand still, never punch). SHOULD: the boo,
the feint, the 3:00 real cap holds the floor at 0.35, the world clock still only burns when you
move; three rounds of it → `TIME - NO DECISION`. REPORT: did the game let you stand still, and did
it tell you honestly that standing still was not winning?

### BLOCK D — THE FEEL

**D1 · The look on-head.** DO: the idle frame, a tell at the floor, a hit, the count, the crowd at
full sway; `screencap` each and read the `eye.py` crop against what you saw. SHOULD: vibrant and
saturated, never grey; the outline three times the detail's weight; the hatch a block; exactly one
white thing on the tell; 12-fps frames never read as judder while the head turns. REPORT: vibrant
or washed? Does he look like a comic, or like a wireframe?

**D2 · Sound.** DO: a full fight with the music on, then with the crowd bed alone. SHOULD: the SFX
sit under the music; the announcer names every landed punch without ever cutting the crow short;
the music never slows. REPORT: music under the fight, or the crowd alone?

**D3 · Endurance.** DO: ten minutes of rounds standing, hands at the temples between exchanges the
way the game invites. REPORT: the neck at DODGE SENSE MEDIUM; the shoulders; whether the corner's
2.2 s is a rest; judder at 60 Hz; whether you ever lost forward.

**D4 · Voices by speech** (asked first, one at a time). DO: the ROOSTER's four lines in two chains
(the fish.audio render; the macOS voice pitched down) back to back; the ANNOUNCER against the
REFEREE; the CORNER close-miked. SHOULD: four speakers named correctly with no captions; the crow
unmistakably the opponent and never the announcer. REPORT: which Rooster? Is the referee the same
room as the announcer and a different person?

---

## 3. What to report back, and where it goes

Each block's answers are written into the doc that owns the number: DODGE SENSE, `HANG`, the deep
floor, `SPECIAL_MS`, `PITCH COMP` and the strike length into DESIGN.md (the row is then removed from
the lab); the tells that were never noticed into BOXER.md §3 (a tell nobody reads is redrawn, not
explained); the left pad's verdict into DESIGN.md §1.5 (whether `LEFT PAD` ships ON). Pull
`cache/motion.log` after every block; the `STRIKE`, `PUNCH` and `PAIR` lines are the evidence when
a feeling and a number disagree. Every ruling is one commit by the integrator, as tropicalstream,
no AI trailer.
