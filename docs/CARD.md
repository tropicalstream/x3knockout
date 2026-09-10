# THE CARD — five opponents

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

## The numbers

| | HP (E/N/H) | tell | strike | recover | dmg | hang (the read) | openings | feints from |
|---|---|---|---|---|---|---|---|---|
| Rooster | 100/120/140 | ×1.00 | ×1.00 | ×1.00 | ×1.0 | ×1.00 | ×1.00 | R2 |
| Sardine | 110/130/150 | ×0.78 | ×0.92 | ×0.85 | ×0.7 | ×0.85 | ×0.85 | R2 |
| Anvil | 150/180/210 | ×1.25 | ×1.00 | ×1.15 | ×1.7 | ×1.10 | ×1.00 | R2 |
| Silk | 120/145/170 | ×0.88 | ×0.95 | ×0.90 | ×1.1 | ×0.90 | ×0.80 | R1 |
| Metronome | 140/170/200 | ×1.00 | ×1.00 | ×0.85 | ×1.4 | ×1.00 | ×0.70 | R3 |

The Rooster's row is all 1.00 by construction: his profile carries exactly the numbers that were
already played and approved, so putting the card in front of him changed nothing about his fight.
The re-export is byte-identical to the shipped asset, and that is checked rather than asserted
(`md5` of `boxer.x3s` before and after the parameterisation).

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

## Testing a specific man

```sh
adb shell am start -n com.x3knockout/.MainActivity --ei bout 3     # 1-based: 3 = THE ANVIL
```

A bout override counts as a harness: it disables records, because walking straight into the
champion is not an achievement.
