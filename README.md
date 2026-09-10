# X3Knockout

A 1983 disc-battle cabinet rebuilt for a pair of glasses you stand up in, for the RayNeo X3 Pro.
You stand on a platform inside a 12 m glass cell on the game grid; the **Monopoly Control
Protocol**'s conscripts throw identity discs at you from platforms around the edge; the only
weapon you have is the disc in your hand, which flies where you look, banks off the glass and
comes back.

All of it runs under one law, and the law is what makes an arcade cabinet playable on 3-DoF
glasses with no positional tracking:

> **TIME MOVES ONLY WHEN YOU MOVE.**

Stand still and every disc hangs in the air with its future drawn as a line to the point on your
body it is aimed at. Turn your head, step, throw, hop, and the world runs at the speed of your
body. Stillness is where you read the room; motion is the only currency; the temple pad is where
you commit.

> Contains flashing lights (incoming warnings, damage frames, derez). **Play standing, in place.**
> The room stays where it is while you turn, step and hop — clear the space around you first, and
> stop if you feel unwell.

Kotlin + OpenGL ES 3, zero dependencies, zero permissions, no network. Package `com.x3knockout`.

**Status: Phase 1 (prototype).** Levels 1 and 2 are authored and playable end to end; the MOTION
LAB and its guided standing test are on by default. Levels 3–12, the voice choreography, the music
table and the cutscenes are Phase 2 — see `docs/BUILD_PLAN.md`.

## How it plays

- **The clock is your body.** Every frame the world gets `dt × timeScale`, where `timeScale` runs
  from a floor (3 / 5 / 8 % by difficulty) up to 1.0 as you move. The number comes from the raw
  gyro and linear-acceleration magnitudes, not from a smoothed pose, so the world stops when your
  head does rather than 50 ms later. A held posture is *not* motion: lean over and stay there and
  the clock sits back down at the floor.
- **Acting spends time too.** You can always make the world move by doing something, never by
  waiting. A throw buys about a quarter-second of world time, a deflector raise or a recall about
  a tenth, a catch about a twentieth; an empty tap still costs something. A hop forces the rate to
  a full 1.0 for its whole flight — the biggest honest cost in the game, and the reason the
  half-second of invulnerability it buys is not an exploit.
- **A tap means exactly one thing**, chosen by a fixed priority: STEAL an armed enemy disc that is
  in your catch zone, else CATCH your own disc coming home, else THROW, else a soft click.
- **The catch zone is a place, not a moment** — 0.45 to 1.1 m from your eye, inside 12° of your
  gaze, and closing. Catching a disc at leisure while nothing else threatens you is the promise;
  catching one while three others hang is the puzzle.
- **The answers to a disc are the body and the pad, and every lane has one of each.** A HIGH disc
  is ducked (get your head under the line) or deflected, stolen, cut or hopped away from; a LEFT
  or RIGHT one is leaned away from or stepped out of; a CENTRE one has no posture answer at all
  and needs the pad. Nothing in this game is survivable only by a body the glasses cannot feel.
- **The collider is the inferred body.** The glasses know your head's tilt and nothing about where
  your body went, so the body is inferred the way a spine works: a tilt becomes a real sideways
  shift of the eye *and the collider* (up to 0.55 m, shaped so that the noise of standing moves
  nothing), a nod becomes a crouch that drops the eye 0.42 m, and a felt sidestep lunges 0.55 m
  and recovers over 1.1 s. The camera moves with all three, so you see yourself dodge.
- **Every throw is telegraphed.** A program turns to face you, LOCKS (its chest chevron pulses),
  winds its arm up over 0.9 s — 1.4 s if it is behind you — and releases into a lane from a
  pattern that is authored, never rolled. A cabinet's rounds are meant to be memorisable.
- **The floor is a weapon in both directions.** A disc that misses and strikes a platform whitens
  the ring it hit; three world-seconds later that ring derezzes, and a program with no rings left
  falls. In Level 2 — and only in Level 2 — your own rings can fall the same way, which makes the
  floor your health bar and the hop the thing that saves you. Standing still cannot cost you the
  floor: the fuse burns on world time, and the hop that gets you off it is what burns it.
- **Rounds, par, lives, a coin.** Three rounds a level, an authored PAR in *world* seconds (so the
  par bonus scores how little you moved, not how fast you were), the tally, then the cell docks to
  the next configuration and the Grid streams past. A hit restarts the round with a life gone; the
  last life ends in `INSERT COIN TO CONTINUE` — same level, round 1, score reset to zero.

## Controls (right temple pad)

| Gesture | In play | Title / game over | Settings |
|---|---|---|---|
| Tap | **commit**: steal · catch · throw · click | insert coin / continue | adjust the selected row |
| Swipe left / right | **hop** to the adjacent platform | — | adjust the selected value |
| Swipe up | **recall** every disc of yours | — | move the selection up |
| Swipe down, flick | **deflector**, 0.5 s world | — | move the selection down |
| Swipe down, hold | **deflector** until you lift | — | (one step per gesture) |
| Double-tap | settings (pauses; rate 0, no cost) | settings | close |
| Triple-tap | re-centre the head and the rest posture | re-centre | — |

There is no long-press — the glasses reserve a temple hold for the system shade. The left temple
pad is the system volume pad and is ignored. D-pad keys mirror the pad for desk testing
(`DPAD_CENTER` commits, `DPAD_DOWN` held is the deflector, `DPAD_UP` recalls, `DPAD_LEFT/RIGHT`
hop). Forward is re-declared, yaw only, at every round card, which is where drift is hidden for
free.

## Settings (double-tap)

`MUSIC` · `VOLUME` · `VOICE` · `DIFFICULTY` (Easy / Normal / Hard: the floor, disc speed, cooldown,
deflector charges, dawdle and aim assist) · `STEP SENSE` (Low / Medium / High) · `HOP` (off fixes
your platform and pays +2 deflector charges — the comfort fallback, shipped in the same build as
the thing it disables) · `MOTION LAB` · `CREDITS` · `RESET SETTINGS`, and `QUIT` on the title and
the game-over card only. Nine rows at a time in a window that scrolls with the selection.

With `MOTION LAB` on, two more rows appear: `TIME FLOOR` (3 / 5 / 8 / 12 / 20 %, overriding
difficulty) and `HOP T` (0.28 / 0.35 / 0.45 / 0.60 s); debuggable builds add `KNEE` (A / B). All
three exist so the owner can rule on them standing up, and all three go once they have.

`HEAD LOOK` and `TURN` are gone. The game cannot aim without the head, so head tracking is not a
setting — a device with no rotation vector says `HEAD TRACKING REQUIRED` on the title instead. And
a hop left is a swipe left, so there is nothing left for a handedness switch to reverse.

Records (`HIGH`, `BEST LEVEL`, `GAMES`) survive `RESET SETTINGS`, and so do the story flags, which
live in a second prefs file (`x3knockout_story`) for exactly that reason. A debug launch writes
neither.

## The two clocks

This is the one thing in the codebase worth knowing before you change anything in it. Every timer
in the game is on exactly one of two clocks, and `docs/DESIGN.md` §1.5 is the audit list:

- **World time (`wdt = dt × timeScale`)** — enemy discs, your own discs, programs' turning, lock
  dwell, wind-up and cooldown, the volley gap, ring fuses, derez scatter, the deflector's held
  charge drain, and the PAR timer. Everything that can hurt you.
- **Real time (`dt`)** — the HUD and its flashes, captions and cues, the body (the step's lunge and
  recovery, the lean, the duck), the hop's camera slide, the death choreography, the seeker *spawn*
  timer, the round-clear beat and the tally, the docking travel, music, and the stillness schedules
  the machine counts aloud. Everything that belongs to the player or the story.

Get one hostile timer onto the wrong clock and the promise breaks; get one caption onto the wrong
clock and the story freezes while the player stands still thinking. `Clock` is the only thing
allowed to decide what a second is, `TwoClocksTest` proves the wiring on the real engine, and the
check on the glasses is that the rail sits at the floor while the story keeps speaking.

## The MOTION LAB and the guided standing test

`MOTION LAB` (on by default in this prototype) draws every body signal live on the right of the
plate — the rate, the smoothed motion scalar, the action charge, the knee in force, the floor, the
pad-blank window, head and body magnitudes, roll and pitch, the step counts, the posture dial — and
runs a guided calibration at the start of a run: hold still, turn, tilt each way, nod, sidestep
each way, then the verbs (tap, hop, deflect, steal, recall). Swipe up to skip it.

Every 50 ms sample is tagged with the guide phase and written to `cache/motion.log`:

```sh
adb shell run-as com.x3knockout cat cache/motion.log > motion.log
tools/.venv/bin/python tools/analyze_motion.py motion.log
tools/.venv/bin/python tools/analyze_knee.py motion.log
```

## Telemetry

Everything the review process reads goes to logcat under the tag `X3Knockout`: a 10 Hz `CLK` line
(`|w| m act rate floor step blank knee forced`), a 5 Hz `MOTION` line and a 5 Hz `VERIFY` pair
(every disc's range, bearing, lane and ETA in both world and real seconds; every program's beat,
HP and bearing), plus `EVENT`, `ENEMY THROW`, `STEAL` / `CATCH` / `HOP` / `DEFLECT` / `RECALL` /
`RING` / `FALL`, `ROUND` / `CLEAR` / `RESTART` / `CONTINUE`, and `fps=` once a second.

## Build and install

Requires JDK 17, the Android command-line tools (`sdk.dir` in `local.properties`) and the bundled
Gradle wrapper (Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21; compileSdk 35, minSdk 29).

```sh
cd x3knockout
tools/gbuild.sh assembleDebug          # serialised: several agents share this checkout
tools/gbuild.sh testDebugUnitTest      # ClockTest, TwoClocksTest, StrokeModelTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.x3knockout/.MainActivity
```

Debug launches drop straight into a round and never write records:

```sh
adb shell am start -n com.x3knockout/.MainActivity \
  --ei level 2 --ei round 3 --ei lives 1 --ef floor 0.2 --ei restarts 4
adb shell input keyevent 96                       # tap
adb shell input keyevent 4                        # double-tap (settings)
adb shell input swipe 900 240 600 240 150         # hop left
adb shell input swipe 800 150 800 380 1500        # deflector, held
adb shell input swipe 800 380 800 150 120         # recall
```

The app declares itself to the RayNeo launcher (`com.rayneo.mercury.app`, category
`com.rayneo.intent.category.AR_APP`) and renders a 1280×480 side-by-side surface. The left eye is
the left 640 px of a screenshot:

```sh
adb exec-out screencap -p > shot.png
tools/.venv/bin/python tools/eye.py shot.png eye.png
```

## Voices

Every line in `docs/STORY.md` is already rendered to audio by `tools/extract_lines.py` +
`tools/render_voices.py` — the **SYSTEM** (the Protocol: macOS Zarvox through a ring-modulator /
echo / bit-crusher chain), the **PILOT** answering it through a real TTS model, and the announcer,
crowd, stray and User voices. The choreography that plays them — the triggers, the caption maps,
the stillness schedules — is Phase 2 and is not wired yet, so this build is silent but for its
effects and one music track.

Five CC BY 4.0 tracks by Kevin MacLeod, brass and drums for an arcade prizefight — a ska horn
section on the title, trumpet and trombone through the fight, something meaner for round three, 44
BPM of nothing much for a knockdown count, and a fanfare for the win. Verbatim licence strings in
`docs/MUSIC.md` and on the in-game CREDITS page. The old track table and
the credits page with every licence string verbatim arrive with Phase 2.4; the CREDITS row in the
settings is a placeholder until then.

tropicalstream · no network, no permissions.
