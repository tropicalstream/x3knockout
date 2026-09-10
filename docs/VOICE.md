# THE VOICE — the script, and the five who speak it

This document is the **build input**, not a description of one. `tools/extract_lines.py` parses the
tables in §6 into `tools/lines.json`, and `tools/render_voices.py` reads only that. Nothing is
hand-copied into a render script, because that is how a game ends up with a clip nobody can trace
to a line.

**Two edits are required before this document does anything.** `extract_lines.py` reads
`docs/STORY.md`, which does not exist in this project, and its `SPEAKERS` set names x3discs'
characters. Repoint it at this file and widen the set (§7.1). Until then `--check` prints
`0 lines` and passes.

---

## 1. THE SEPARATION LAW

> Four speakers can be heard inside any ten seconds of a fight — the **ANNOUNCER**, the
> **REFEREE**, the **CORNER**, and the **man in front of you** — plus the crowd. Any two of them
> must differ on **at least two** of { base voice, pitch register, room length, speaking rate }.
> Two men on **different rungs of the card** may share a base voice, because they are never in the
> same fight. The three officials and the crowd may never share one with each other, or with the
> fighter currently up.

That single rule is what resolves the shortage of good macOS male voices without weakening anything
the player actually hears. DESIGN.md §9.4 named five speakers with the fifth being "ROOSTER"; the
card has five men now, so the fifth speaker is not a character but a **role — THE MAN IN THE RING**.
There is only ever one of him on the glass, which is what makes the roster affordable.

| Speaker | Track | Base voice | Rate | Room | Register | Reads as |
|---|---|---|---|---|---|---|
| ANNOUNCER | `voice/` m4a | Ralph | 165 | long dual echo 180 / 320 ms | low, gravelled | a man on the house PA who has done a thousand of these |
| REFEREE | `voice/` m4a | Fred | 186 | one 42 ms slap | nasal, mid-high | in the ring with you, no microphone, shouting |
| CORNER | `voice/` m4a | Reed | 190 | **none at all** | full-band, present | thirty centimetres from your ear |
| THE MAN IN THE RING | `voice_hero/` mp3 | five (§2) | five | one 18 ms slap | five | 2.6 m away, behind a gumshield |
| CROWD | `voice/` m4a | six voices summed | 146–152 | band-limited smear | no consonants | the gallery |

**The CORNER is the only voice in the game with no reverb and content above 3.8 kHz.** That single
fact identifies him instantly on a small headset speaker, and it is why he is the one who teaches.

---

## 2. THE CAST — seven models, chosen by the owner (2026-09-10)

**THE THIRD CASTING IS THE ONE THAT SHIPS.** The first was macOS's 1980s formant synths, and the
owner's verdict was the whole argument: *"the voices sound too robotic. this is an emotional sport
not a reobot competition."* The second put real voices on the ANNOUNCER, the REFEREE and the
CORNER but left the five boxers as ONE fish.audio model pitch-shifted five ways — which is a
costume, not a cast. A flyweight and a heavyweight are not the same performance at different
speeds; they are different people, and on a card whose entire premise is five men you can tell
apart across a dark room, the ear has to be told that too.

So the owner picked seven models by URL, and they are the cast:

| Role | fish.audio reference id | Chain | Why this position needs a person |
|---|---|---|---|
| ANNOUNCER | `ac192aa6102d4d669e1af4e4351cf89d` | long double echo + gentle compression | the house PA: the institution, the city, the number, the title |
| REFEREE | `1443bdae8a9546d6bb451cc4816cfdfd` | one 38 ms slap + firmer compression | in the ring with you, no microphone |
| CORNER | `f54c4c98cacc4c12ba3d897c5a866a4b` | compression only, NO room | the old trainer, 30 cm from your ear |
| rooster | `1bf2dee1ca2848b5bc0580a4d9301341` | the shared BOXER slap | 120 lb of showboat, too pleased with himself |
| sardine | `97050f3ee6dd49f8b2b58de51ed21269` | " | never says one thing either |
| anvil | `44db4aafb5ff45a7b268beaeead5dec7` | " | a wardrobe talking |
| silk | `a5f60dc6887548c2bec5190c95d26dee` | " | dry, quiet, nothing shows — not even volume |
| metronome | `40943e1f497c4256b23d7bc29b0e26f6` | " | flat, even, no affect: the champion keeps your time |

**`BOXER_SHIFT` IS GONE.** Shifting a cast voice undoes the casting, and every semitone of it was
there to fake a difference that is now real. All five men share the one `BOXER_CHAIN` so they
stand in the same physical space and differ only as people, which is exactly what §1 asks of them.

**THE CORNER WAS THE LAST HOLD-OUT AND HE IS CAST TOO.** He is the only voice that is never in the
room — 30 cm from the player's ear, dry, no reverb at all — so he is separated from the other six
by SPACE as well as by timbre, and his chain is the only one with no `aecho` in it at all. He also
carries the story (§6.6), which made him the last position that could have stayed synthetic.

**NOTHING IS LEFT ON macOS `say` BUT THE CROWD**, which is not a person: six voices detuned ±40
cents and offset 30–90 ms, summed. A crowd rendered from one real voice six times is a chorus,
which is what it sounded like the first time.

**A CLIP'S VOICE IS ITS SPEAKER, EXCEPT FOR THE MAN IN THE RING.** `render_voices.fish_id` reads
the fighter suffix ONLY for the `BOXER` speaker, so `intro_anvil` is the ANNOUNCER naming Duke
Odell and `guard_anvil` is Duke Odell talking. A BOXER clip with no suffix is the fallback the
engine reaches for when a man has no line of his own, and it is rendered in the first man's voice.

**Silk's crow is the one place this design nearly broke, and it must not be simplified away.**
`Attack.SUNRISE` carries `tellSfx = -1` (`Boxer.kt:278` — "the Sunrise is crowed, not played"), so
**the crow is the uppercut's only audio tell**, and the uppercut is 25 damage and unblockable. But a
man whose whole character is that nothing shows must not shout "Rise and shine". So Silk's crow is a
**breath, not a word**: a 440 ms exhale that carries the timing and none of the information, and his
cut crow is the same file trimmed to 220 ms with a 40 ms fade, which is exactly what a feinted
breath sounds like. The `[[inpt PHON]]` phoneme escape was tried and **does not survive `say -o`** —
it rendered the literal bracket text at 4.2 s — so the word is "Hup." and the `lowpass=f=5200` plus
`volume=0.75` do the rest. If it still reads as a word on-glass, the fallback is a synthesised
breath in `Sfx` (a noise burst through a 900 Hz band-pass with a 120 ms exponential decay) fired
from `onTell` alongside an empty crow; the plumbing already tolerates `voices.crow == ""`.

---

## 3. THE CHAINS — verified ffmpeg, rendered end to end on this machine

Copy these verbatim into `tools/render_voices.py`, replacing the six inherited x3discs chains.

```python
ANNOUNCER_CHAIN = ("highpass=f=280,lowpass=f=3200,acompressor=threshold=-16dB:ratio=3,"
                   "aecho=0.82:0.85:180|320:0.36|0.22,volume=1.9,alimiter=limit=0.95")

REFEREE_CHAIN   = ("highpass=f=250,lowpass=f=3800,acompressor=threshold=-20dB:ratio=4,"
                   "aecho=0.9:0.25:42:0.16,volume=2.2,alimiter=limit=0.95")

CORNER_CHAIN    = ("highpass=f=110,lowpass=f=7000,equalizer=f=1400:t=q:w=1.2:g=4,"
                   "acompressor=threshold=-18dB:ratio=3.5,loudnorm=I=-16:TP=-1.5")

def boxer_chain(semitones, extra=""):
    r = 2 ** (semitones / 12.0)
    # THE LEADING aresample=44100 IS NOT DECORATION -- see the hazard below.
    pre = (f"aresample=44100,asetrate=44100*{r:.6f},aresample=44100,atempo={1/r:.6f},"
           if semitones else "aresample=44100,")
    return (pre + extra +
            "highpass=f=120,lowpass=f=5200,aecho=0.95:0.2:18:0.12,"
            "acompressor=threshold=-18dB:ratio=3,loudnorm=I=-17:TP=-1.5")

CROWD_VOICES = [("Albert",150),("Xander",150),("Thomas",150),
                ("Karen",146),("Moira",152),("Samantha",148)]
# per voice i: aresample=44100,asetrate=44100*{2**(((i-2.5)/2.5*40)/1200)},aresample=44100,adelay={30+12*i}|{...}
# summed:     amix=inputs=6:duration=longest:normalize=0,highpass=f=200,lowpass=f=3400,volume=1.6,alimiter=limit=0.95
```

**HAZARD — `asetrate` on a `say` render.** `say -o x.aiff` writes **22050 Hz mono** (verified with
ffprobe). x3discs' `BUILD_CHAIN` uses `asetrate=44100*0.8409,aresample=44100,atempo=1.1892` and is
correct *there*, because BUILD processes a 44.1 kHz fish.audio mp3 and never a `say` render. Copy
that idiom onto a 22050 Hz aiff — which is exactly what the per-fighter pitch shift does — and you
get **+9 semitones at half length**, not −3 semitones at the same length. The next person will copy
the x3discs line; leave the comment in.

**Two deliberate departures from the inherited chains, both improvements.**

1. **The crowd is six DIFFERENT voices, three of them female** (x3discs summed six Zarvoxes). A
   crowd is a spread of *people*, and mixed sex is what makes a summed chorus read as a room rather
   than as a chorus effect. All six exist and render; chants land at 1843–2520 ms.
2. **The referee's numerals 5–10 have the crowd BAKED IN at render time.** DESIGN.md §9.3 wants
   "the count spoken along from 5", but `VoiceBus` is one-speaker-at-a-time, so the crowd literally
   cannot count *with* the referee at runtime. Compositing costs nothing at runtime and sounds
   correct:

   ```
   ffmpeg -i ref_n.wav -i crowd_n.wav -filter_complex \
     "[1:a]adelay=60|60,volume=0.40[c];[0:a][c]amix=inputs=2:duration=longest:normalize=0,\
      atrim=0:0.95,afade=t=out:st=0.87:d=0.08,alimiter=limit=0.95"
   ```

   Verified: `ref_5` 594 ms, `ref_8` 368 ms, `ref_10` 380 ms — all inside the one-per-real-second
   budget. `Fight.kt:1666`'s standalone `Sfx.CROWD_OH` from count 5 is then deleted; one bus means
   one speaker.

---

## 4. THE KEYING — a new fighter is a row, never new code

Every line that depends on which man is in the ring has an id of the form `<stem>_<fighter.id>`.
Eight stems: `intro` · `chant` · `down` (the announcer's and the crowd's side) · `crow` ·
`crow_cut` · `hit` · `taunt` · `guard` (his own side, on `voice_hero/`).

Resolution happens **once per bout**, never in the hot path, into a small value object beside
`Lines` in `Terms.kt` — the file both parallel authors share:

```kotlin
/**
 * EVERY VOICE ID THAT DEPENDS ON WHICH MAN IS IN THE RING, resolved once when the bout is set up.
 *
 * The alternative -- building "crow_" + fighter.id at each call site -- puts string surgery on the
 * telegraph's path and makes a missing clip a SILENT FAIRNESS BUG: the Sunrise has tellSfx = -1
 * (Boxer.Attack line 278), so the CROW IS ITS ONLY AUDIO TELL, and a man with no crow file has a
 * 25-damage unblockable that nobody can hear coming. So resolution is eager, it falls back to the
 * Rooster's clip rather than to silence, it is logged once per bout, and [hero] is a real SET
 * rather than a substring test -- `guard_silk` and `tip_wing_r_quiet` must never be told apart by
 * a lastIndexOf.
 */
class FighterVoice(f: Fighter, has: (String) -> Boolean) {
    private fun pick(stem: String) = "${stem}_${f.id}".takeIf(has)
        ?: "${stem}_rooster".takeIf(has) ?: ""
    val intro = pick("intro");  val chant = pick("chant");   val down = pick("down")
    val crow  = pick("crow");   val crowCut = pick("crow_cut")
    val hit   = pick("hit");    val taunt = pick("taunt");   val guard = pick("guard")
    /** The ids that live on the hero track. Empty stems are absent by design (Silk has no taunt). */
    val hero: Set<String> = setOf(crow, crowCut, hit, taunt, guard).filter { it.isNotEmpty() }.toSet()
    /** Boxer.kt speaks in STEMS and knows nothing about the card; this turns a stem into a clip. */
    fun resolve(stem: String) = when (stem) {
        Lines.Him.CROW -> crow; Lines.Him.CROW_CUT -> crowCut; Lines.Him.HIT -> hit
        Lines.Him.TAUNT -> taunt; Lines.Him.GUARD -> guard; else -> stem
    }
}
```

`has` is `{ id -> host.hasVoice(id) || host.hasHero(id) }`. Build it in `Fight.newFight()` and
`setBout()`, and log the resolution the way the codebase logs everything else:

```
VOICE bout=anvil intro=intro_anvil crow=crow_anvil taunt=taunt_anvil chant=chant_anvil hero=5
```

### 4.1 The corner speaks a dialect, because the colour channel varies across the card

Four of the corner's eight tips name a colour channel, and colour is exactly what the card varies.
`tip_wing_r` says "Gold crest" and `Fighter.hasCrest` is literally `colourTells && id == "rooster"`;
Silk has no colour at all. Today's tips would actively mislead on four of the five fights. A new
`Fighter.tellDialect` picks one of three:

| dialect | fighters | resolves to |
|---|---|---|
| CREST | rooster | the bare stem |
| LIT | sardine, anvil, metronome | stem + `_lit`, falling back to the bare stem |
| QUIET | silk | stem + `_quiet`, falling back to the bare stem |

```kotlin
/** The corner speaks the dialect of the man in front of him; a missing variant falls back. */
fun tip(stem: String, d: Fighter.Dialect, has: (String) -> Boolean): String {
    if (stem.isEmpty()) return ""
    val suf = when (d) { Fighter.Dialect.CREST -> ""; Fighter.Dialect.LIT -> "_lit"
                         Fighter.Dialect.QUIET -> "_quiet" }
    return (stem + suf).takeIf(has) ?: stem.takeIf(has) ?: ""
}
```

Only three tips need all three variants. `tip_wing_l` and the four generic tips need none, and
`tip_peck` serves CREST and LIT alike because both have a lit glove. **Adding a sixth fighter is
therefore: one row in `Fighter.CARD`, eight rows in §6, and zero lines of Kotlin.**

---

## 5. THE INTRODUCTIONS — the owner's request, staged

### 5.1 The shape

A real pre-fight has two officials with two jobs and they are not interchangeable: the **ring
announcer names the fighters**, the **referee instructs them**. Four clips, three of them shared:

| # | id | speaker | measured |
|---|---|---|---|
| 1 | `intro_<fighter>` | ANNOUNCER | 2161–3886 ms |
| 2 | `ref_instr` | REFEREE | 1820 ms |
| 3 | `ref_protect` | REFEREE | 1728 ms |
| 4 | `ref_touch` | REFEREE | 750 ms |

Ceremony totals, measured through the real chains: rooster **6744 ms** · sardine 7121 · anvil 6694 ·
silk 6459 · metronome 8184. The shared referee block is 4298 ms; only clip 1 changes per man.

**The weight, the corner and the billing are NOT spoken.** `Hud.intro()` already draws `introName`,
`introBilling` and the corner line on the plate. The division of labour: **the plate carries the
numbers, the voice carries the theatre.** The first draft said "In the far corner, at one hundred
and twenty pounds — Roy, the Rooster, Rudd." and measured **3920 ms**, a second and a half of it
repeating what was already on the glass; the Metronome's version hit 5488 ms. Cutting the redundancy
saves 1.5 s a fight for nothing.

### 5.2 The trigger — no new state, no new timer

```kotlin
private fun enterIntro() {
    state = State.INTRO; stateT = 0f; introSkipWanted = false; introEnded = false
    boxer.taunt()
    ceremony = listOf(voices.intro, Lines.REF_INSTR, Lines.REF_PROTECT, Lines.REF_TOUCH)
                   .filter { it.isNotEmpty() }
    // THE CEILING IS THE SCRIPT'S OWN LENGTH, not a constant. A hardcoded INTRO_MAX_T guillotines
    // a ceremony somebody made longer, and does it silently: the fight just starts mid-sentence.
    introCap = ceremony.sumOf { host.voiceDurationMs(it) } / 1000f + INTRO_TAIL_T   // tail 2.0 s
    if (introCap < INTRO_MIN_T) introCap = INTRO_MAX_T          // no manifest: fall back
    host.sayAll(ceremony)
}

fun onVoiceLineEnd(id: String) {
    if (state != State.INTRO) return
    if (id == ceremony.lastOrNull()) introEnded = true     // was: id == Lines.INTRO_3
    if (introSkipWanted) enterRoundCard()
    else if (introEnded && stateT >= INTRO_MIN_T) enterRoundCard()
}
```

and in `updateIntro()`, `stateT >= INTRO_MAX_T` becomes `stateT >= introCap`. `INTRO_SKIP_T` (1.5 s)
and `INTRO_MIN_T` (3.0 s) are unchanged, and the existing "a tap ends the ceremony at the END of the
current line, never mid-line" behaviour is preserved for free.

### 5.3 The measured timeline (the Rooster, VOICE on, no skip)

| t (s) | what happens |
|---|---|
| 0.00 | `enterIntro()` — `boxer.taunt()`, `sayAll(ceremony)` |
| 0.00 | ANNOUNCER `intro_rooster`; the bout card is already on the plate |
| 2.45 | REFEREE `ref_instr` starts → `onVoiceLineStart` sets `refCentre = true`, `refArmUp = true` |
| 2.45–3.05 | the referee walks from the far-left post to centre on the existing 0.2 s exponential ease, right arm rising to 1.15 rad: hands out between the fighters |
| 4.27 | `ref_protect` |
| 6.00 | `ref_touch` starts → `refArmUp = false`, the arm drops |
| 6.74 | `onVoiceLineEnd(ref_touch)` — it is `ceremony.last()`, and `stateT` 6.74 ≥ 3.0 → `enterRoundCard()`; `refCentre = false`, he walks back during the card |
| 6.74–7.94 | ROUND_CARD 1.2 s: `ROUND 1` / `THE STRUT`; the yaw is re-declared |
| 7.94 | `enterFight()` — three bells at 0.45 s, and REFEREE `ref_box` "Box!" (urgent) |

A tap at ≥ 1.5 s ends it at the current line's end: worst case 2.45 s, best case immediate.

### 5.4 The staging — the referee is already in the scene

`GLRenderer.refereeScene(dt)` (line 957) keys everything off
`counting = f.state == State.KNOCKDOWN_COUNT`. Widen it:

```kotlin
val centre = f.state == State.KNOCKDOWN_COUNT || f.refCentre
val armTarget = when {
    f.state == State.KNOCKDOWN_COUNT -> if (f.countN % 2 == 1) 2.6f else 0.25f
    f.refArmUp                       -> 1.15f      // the instructions: hands out
    else                             -> 0f
}
```

`Fight` gains `var refCentre = false; private set` and `var refArmUp = false; private set`, written
only from `onVoiceLineStart` / `onVoiceLineEnd` and cleared in `enterRoundCard()`. Six lines, and
the ceremony stops being a disembodied PA and becomes two people in a ring.

### 5.5 The referee's other seven moments

DESIGN.md §0.1 rule 3 names the referee as a pressure instrument, so he gets a job in the fight and
not only at the start and on the canvas: `ref_box` at every bell (**replacing `Lines.FIGHT`**),
`ref_work` on the round 1–2 stall ahead of the corner, `ref_break` at the round-end bell,
`ref_neutral` on his knockdown and on a tap during his count, `ref_stop` on the TKO, `ref_time` at
the final bell.

`ref_break` is the honest home for the owner's "break": the game has no clinch, and a break that
fired on nothing would be worse than none — but **the bell separating two fighters IS a break**, it
is always correct, and it uses the word.

---

## 6. THE SCRIPT — 113 clips (84 + the career's 17 + 12 rendered earlier)

**The tables below are parsed.** Their shape is the contract in §7; do not reformat them. Cells
after the fourth are for humans and the parser ignores them. `urg` is the bus's `urgent` flag and
`pat` the patience in milliseconds — a line whose patience expires while it waits behind another is
**dropped unheard**, so those numbers are load-bearing and are derived from the measured lengths in
the last column (§7.3).

## 6.1 ANNOUNCER

| id | speaker | text | when | urg | pat | ms |
|---|---|---|---|---|---|---|
| `intro_rooster` | ANNOUNCER | Roy, the Rooster, Rudd! | ceremony 1 | sayAll | 6000 | 2446 |
| `intro_sardine` | ANNOUNCER | Sal, the Sardine, Marino! | ceremony 1 | sayAll | 6000 | 2823 |
| `intro_anvil` | ANNOUNCER | Duke, the Anvil, Odell! | ceremony 1 | sayAll | 6000 | 2396 |
| `intro_silk` | ANNOUNCER | Sorensen. Silk. | ceremony 1 | sayAll | 6000 | 2161 |
| `intro_metronome` | ANNOUNCER | Your champion. Max, the Metronome, Voss! | ceremony 1 | sayAll | 6000 | 3886 |
| `left` | ANNOUNCER | Left. | a landed left | no | 300 | 840 |
| `right` | ANNOUNCER | Right. | a landed right | no | 300 | 800 |
| `body_blow` | ANNOUNCER | Body blow. | a landed body blow | no | 300 | 1050 |
| `counter` | ANNOUNCER | Counter. | a COUNTER lands | no | 300 | 840 |
| `down_rooster` | ANNOUNCER | Down goes the Rooster! | his knockdown | yes | — | 1706 |
| `down_sardine` | ANNOUNCER | Down goes the Sardine! | his knockdown | yes | — | 1750 |
| `down_anvil` | ANNOUNCER | Down goes the Anvil! | his knockdown | yes | — | 1700 |
| `down_silk` | ANNOUNCER | Down goes Silk! | his knockdown | yes | — | 1550 |
| `down_metronome` | ANNOUNCER | Down goes the Metronome! | his knockdown | yes | — | 1869 |
| `sleep` | ANNOUNCER | That one put him to sleep. | a SPECIAL in STAGGER, final | yes | — | 1929 |
| `winner_ko` | ANNOUNCER | Winner, by knockout. The challenger! | the KO card | yes | — | 3351 |
| `winner_tko` | ANNOUNCER | Stopped. Technical knockout. | the KO card after a TKO | yes | — | 2823 |
| `winner_belt` | ANNOUNCER | And the new champion! | the KO card on the last bout | no | 4000 | 1645 |
| `no_decision` | ANNOUNCER | Time. No knockout. No decision. | end of round 3, no KO | no | 1500 | 3691 |
| `end_of_line` | ANNOUNCER | That's the card. Good night. | QUIT | yes | — | 2625 |

## 6.2 REFEREE

| id | speaker | text | when | urg | pat | ms |
|---|---|---|---|---|---|---|
| `ref_instr` | REFEREE | Obey my commands at all times. | ceremony 2 | sayAll | 6000 | 1820 |
| `ref_protect` | REFEREE | Protect yourself at all times. | ceremony 3 | sayAll | 6000 | 1728 |
| `ref_touch` | REFEREE | Touch gloves. | ceremony 4, the last id | sayAll | 6000 | 750 |
| `ref_box` | REFEREE | Box! | every bell | yes | — | 545 |
| `ref_break` | REFEREE | Break! To your corners. | the round-end bell | yes | — | 1727 |
| `ref_work` | REFEREE | Box! Let's work. | the stall, rounds 1 and 2 | no | 1200 | 1461 |
| `ref_neutral` | REFEREE | Neutral corner. | his knockdown; a tap during his count | no | 2200 | 920 |
| `ref_stop` | REFEREE | That's three. I'm stopping it. | the TKO | no | 2500 | 1860 |
| `ref_time` | REFEREE | Time! | the final bell, no KO | yes | — | 510 |
| `ref_1` / `_2` / `_3` / `_4` / `_5` / `_6` / `_7` / `_8` / `_9` / `_10` | REFEREE | One. / Two. / Three. / Four. / Five. / Six. / Seven. / Eight. / Nine. / Ten. | the count, one per real second, crowd baked in from five | yes | — | 368–600 |

## 6.3 CORNER

| id | speaker | text | when | urg | pat | ms |
|---|---|---|---|---|---|---|
| `put_him_away` | CORNER | Put him away! | the meter crosses 26 | no | 2000 | 1018 |
| `get_up` | CORNER | Get up! | your count, at four | no | 800 | 845 |
| `stick_and_move` | CORNER | Stick and move. | the boo in round 1, behind ref_work | no | 2000 | 1183 |
| `tip_peck` | CORNER | Lean off the lit glove. | the corner — CREST and LIT | no | 3000 | 1518 |
| `tip_peck_quiet` | CORNER | Lean off his shoulder. | the corner — QUIET | no | 3000 | 1326 |
| `tip_wing_r` | CORNER | Gold crest. Duck. | the corner — CREST | no | 3000 | 1773 |
| `tip_wing_r_lit` | CORNER | Gold glove. Duck. | the corner — LIT | no | 3000 | 1789 |
| `tip_wing_r_quiet` | CORNER | Back foot plants. Duck. | the corner — QUIET | no | 3000 | 2029 |
| `tip_wing_l` | CORNER | Block the low one. | the corner — all | no | 3000 | 1325 |
| `tip_sunrise` | CORNER | He crows. Get off the line. | the corner — CREST | no | 3000 | 2286 |
| `tip_sunrise_lit` | CORNER | He calls it. Get off the line. | the corner — LIT | no | 3000 | 2290 |
| `tip_sunrise_quiet` | CORNER | Hands drop. Get off the line. | the corner — QUIET | no | 3000 | 2382 |
| `tip_guard` | CORNER | Forget his gloves. Body. | punches wasted on a closed guard | no | 3000 | 2063 |
| `tip_still` | CORNER | Read him, then move. | stalls booed | no | 3000 | 1486 |
| `tip_step` | CORNER | Don't punch and run. | body steps blanked | no | 3000 | 1485 |
| `tip_special` | CORNER | Meter's lit. Both hands. | a lit meter never spent | no | 3000 | 2142 |

## 6.4 CROWD

| id | speaker | text | when | urg | pat | ms |
|---|---|---|---|---|---|---|
| `chant_rooster` | CROWD | Roo ster! Roo ster! | hit twice unanswered | no | 2000 | 2253 |
| `chant_sardine` | CROWD | Sar dine! Sar dine! | hit twice unanswered | no | 2000 | 2520 |
| `chant_anvil` | CROWD | An vil! An vil! | hit twice unanswered | no | 2000 | 1956 |
| `chant_silk` | CROWD | Silk! Silk! | hit twice unanswered | no | 2000 | 1938 |
| `chant_metronome` | CROWD | Voss! Voss! | hit twice unanswered | no | 2000 | 1843 |

The Metronome's chant is his **surname**: a crowd chants two syllables, never "MET-RO-NOME".

## 6.5 THE MAN IN THE RING

| id | speaker | text | when | urg | pat | ms |
|---|---|---|---|---|---|---|
| `crow_rooster` | BOXER | Rise and shine! | the SUNRISE tell | yes | — | 835 |
| `crow_cut_rooster` | BOXER | Rise. | the false crow | yes | — | 509 |
| `hit_rooster` | BOXER | Wake up. | after he lands a big one | no | 1500 | 569 |
| `taunt_rooster` | BOXER | Cluck, cluck. | the stall taunt, and desperate | no | 1500 | 873 |
| `guard_rooster` | BOXER | That all you got? | your punch on his closed guard | no | 1500 | 861 |
| `crow_sardine` | BOXER | Here it comes! | the SUNRISE tell | yes | — | 869 |
| `crow_cut_sardine` | BOXER | Here it. | the false crow | yes | — | 436 |
| `hit_sardine` | BOXER | Told you. | after he lands a big one | no | 1500 | 626 |
| `taunt_sardine` | BOXER | Come on! | the stall taunt | no | 1500 | 640 |
| `guard_sardine` | BOXER | Nope. Nope. | your punch on his closed guard | no | 1500 | 1029 |
| `crow_anvil` | BOXER | Timber. | the SUNRISE tell | yes | — | 826 |
| `crow_cut_anvil` | BOXER | Tim. | the false crow | yes | — | 580 |
| `hit_anvil` | BOXER | Sit down. | after he lands a big one | no | 1500 | 1250 |
| `taunt_anvil` | BOXER | Any time. | the stall taunt — the wait is the bait | no | 1500 | 1210 |
| `guard_anvil` | BOXER | My turn. | fires exactly as his COUNTER lands | no | 1500 | 1180 |
| `crow_silk` | BOXER | Hup. | the SUNRISE tell — a breath, not a word | yes | — | 440 |
| `crow_cut_silk` | BOXER | Hup. | the false crow — the same file trimmed to 0.22 s | yes | — | 220 |
| `hit_silk` | BOXER | Mm. | after he lands a big one | no | 1500 | 647 |
| `guard_silk` | BOXER | No. | your punch on his closed guard | no | 1500 | 507 |
| `crow_metronome` | BOXER | Now. | the SUNRISE tell | yes | — | 970 |
| `crow_cut_metronome` | BOXER | No. | the false crow | yes | — | 635 |
| `hit_metronome` | BOXER | On the beat. | after he lands a big one | no | 1500 | 1259 |
| `taunt_metronome` | BOXER | Tick. | the stall taunt | no | 1500 | 900 |
| `guard_metronome` | BOXER | Late. | your punch on his closed guard | no | 1500 | 922 |

## 6.6 THE RISE — the career (2026-09-10)

> *"the game plot should be the player is a new boxer from washington dc going up in rank to try
> to defeat the champion, the next boxer should automatically come next. make it an inspirational
> dramatic rise to the top rocky story."*

**THE PLAYER IS SOMEBODY NOW.** He was `YOU`, a nameplate on the right of the scoreboard. He is
**KID COLUMBIA, out of Washington, D.C.** — unranked, unknown, and five fights from the belt. The
card is a LADDER: the Rooster is ranked four, the Sardine three, the Anvil two, Silk one, and Max
Voss holds the title. Beat a man and you take his ranking, the announcer says the number out loud,
and the next man is already walking to the ring — there is no coin between fights any more.

**WHY THE CORNER CARRIES THE STORY AND THE ANNOUNCER CARRIES THE STAKES.** The announcer is the
institution: he says the number, the city and the title, because those are facts about the record.
The trainer is the only person in the building who is on the player's side, so he gets everything
that is about the player rather than about the fight. That division is also what keeps the writing
honest — nobody in a boxing hall makes a speech, so nobody here does either.

Every line is one breath. The longest is nine words.

| id | speaker | text | when | urg | pat |
|---|---|---|---|---|---|
| `intro_you` | ANNOUNCER | And in this corner. Out of Washington, D.C. Kid Columbia! | ceremony 0, every bout | sayAll | 8000 |
| `title_shot` | ANNOUNCER | And now. For the championship of the world! | ceremony 0, the last bout only | sayAll | 8000 |
| `rank_4` / `_3` / `_2` / `_1` | ANNOUNCER | Ranked. Number four in the world. / Number three in the world. / Number two in the world. / The number one contender! | the rise card, after his rank is taken | no | 4000 |
| `corner_1` | CORNER | Everybody starts here. He struts. Take the strut off him. | the bout card, fight 1 | no | 4000 |
| `corner_2` | CORNER | He throws in bunches. Let him empty out. Then answer. | the bout card, fight 2 | no | 4000 |
| `corner_3` | CORNER | Don't swing at Duke Odell. Make him swing at you. | the bout card, fight 3 | no | 4000 |
| `corner_4` | CORNER | Nothing shows on this one. Watch his feet, not his face. | the bout card, fight 4 | no | 4000 |
| `corner_5` | CORNER | Twelve years he's kept the time. Take the beat off him. | the bout card, fight 5 | no | 4000 |
| `climb_1` | CORNER | One down. Nobody knows your name yet. Keep going. | the rise card, after fight 1 | no | 5000 |
| `climb_2` | CORNER | They're saying it now. Say it back with your hands. | the rise card, after fight 2 | no | 5000 |
| `climb_3` | CORNER | You just walked through a wall. Don't stop to look. | the rise card, after fight 3 | no | 5000 |
| `climb_4` | CORNER | One more, kid. One more and you're not the kid. | the rise card, after fight 4 | no | 5000 |
| `climb_5` | CORNER | Look at you. Out of the District. Champion of the world. | the ending, after fight 5 | no | 6000 |
| `not_beaten` | CORNER | You're not beaten. You're on the floor. Different things. | your KO, the continue card | no | 5000 |

`intro_you` is the FIRST clip of the ceremony, ahead of the opponent's `intro_<fighter>`, because
that is the order a real card is read and because it is the one line the player hears five times:
by the Metronome it should already sound like his name.

`title_shot` replaces nothing — it is inserted before `intro_metronome` and only there, so the last
bout announces itself as different before a single punch is thrown.

---

Silk has **no taunt**, deliberately: he is the man who shows nothing, and `FighterVoice.pick`
returns `""` for a stem with no clip, which every call site already tolerates.

`crow_cut_silk` and `crow_silk` share their text on purpose — the cut crow **is** the crow, trimmed.
The parser will flag that as a duplicate only if the texts differ, so keep them identical and let
`render_voices.py`'s `CUT_TRIM = {"crow_cut_silk": 0.22}` do the truncation.

---

## 7. THE PARSER'S CONTRACT — read this before editing §6

`tools/extract_lines.py` is a **line-based** scanner. It does not know about code fences, HTML
comments or nesting. Any line anywhere in this file that begins with `|`, whose first cell is a
backticked id and whose second cell is a known speaker, **is a voice line.** Examples in this
document are therefore indented by two spaces, which breaks the leading `^\|` and makes them inert.

### 7.1 What must change in the tool first

1. `STORY = docs/STORY.md` → **`docs/VOICE.md`**. `STORY.md` does not exist here.
2. `SPEAKERS = {"SYSTEM", "PILOT", "ANNOUNCER", "BUILD", "STRAY", "USER", "CROWD"}` →
   **`{"ANNOUNCER", "REFEREE", "CORNER", "BOXER", "CROWD"}`**.
   **This is the dangerous one.** A row whose speaker is not in the set is skipped *silently* —
   `if sp is None: continue`, no error — so a `--check` run against today's set reports 25 lines and
   **passes**, having thrown away every REFEREE, CORNER and BOXER line in the script. Widen the set
   in the same commit as this file. And keep the set free of prefix relations (no `REF` beside
   `REFEREE`): the lookup is `next(s for s in SPEAKERS if cell.startswith(s))` over a **set**, whose
   iteration order is arbitrary, so two speakers where one prefixes the other would resolve
   nondeterministically.
3. `RECYCLE_FROM` is repointed at `~/Projects/x3discs/app/src/main/assets` and used for nothing
   (§7.4). Keep the mechanism; it costs nothing and the next game will want it.

The regex itself needs **no change**: it already accepts these speaker names and ids as long as
`crow_cut_metronome`.

### 7.2 The row grammar, exactly

    ROW = ^\|\s*(♻)?\s*((?:`[a-z0-9_]+`\s*/?\s*)+)\|\s*([A-Z ()/+,–-]+?)\s*\|\s*(.*?)\s*\|

- **cell 1 — the id(s).** Backticked, `[a-z0-9_]+` only: lowercase, digits, underscore. No hyphens,
  no capitals, no dots. Several ids may share a row, separated by `/`.
- **cell 2 — the speaker.** Uppercase letters, spaces, `(`, `)`, `/`, `+`, `,`, `–`, `-` **only**.
  One lowercase character anywhere in the cell and the whole row silently fails to match.
- **cell 3 — the text.** Anything up to the next `|`. **It must not contain a pipe**, which is the
  one thing that would truncate a line mid-sentence without any error. (Note that `Hud.kt` uses `|`
  as its caption line-break; the spoken text and the drawn caption are different strings and must
  stay so.)
- **cells 4+** are ignored. `when`, `urg`, `pat` and `ms` are for humans.
- A header row (`| id | speaker | …`) and a separator (`|---|---|`) both fail the backtick
  requirement and are skipped. Nothing needs to be done about them.

### 7.3 The three rules that are easy to break

**Multi-id rows and the `_n` shorthand.** For `` `ref_1` / `_2` / `_3` `` the stem is the first id
with trailing digits stripped (`ref_`), and every id starting with `_` is expanded against it. The
text is split on **`" / "` — space, slash, space** — and the split is used **only if the number of
parts equals the number of ids**; otherwise every id silently gets the whole text. So the count row's
text must be exactly ten `" / "`-separated parts, and a text that legitimately contains " / " must
never sit in a multi-id row.

**Duplicate ids.** Two rows with the same id and *different* text is a hard error, because two lines
sharing a filename means one of them is silently unreachable. The same id with the *same* text is
deduplicated and kept once — which is what makes a cross-reference row safe.

**Cross-references.** A row whose text is exactly a section pointer in the form `(4.2)` — digits and
dots inside parentheses, and nothing else — is treated as a pointer and skipped. It must be bare
digits: `(§4.2)` is not recognised and would be rendered as spoken text.

### 7.4 The recycle mark

A leading `♻` before the id means the clip already exists in a sibling project and is **copied, not
re-rendered** — the suite's continuity, and fewer renders. The syntax is:

      | ♻ `end_of_line` | ANNOUNCER | End of line. | QUIT | yes | — |

**Nothing in this game is recycled, and that is a decision, not an oversight.** Every speaker here
goes through a chain that does not exist in x3discs — the ANNOUNCER is Ralph through a stadium echo
where x3discs' is Zarvox — so a copied clip would not be the same speaker wearing the same room; it
would be a **sixth voice**, and §1's separation law is the whole reason the roster is affordable.
The one honest candidate is `end_of_line`, and it is the subject of a ruling in §9.

### 7.5 The card cross-check `--check` must gain

This is what makes "a new fighter is a row" a property rather than a hope:

```python
CARD = re.findall(r'id = "([a-z]+)", name =', open(FIGHTERS).read())
PER_FIGHTER = ["intro", "chant", "down", "crow", "crow_cut", "hit", "guard"]   # taunt optional
for fid in CARD:
    for stem in PER_FIGHTER:
        if f"{stem}_{fid}" not in seen:
            errors.append(f"card fighter `{fid}` has no `{stem}_{fid}` row in VOICE.md")
```

---

## 8. THE RENDER SCRIPT

`tools/render_voices.py` is a **byte-identical copy of x3discs' except for one `/tmp` path**, and
`extract_lines.py` is byte-identical. Nobody should read either as "already adapted".

```python
SPEAKER_DIR = {"ANNOUNCER":"voice","REFEREE":"voice","CORNER":"voice","CROWD":"voice",
               "BOXER":"voice_hero"}
SPEAKER_EXT = {"voice":"m4a", "voice_hero":"mp3"}
ANNOUNCER_VOICE, ANNOUNCER_RATE = "Ralph", 165
REFEREE_VOICE,   REFEREE_RATE   = "Fred",  186
CORNER_VOICE,    CORNER_RATE    = "Reed",  190
FIGHTER = {                    # id: (voice, rate, semitones, extra ffmpeg prefix)
  "rooster":  ("Junior", 190,  1.0, ""),
  "sardine":  ("Rocko",  215,  2.0, ""),
  "anvil":    ("Grandpa",175, -3.0, "equalizer=f=130:t=q:w=1.0:g=4,"),
  "silk":     ("Daniel", 150,  0.0, "volume=0.75,"),
  "metronome":("Reed",   150, -1.0, ""),
}
FISH_FIGHTER = os.environ.get("X3_FISH_FIGHTER", "metronome")   # "" disables fish entirely
CUT_TRIM = {"crow_cut_silk": 0.22}
```

New functions: `fighter_of(lid)` (**longest-suffix** match against `FIGHTER`, so
`crow_cut_metronome` resolves before any shorter id could); `render_boxer(lid, text)` dispatching on
it, appending `atrim=0:{t},afade=t=out:st={t-0.04}:d=0.04` for ids in `CUT_TRIM`, and calling
fish.audio when the fighter is `FISH_FIGHTER` and `tools/fish.config` is readable — **on any failure
falling back to the macOS voice and printing `fish→macOS <id>: <reason>`** rather than leaving a
hole; and `render_referee_count(n, word)` for the composite in §3.

Keep `--only`, `--force`, `--dry-run`, `--list`, `--prune`, `dur_ms` and the per-directory
`manifest.json` writer exactly as they are — that last one feeds `Voice.durations`, and every timing
decision in this document depends on it.

### 8.1 `--check-lengths`, a hard gate

| family | cap | worst measured |
|---|---|---|
| crow_* — the telegraph | **1000 ms** | 970 (crow_metronome) |
| ref_N — one per real second | **950 ms** | 600 |
| tip_* — the 2.2 s corner | **2400 ms** | 2382 |
| any voice/ clip | **4500 ms** | 3896 |
| a fighter's ceremony sum | **9000 ms** | 8184 (metronome) |

Every timing decision here rests on a measured duration. The gate is what stops a re-worded line
from silently breaking the telegraph, the count or the ceremony.

Render cost: ≈ 150 free macOS `say` invocations (87 lines, 30 chant renders, 36 count beds), under
two minutes wall clock, plus five fish.audio calls if the champion goes that way.

---

## 9. HAZARDS FOUND WHILE MEASURING, AND THE RULINGS OWED

**An urgent announcer line eats a following non-urgent referee line.** `hisKnockdown()` fires
`down_<id>` **urgent** (1706–1869 ms measured) and `beginCount()` runs on the next statement. A
`ref_neutral` queued behind it at the obvious patience of 1200 ms is **silently dropped every
time** — `Voice.pump()` discards a line whose patience expired while it waited. Patience must exceed
the longest `down_*`: **2200 ms**, and by the same arithmetic `ref_stop` 2500 and `no_decision` 1500.

**The 2.2 s corner is fiction the moment a tip speaks.** `Clock.CORNER_T = 2.2f`, but `endRound()`
sets `cornerHold = max(CORNER_T, voiceDurationMs(tip)/1000 + 1.05)`. With the current texts
(`tip_still` measured 4300 ms at Reed 170) the corner stretched to **5.35 s**; even with the
tightened texts above the longest tip gives 3.43 s. Two consequences and two fixes:

- The rest is a **different length depending on which tip you earned**, so the player never learns
  the corner's rhythm. Use a constant `CORNER_HOLD_TIP_T = 3.4f` whenever a tip speaks, not a
  `max()`.
- Worse: `update()` gates on `clock.forced != Forced.CORNER && stateT >= cornerHold`
  (`Fight.kt:1422`), so for the last ~1.2 s of a stretched corner **the world clock is unforced and
  answers the player's motion again** — the rest is not a rest. Pass the same number:
  `clock.forceCorner(cornerHold)`.

**The chant text and the chant clip disagree.** `chantT = 2f` (`Fight.kt:1813`) while
`chant_sardine` measures 2520 ms: the plate's `SAR-DINE` vanishes half a second before the crowd
stops shouting it. Drive it off the manifest —
`chantT = max(2f, host.voiceDurationMs(voices.chant) / 1000f)`.

**`Lines.OH` is dead code.** DESIGN.md §9.4 lists it, but `Fight.onStrike` and `updateCount` both
fire the synthesised `Sfx.CROWD_OH`. Deleting it removes a clip and stops the crowd competing with
the announcer for the one-speaker bus.

**Four rulings owed to the owner**, before rendering:

1. **Is the corner a woman?** Three men and a crowd in one arena is the hardest separation problem
   in §1, and the cheapest complete solution is to make the one voice *inside your head* the only
   female one — Karen (AU) or Moira (IE) at 185 through the unchanged `CORNER_CHAIN`. It costs
   nothing, adds a character, and makes the teaching voice unmistakable. It changes DESIGN.md §10's
   "corner man" wording. **Default if he declines: Reed, as tabled.**
2. **Who gets the fish.audio voice?** Recommended: the **Metronome**. TEST.md D4 currently points
   that choice at the Rooster; re-point it, and the test then decides something real either way.
3. **Does `end_of_line` keep the suite's Tron sign-off?** The id must stay (`MainActivity:461` reads
   its duration), but "End of line." means nothing in a boxing cabinet. Tabled as "That's the card.
   Good night."; if he keeps the old line, mark the row `♻` and copy it.
4. **Does the break force anything?** Recommended: **no**. `ref_break` at the round-end bell is pure
   theatre and always correct; a break that also moved the player would be pressure applied for a
   behaviour the game does not have.

---

## 10. INVENTORY

| directory | speakers | clips |
|---|---|---|
| `app/src/main/assets/voice/` (m4a) | ANNOUNCER 20 · REFEREE 19 · CORNER 16 · CROWD 5 | **60** |
| `app/src/main/assets/voice_hero/` (mp3) | the man in the ring | **24** |
| | | **84 + 2 manifests** |

Neither directory exists yet, which is why `Voice.load()` logs a handled `FileNotFound` at boot.
`Voice.kt` itself is complete and correct as inherited and needs exactly one addition:

```kotlin
/** Does the manifest name this clip? The resolution in [FighterVoice] must not guess. */
fun has(id: String) = durations.containsKey(id)
```

surfaced on `GameHost` as `hasVoice(id)` / `hasHero(id)`, and returning `false` in the JVM test host
so `TwoClocksTest`'s fight stays silent.

## The base ids the engine asks for

`Terms.kt` names these directly, so they must exist whatever else the script carries. Where a
fighter has his own variant of one (`chant_anvil`, `hit_silk`, …) the game prefers it at runtime and
falls back to the base — see `MainActivity.say`.

| id | speaker | text | when |
|---|---|---|---|
| `intro_1` | ANNOUNCER | Ladies and gentlemen. | the bout card |
| `intro_2` | ANNOUNCER | Three rounds of boxing. | + clip |
| `intro_3` | ANNOUNCER | Seconds out. | + clip |
| `fight` | REFEREE | Box! | the bell |
| `knockdown` | ANNOUNCER | Down! | he hits the canvas |
| `oh` | CROWD | Ohh. | a heavy landed punch |
| `chant` | CROWD | Knock him out. | the crowd wants it finished |
| `cluck` | BOXER | Hah. | his throwaway noise |
| `that_all` | BOXER | Is that all? | your punch died on his guard |
| `wake_up` | BOXER | Wake up. | you answered wrong twice |
| `rise_and_shine` | BOXER | Rise and shine! | the uppercut's tell — the voice IS the telegraph |
| `rise_cut` | BOXER | Rise and— | the false one: he cuts it off |
