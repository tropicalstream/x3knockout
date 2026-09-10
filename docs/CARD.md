# THE CARD — five opponents

> **AMENDED 2026-09-10 — `docs/LAW.md`.** The world's floor is now one constant for the whole game
> (`FLOOR_STILL` 0.03) and no fighter writes it. Two things follow for this document. **The `hang`
> column is deleted** — nobody on the card owns "how long you are given to think" any more, because
> standing still IS the hang and it lasts as long as the player wants; the column is replaced by
> **COMMITMENT**, which is the honest expression of the same idea. And **the Rooster gets harder in
> the same build**, because a deep floor hands the player unlimited read time and a punch that
> costs a third of what it did: without §"THE ROOSTER, MADE A FIGHT" below, the owner's note that
> he is too easy gets worse rather than better.

A ladder of five boxers who are merely faster than each other is one boxer with a difficulty
slider. The arcade this game descends from never did that: its opponents escalate by asking
something NEW, so beating one leaves you able to do something you could not do before. That is the
rule here, and it is why each man owns a mechanic rather than a number.

| # | Fighter | Billing | Teaches | Gimmick |
|---|---|---|---|---|
| 1 | **THE ROOSTER** — Roy Rudd | the strutting champion of nowhere | **the grammar** — colour says everything: a pupil is a jab and which hand, slits are a hook, gold high or drooped is which hook, white is the uppercut | none; slow and loud on purpose |
| 2 | **THE SARDINE** — Sal Marino | never throws just one | **speed** — the same grammar at 78 % length, in chains. Each shot is cheap; the flurry is not | `FLURRY`: chained follow-ups take 60 % of their authored tell |
| 3 | **THE ANVIL** — Duke Odell | he waits for you to swing | **patience** — vast, slow, telegraphed a mile off, hits like a truck. Punch a raised guard and he takes your arm off for it | `COUNTER`: a punch that dies on his guard buys him a free one back |
| 4 | **SILK** — Sorensen | nothing shows on him | **the quiet tell** — no crest, no pupil flash, no white glove. Every tell is posture: a shoulder, a planted foot, a dropped hand | `QUIET`: the colour channel is switched off entirely |
| 5 | **THE METRONOME** — Max Voss | he keeps your time | **mastery** — his clock IS your motion, amplified. Stand still and he crawls; move and he is faster than anyone on the card | `TEMPO`: his own seconds are `0.10 + 2.2 × motion` |

## Why the champion is what he is

Every other opponent teaches you to move at the right moment. The Metronome asks you to *earn* each
of those moments, because his speed is literally your own — the SUPERHOT law turned into the boss.
He reads `body.moving`, the same scalar the world clock reads, so what accelerates him is exactly
what the player can already see accelerating the world on the rail: nothing is hidden, and the
instrument is on screen before he ever appears. His floor is a crawl and never zero, because an
opponent who genuinely stopped could be outlasted by a motionless player and there would be no
fight.

His pattern is deliberately PLAIN — no feint spam, no unreadable chains, long clean phrases. He does
not need tricks. The trick is the game.

## How each man WAITS — the style channel

The arcade's opponents are recognisable across a room before either fighter throws anything, and it
is not their outlines that do it: it is how they wait. The pose strips are shared here, so four
multipliers on top of the same 196 frames carry it, plus how much of a landed punch each one shows.

| | bob | sway | reaction | stature | reads as |
|---|---|---|---|---|---|
| Rooster | ×1.0 | ×1.0 | ×1.0 | 1.00 | the reference: a showboat on his toes |
| Sardine | 2.4 Hz, shallow | 2.0 Hz, tight | ×1.35 | 0.90 | never still; flinches at everything |
| Anvil | 0.45 Hz, deep | almost none | ×0.45 | 1.12 | a slow heave; hardly registers being hit |
| Silk | 0.7 Hz, tiny | 0.55 Hz, wide | ×0.70 | 1.06 | a long lazy weight-shift, showing nothing |
| Metronome | exactly on the beat | **none at all** | ×0.60 | 1.04 | a tick. Square, upright, unnervingly still |

The reaction multiplier is also a fairness lever, not only a characterisation: the hit reaction is
the player's feedback that a punch landed, so taking it away is part of what makes the later fights
read as harder.

Each man's three rounds carry his own names — the round card is the one moment the game says
something about him before he hits you:

| | R1 | R2 | R3 |
|---|---|---|---|
| Rooster | THE STRUT | THE RUFFLE | THE COCKFIGHT |
| Sardine | THE SHOAL | THE BOIL | THE FEEDING |
| Anvil | THE WEIGHT | THE SWING | THE DROP |
| Silk | NOTHING SHOWS | STILL NOTHING | TOO LATE |
| Metronome | ANDANTE | ALLEGRO | PRESTO |

## The numbers

| | HP (E/N/H) | tell | strike | recover | dmg | phrase gap (world s, R1/R2/R3) | openings | feints from |
|---|---|---|---|---|---|---|---|---|
| Rooster | 100/120/140 | ×1.00 | ×1.00 | ×1.00 | ×1.0 | **0.5 / 0.4 / 0.3** | ×1.00 | R2 |
| Sardine | 110/130/150 | ×0.78 | ×0.92 | ×0.85 | ×0.7 | 0.4 / 0.3 / 0.3 | ×0.85 | R2 |
| Anvil | 150/180/210 | ×1.25 | ×1.00 | ×1.15 | ×1.7 | **1.0 / 0.9 / 0.8** | ×1.00 | R2 |
| Silk | 120/145/170 | ×0.88 | ×0.95 | ×0.90 | ×1.1 | 0.6 / 0.5 / 0.4 | ×0.80 | R1 |
| Metronome | 140/170/200 | ×1.00 | ×1.00 | ×0.85 | ×1.4 | 0.5 / 0.4 / 0.3 | ×0.70 | R3 |

**`hangMul` is gone and `Boxer.PHRASE_GAP` becomes a per-fighter `FloatArray(3)` in its place.**
That is not a rename: the old column was a hidden gift of *reading time*, which the law no longer
lets anybody hand out, and the new one is a visible cost in *dead air*, which is the only thing on
this table the player can actually watch. It also keeps the Anvil's character intact — his whole
shape is "throw, then stand there invitingly", and a global gap cut would have flattened him into
everybody else while the Rooster was being tightened.

The Rooster's timing multipliers stay all-1.00 by construction: his profile carries exactly the
numbers that were played and approved, and everybody else's row is read against them. That is why
his difficulty below comes from his **pattern**, not from his multipliers. The re-export stays
byte-identical to the shipped asset, and that is checked rather than asserted (`md5` of `boxer.x3s`
before and after the parameterisation).

## COMMITMENT — the column that replaces the hang

Under a 0.03 floor the frozen frame is the default state, so the question stops being "how long do
you get to read" and becomes **"is there anything in the frame to read?"** An opponent who rarely
commits produces an *empty* frozen frame, which is a different way of being invisible. The measure:

```
COMMITMENT = (world seconds with a TELL or a STRIKE on the glass) / (world seconds in the round)
```

It is computable off the existing 5 Hz `VERIFY him=` line with no new telemetry, and it is the
number this card is now balanced on.

| | R1 today | R1 ships | R3 ships | reads as |
|---|---|---|---|---|
| Rooster | **35 %** | **43 %** | 45 % | the grammar, spoken in whole sentences |
| Sardine | — | 52 % | 58 % | never one shot; the flurry is the phrase |
| Anvil | — | 32 % | 36 % | vast gaps that ARE the bait — his low number is his character |
| Silk | — | 44 % | 48 % | |
| Metronome | — | 46 % | 52 % | |

The Anvil is deliberately the least committed man on the card and must stay so: his phrases are
"throw, then wait", the wait is the trap, and raising his commitment would delete his gimmick. His
pressure comes from `dmgMul ×1.7` and the counter, not from density.

---

## THE ROOSTER, MADE A FIGHT

> **The owner, having played it: "rooster is too easy."**

He is the tutorial and must stay legible, so the rule for this section is the one the brief set:
**the difficulty comes from DENSITY — fewer dead seconds, shorter waits, more phrases per round —
never from shorter tells**, which would make him unreadable and destroy the one thing he exists to
teach.

### What does NOT change, and why each was considered and refused

| lever | verdict | reason |
|---|---|---|
| **tell lengths** (peck 0.50 / wing 0.67 / uppercut 0.83 world s) | **unchanged** | These ARE the teaching. Every other man on the card is a multiplier on them, so shortening the Rooster's shortens the whole ladder by stealth, and a tutorial that cannot be read has no output. |
| **HP** (100/120/140) | **unchanged** | More HP is a *longer* tutorial, not a harder one. His fight ends on the knockdown ladder (80 / 40 / 0) with HP floored at the bell (72 in R2, 42 in R3), so HP is pacing rather than difficulty — and his row is the unit the other four are measured in. |
| **recover lengths** | **unchanged** | The recover is the player's turn. Taking it away is taking away the reward for reading correctly, which is the opposite of teaching. |
| **feints** | **still from R2** | R1's contract is "one attack, one answer"; the lie is R2's lesson ("the third flash is the truth"). A feint in the tutorial makes the grammar unlearnable, and the owner asked for a fight, not a trick. |
| **`openMul`, `dmgMul`, `strikeMul`** | **unchanged at 1.00** | Same reason as HP: his row is the ruler. |

### What changes: the waits, and one more phrase

**1. The neutral waits are roughly halved, and the phrase gap with them.** A `Wait` is dead air —
the guard is up, nothing is committed, nothing is being read. Under a deep floor it is an *empty
frozen frame*, which is the worst thing that can be on the glass. Cutting it removes nothing the
player was using: the read lives in the tell, and the tell is untouched.

| | today | **ships** | floor on how short |
|---|---|---|---|
| A's waits (between pecks) | 1.2 | **0.6** | 0.6 world s is 7 frames of the 8-frame `idle` loop — he visibly resets |
| B / C's waits (between wings) | 1.5 | **0.7** | the wings' recovers are already 0.83 / 0.60, so the reset is longer than the wait |
| D's wait (before the Sunrise) | 1.0 | **0.5** | |
| E's wait | 2.0 | **0.9** | |
| `PHRASE_GAP` | 1.0 (global) | **0.5** (his row) | |

**2. Phrases B and C gain a third attack — the OTHER wing.** B was `#3 (wait) #3` and C was
`#4 (wait) #4`: the same punch twice, which teaches one answer and then repeats itself. They become
`#3 (0.7) #3 (0.7) #4` and `#4 (0.7) #4 (0.7) #3`. The lesson is now inside one phrase instead of
spread across two: **the crest is gold both times, and what changed is its HEIGHT** — fanned for
the head hook, drooped for the body hook. That is exactly the R1 grammar, and it is now taught as a
contrast rather than as two separate drills.

**3. A fifth phrase, F, joins the rotation — and it is the one that makes him a fight.**

```
F:  #2 (0.6) #3 (0.6) #4   →  [guarded through all three] #5
```

Three *different* attacks in a row, each at its full authored tell, asking the player to switch
answer **type** twice with no reset: lean, then duck, then block. Nothing in R1 has ever asked that.
And its branch is the lesson the player has earned by then — hold the guard through all three and
he throws the one punch the guard cannot stop.

F also fixes a quiet defect. `Boxer.rotationFor()` shuffles **four** slots, and R1's rotating pool
is three phrases (`pattern(r).drop(1).filter { hpBelow >= 1f }`), so one shuffled slot has always
been discarded and the "fixed 4-slot rotation" has been doing three slots' worth of work. With F the
pool is four and the rotation finally means what its KDoc says.

### The tables, in full

```kotlin
/** Round 1 — THE STRUT: teach each answer, then ask for two in a row. A opens; B C D F rotate. */
val PATTERN_R1: List<Phrase> = listOf(
    Phrase("A", listOf(
        Step.Hit(Attack.PECK_L), Step.Wait(0.6f), Step.Hit(Attack.PECK_L), Step.Wait(0.6f),
        Step.Hit(Attack.PECK_R), Step.Wait(0.6f), Step.Hit(Attack.PECK_R),
        Step.Branch(Read.DUCKED, next = "C"),
    )),
    Phrase("B", listOf(                       // gold, fanned -- gold, fanned -- gold, DROOPED
        Step.Hit(Attack.WING_R), Step.Wait(0.7f), Step.Hit(Attack.WING_R), Step.Wait(0.7f),
        Step.Hit(Attack.WING_L),
        Step.Branch(Read.SLIPPED_INTO_HIT, next = "B"),
    ), repeats = 1),
    Phrase("C", listOf(                       // the mirror: drooped -- drooped -- FANNED
        Step.Hit(Attack.WING_L), Step.Wait(0.7f), Step.Hit(Attack.WING_L), Step.Wait(0.7f),
        Step.Hit(Attack.WING_R),
        Step.Branch(Read.DUCKED, next = "C", say = Lines.Him.HIT),
    ), repeats = 1),
    Phrase("D", listOf(
        Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R), Step.Wait(0.5f),
        Step.Branch(Read.CENTRED, then = listOf(Step.Hit(Attack.SUNRISE, tellT = TELL_SUCKER))),
        Step.Branch(Read.SLIPPED, then = listOf(Step.Hit(Attack.SUNRISE))),
    )),
    Phrase("F", listOf(                       // lean, then duck, then block -- no reset between
        Step.Hit(Attack.PECK_R), Step.Wait(0.6f), Step.Hit(Attack.WING_R), Step.Wait(0.6f),
        Step.Hit(Attack.WING_L),
        Step.Branch(Read.GUARDED, then = listOf(Step.Hit(Attack.SUNRISE))),
    )),
    Phrase("E", listOf(
        Step.Hit(Attack.SUNRISE), Step.Wait(0.9f), Step.Hit(Attack.SUNRISE),
    ), hpBelow = 0.6f),
)
```

R2 and R3 keep their structure and take the same wait cut, because the same argument applies with
more force where the phrases are already tighter:

| | today | ships |
|---|---|---|
| A2 | 1.0 | **0.6** |
| B2 (the feint's beat) | 0.3 | **0.3** — unchanged; it is the feint's timing, not dead air |
| C2 / D2 | 0.8 / 0.6 | **0.5 / 0.4** |
| E2 | 1.5 | **0.9** |
| A3 / C3 / E3 | 0.6 / 0.4 / 1.0 | **0.4 / 0.3 / 0.6** |

### The arithmetic, so the claim can be checked

Attack costs in world seconds at R1 (tell + strike + recover, from BOXER.md §3), with the
*committed* part — tell + strike — in brackets: peck L 1.25 [0.75], peck R 1.30 [0.75],
wing R 1.83 [1.00], wing L 1.60 [1.00], Sunrise 2.33 [1.16].

| phrase | today | attacks | committed | **ships** | attacks | committed |
|---|---|---|---|---|---|---|
| A | 8.70 s | 4 | 34 % | **6.90 s** | 4 | **43 %** |
| B | 5.16 s | 2 | 39 % | **6.66 s** | 3 | **45 %** |
| C | 4.70 s | 2 | 43 % | **6.43 s** | 3 | **47 %** |
| D | 5.88 s | 3 | 45 % | **5.38 s** | 3 | **49 %** |
| F | — | — | — | **5.93 s** | 3 | **46 %** |
| **a round** | A + 2.7 laps of B C D | **22.8** | **35 %** | **A + one lap of B C D F** | **16** | **43 %** |

Two things to read off that last row, because they are easy to confuse.

- **Attacks per WORLD second rise 24 %** (0.380 → 0.473) and **dead neutral air falls from 39 % to
  26 %** of the round. That is the density the owner asked for.
- **Attacks per round fall from 22.8 to 16, and that is correct**, because `ROUND_WORLD_S` drops
  from 60 to 36 in the same change (LAW.md §7 — at a 0.03 floor a 60-world-second round is eight
  real minutes). At 36 world seconds his round is **exactly the opening plus one full lap** (33.8
  world s), so the bell falls two seconds after the last phrase closes and the player sees each
  phrase once. That is a far more learnable round than today's two-and-two-thirds laps, and it is
  why 36 was chosen rather than fitted.

### The change that is NOT in this document but decides whether any of it works

`LAW.md` §6: the forced punch rate drops from 1.0 to 0.55, so a landed punch costs **0.083** world
seconds instead of 0.150. **Every world-timed window that is meant to be "measured in your punches"
must be divided by 1.82** — the stagger, its cap and extension, and all four `GUARD_OPEN_*`
constants. If that divisor is skipped, every opening the Rooster gives yields 1.8× more punches and
he ends up *easier* than the man the owner already found too easy, no matter what this section does
to his pattern.

---

## What the card owes the voice

`docs/VOICE.md` keys every fighter-dependent line as `<stem>_<fighter.id>` and resolves it once per
bout, so `Fighter` grows three fields and nothing else has to change to add a sixth man:

| | `weightLb` | `chantWord` | `tellDialect` |
|---|---|---|---|
| Rooster | 120 | ROO-STER | CREST |
| Sardine | 112 | SAR-DINE | LIT |
| Anvil | 248 | AN-VIL | LIT |
| Silk | 160 | SILK | QUIET |
| Metronome | 175 | VOSS | LIT |

`weightLb` replaces `Hud.INTRO_CORNER`'s hardcoded `"120 LB - FAR CORNER"`; `chantWord` replaces
`Fight.kt:1813`'s hardcoded `"ROO-STER"`; `tellDialect` picks which of the corner's three colour
dialects he is spoken to in, because `tip_wing_r` says "Gold crest" and only the Rooster has a
crest. The champion's chant is his **surname** — a crowd shouts two syllables, never
"MET-RO-NOME".

## The ladder

A **KO advances the card**; a **loss does not**. A continue is always a rematch with the man who
just beat you, never a promotion — the arcade's own rule, and the one that makes the card a record
of what you can actually beat rather than of how many coins you fed it. The furthest rung lives
with the RECORDS (`boutReached`), so `RESET SETTINGS` cannot quietly demote anybody and a debug
launch cannot promote them.

Beating the Metronome leaves the card where it is, so the champion can be fought again instead of
the ladder silently wrapping round to the Rooster and making the achievement disappear.

## One rig, five men

The card shares ONE skeleton and ONE set of pose strips: 32 parts, 7 markers, 196 frames, 20 strips,
identical across all five. What differs is the SILHOUETTE hung on them and the palette. That is not
a shortcut — it is the economy the arcade ran on, and it is what makes five opponents affordable
inside this device's vertex budget (each `.x3s` is ~2 MB; the busiest idle frame is 421–457
segments against a 700 cap).

Silhouettes are **authored, not derived**. Deriving them from the Rooster by multipliers looked
tidier and quietly moved him — 0.22 × 1.09 is 0.2398, not the 0.24 his trunks were drawn at — so
each fighter's numbers are written out in `LOOKS` in `blender/assets/boxer.py`.

```sh
X3_FIGHTER=anvil blender -b --python blender/export_strokes.py -- \
    blender/assets/boxer.py app/src/main/assets/models/boxer_anvil.json
```

`X3_FIGHTER` unset (or `rooster`) writes `boxer.x3s`, unchanged.

## The counter, and why it lives where it does

The Anvil's punish is thrown from the head of the pattern interpreter, ahead of the wait and the
phrase — not from a timer. The first attempt armed a countdown and waited for an idle frame; the
pattern reached its own next tell 0.4 s later and the punish simply never arrived. The code read
perfectly well and it was only caught on the glasses, in the log:

```
PUNCH hand=R level=HEAD result=GUARD dmg=0     <- the punch dies on his guard
TELL  attack=PECK_R  tellT=0.63  phrase=N3     <- ...and the PATTERN answers, not the counter
```

It also cancels his wait. His long waits are the bait — the shape of his pattern is "throw, then
stand there invitingly" — so an answer that arrived a second and a half later would not be
connected to the punch that earned it. Now:

```
PUNCH hand=R level=HEAD result=GUARD dmg=0
TELL  attack=WING_R  tellT=0.38                <- 2 ms later, and visibly shorter than his usual
```

## Testing a specific man

```sh
adb shell am start -n com.x3knockout/.MainActivity --ei bout 3     # 1-based: 3 = THE ANVIL
```

A bout override counts as a harness: it disables records, because walking straight into the
champion is not an achievement.
