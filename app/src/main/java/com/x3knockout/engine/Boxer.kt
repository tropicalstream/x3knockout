package com.x3knockout.engine

import com.x3knockout.audio.Sfx
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * ROY "THE ROOSTER" RUDD — the one boxer (BOXER.md), as a state machine on WORLD time.
 *
 * He is a cabinet, not a coin (BOXER.md §7): he plays the pattern table in [PATTERN_R1] /
 * [PATTERN_R2] / [PATTERN_R3] and nothing else. The only randomness is the ORDER OF PHRASES within
 * a round after the scripted opening, a fixed 4-slot rotation seeded once per fight from the
 * frame count at the coin ([newFight]), so a run is learnable and no two runs are identical.
 * Branches key off PLAYER STATE at the moment a phrase ends — what the player can see and
 * control — and are read through [Read].
 *
 * WHAT THIS CLASS OWNS: his HP and its knockdown ladder; his phase (idle → tell → strike →
 * recover, the feints, the stun, the stagger, the hit reactions, the fall, the count's poses, the
 * KO); his guard and everything that opens it (DESIGN.md §4.3); the hang and the fuse of every
 * tell and therefore the FLOOR he hands the clock ([floorNow], DESIGN.md §2.2); the aim point he
 * throws at and the collider test that decides whether he landed (DESIGN.md §3.3: geometric,
 * never a threshold); the strip he is showing ([strip]) and the semantic state of his face and
 * gloves that the renderer turns into tints; the round escalation (BOXER.md §6); the drill.
 *
 * WHAT IT DOES NOT OWN: the player (HP, hearts, the KO meter, the score — `Fight`), the clock
 * (it READS `wdt` and `dt` and WRITES nothing but its floor request), colour (the renderer maps
 * [flashGlove], [pupils], [crest] and the rest onto the tint table), sound and voice (it asks
 * through [Listener.onSfx] / [Listener.onSay]; his CROW is `urgent` because it IS a telegraph,
 * DESIGN.md §9.4 — and the fight plays it from [Listener.onTell], so this class never asks for
 * `rise_and_shine` itself; his chatter — `wake_up`, `cluck`, `that_all` — is never urgent).
 *
 * THE TWO CLOCKS IN HERE (DESIGN.md §2.9): the tell, the strike, the recover, the feint frames,
 * the guard's re-close and open windows, the stagger and its extension, the stun, the idle
 * circling, every wait of the pattern — all advance on `wdt`. The hang and the fuse — they bound
 * the READ, so they must run while the read is still — the stall timers, the flashes, and the
 * count's `knockdown` / `down` / `getup` / `ko` strips advance on `dt` (the fall is a story beat
 * the referee's clock owns, and the count runs the world at rate 0, so a fall on `wdt` would
 * never land). His STRIKE advances on `wdt` whatever the fight decides the world does during it:
 * the first draft forced it to real time (`Clock.forceStrike` from [Listener.onStrikeStart]); the
 * owner's amendment at the top of BOXER.md rules it world time, and that is what ships — the
 * fight forces nothing at the strike and [floorNow] hands the clock the hang-then-fuse floor
 * through it (the hang and the fuse keep burning in [update]). Either way this class adds `wdt`
 * to it and the fight's floor decides how fast that is. The one exception is the R3 stall
 * half-peck, a FEINT that advances on `dt` by BOXER.md §6 ("the one attack that ignores the law")
 * — and a feint cannot hurt anyone. Nothing here adds `dt` to anything that can hurt the player.
 *
 * THE HIT REACTION IS AN OVERLAY, NOT A STATE. A landed punch shows `hit_head` / `hit_body` for
 * [HIT_T] world seconds, but the state under it — the recover he was in, the open window a body
 * blow started — keeps its own clock. If the reaction paused them, every landed punch would
 * reopen him for another quarter second and a rhythm player could juggle him forever; the design
 * caps that with the stagger and nowhere else. So [Phase.HIT] is what the picture shows, [under]
 * is where he really is, and the overlay ends into whatever the underlying state became.
 *
 * WHAT `idle()` MEANS DEPENDS ON WHERE HE IS. The fight calls it from the title (the attract's
 * circling: no pattern, no drill) and from the count when the referee reaches his number (the
 * rise: HP to the floor of BOXER.md §4, two world seconds of pecks). This class tells the two
 * apart by [down]: a boxer on the canvas who is told to idle is rising. Likewise `win()` on a
 * boxer on the canvas is the KO pose, never the bounce — a man on the canvas does not win.
 *
 * This file imports nothing from Android. `TwoClocksTest` / `BoxerTest` drive it on the JVM with a
 * fake [Body] and prove that a frozen `wdt` leaves his tell where it was while his hang still
 * burns.
 */
class Boxer {

    companion object {
        // ---------------------------------------------------------------- where he stands
        /**
         * THE MARK — the ring's own reference, and the player's nominal stance. It is NOT where
         * the man is any more (see [footX] / [footZ]); it is the zero of the polar coordinate
         * system his feet live in, the point the ring is built around, and the point the referee's
         * marks are measured from.
         */
        const val X = 0f
        const val Z = -2.6f
        /** His holding distance from the mark. `(lat, range) = (0, REST_RANGE)` is exactly [X], [Z]. */
        const val REST_RANGE = 2.6f
        // ---------------------------------------------------------------- HIS FEET (DESIGN.md §6.2)
        /**
         * HOW FAR ROUND HE MAY GET, and it is DERIVED rather than tuned: the binding constraint
         * is not the frustum, it is the plate's own right rail at x 612 of 640 (`Hud.RAIL_R_X`),
         * projected through the tangent — `atan((272/320)·tan(38.7°))` = 34.25°, rounded down.
         * A man who walks out from behind the HUD is a bug the player cannot do anything about.
         */
        const val EDGE_RAD = 0.58f
        /** His own silhouette's half-width, subtracted from the edge so his SHOULDER stays inboard. */
        const val HALF_W = 0.30f
        /** The last 25 cm before the edge fades the gait's amplitude instead of clamping its position. */
        const val VIS_SOFT = 0.25f
        /** The net, never the wall: the soft fade above is what he should actually feel. */
        const val LAT_MAX = 0.66f
        /** The two integrators' time constants, world seconds. Lateral is the quicker: feet move. */
        const val LAT_TAU = 0.075f
        const val RANGE_TAU = 0.11f
        const val RANGE_MIN = 2.20f
        const val RANGE_MAX = 3.30f
        /** A recover or a feint puts him back OUT this multiple of his step-in. */
        const val FOOT_OUT_K = 2.0f
        /** A landed punch knocks him back this far, scaled by [Fighter.reactMul]. */
        const val KNOCK_BACK = 0.18f
        /**
         * THE TWO REACHES, as SLACK on [REST_RANGE] — so at the shipped distance both are false
         * and nothing about the fight changes until somebody's feet move. His is short because he
         * steps in to throw; yours is long because you are the one standing still.
         */
        const val HIS_REACH = 0.22f
        const val YOUR_REACH = 0.55f
        /** THE ANVIL cuts the ring: every evasion of yours costs this much of it, and he never gives it back. */
        const val RATCHET_M = 0.055f
        const val RATCHET_MIN = 2.20f
        /** …except to a landed body blow. Standing in is the answer to being walked down. */
        const val RATCHET_BACK = 0.20f
        /** THE SARDINE boils: each chained shot inside a phrase closes another 7 cm, three deep. */
        const val FLURRY_CREEP = 0.07f
        const val FLURRY_CREEP_MAX = 0.21f
        /** THE METRONOME mirrors you: this much of your own displacement, this much of the blend, eased. */
        const val MIRROR_K = 0.80f
        const val MIRROR_MIX = 0.75f
        const val MIRROR_TAU = 0.50f
        /** SILK plants: 6 cm of counter-motion over 0.08 world seconds — a SHAPE cue, not a light one. */
        const val PLANT_SETTLE = 0.06f
        const val PLANT_T = 0.08f
        /** The billboard yaw eases over this in REAL seconds: a frozen man who never turns is cardboard. */
        const val YAW_T = 0.15f
        /** He walks back to his mark over this in REAL seconds — the count runs the world at 0. */
        const val RISE_RESET_T = 0.9f
        const val HEIGHT = 1.9f
        const val GLOVE_R = 0.24f
        /** He re-squares to the player's yaw over this when they look away and back. */
        const val RESQUARE_T = 0.3f
        /** A yaw beyond this is "looked away": the fight dims, never punishes (DESIGN.md §6). */
        const val LOOK_AWAY_RAD = 0.2618f

        // ---------------------------------------------------------------- health (DESIGN.md §4.1, §5.1, §5.6)
        /** His HP in round 1 by difficulty: EASY / NORMAL / HARD. */
        val HP_BY_DIFFICULTY = intArrayOf(100, 120, 140)
        /** At the bell: `max(current, floor)` — he recovers in his corner, never fully (fractions of round 1's HP). */
        const val HP_FLOOR_R2 = 72f / 120f
        const val HP_FLOOR_R3 = 42f / 120f
        /** He goes down crossing these fractions of max: 80 / 40 / 0 of 120. */
        val KD_LADDER = floatArrayOf(2f / 3f, 1f / 3f, 0f)
        /** He rises at 4 (KD1), 7 (KD2), 9 (KD3) — unless the knockdown was a KO (§5.1). */
        val RISE_AT = intArrayOf(4, 7, 9)
        /** On rising: HP to this fraction of max, and only pecks for [RISE_PECKS_ONLY_T] world seconds. */
        const val RISE_HP_FRAC = 0.25f
        const val RISE_PECKS_ONLY_T = 2.0f
        /** Three knockdowns in ONE round stop it (TKO); a fourth in the fight is always a KO. */
        const val TKO_KNOCKDOWNS_ROUND = 3
        const val KO_KNOCKDOWNS_FIGHT = 4
        /** Under this fraction of HP in round 3 every wait is halved and he clucks before each phrase. */
        const val DESPERATE_HP_FRAC = 0.25f

        // ---------------------------------------------------------------- the floor he hands the clock (DESIGN.md §2.2)
        /**
         * THE HANG AND THE FUSE ARE GONE with the time law they existed to bound (2026-09-10).
         *
         * Their whole job was the READ: under the old law a still player froze the world mid-tell
         * and could have stood there forever, so the hang was how long that was allowed to be
         * free and the fuse was what eventually made him commit anyway. Both ran on REAL time
         * against a world that was not moving, and that mismatch WAS the mechanic.
         *
         * With the world always at 1.0 a tell simply plays and ends — the longest on the card is
         * 1.05 s and the shortest hang was 0.5 — so neither timer could ever have been reached.
         * They are not neutralised, they are deleted, and the reflex rail they drove now reads
         * the tell's own fraction, which is the same information and honestly derived.
         */
        const val TELL_RAIL = 1f

        // ---------------------------------------------------------------- escalation (BOXER.md §6, DESIGN.md §5.6)
        /** Tell length multiplier by round (the strip's tell segment plays faster; the strike always at 1×). */
        val TELL_MUL = floatArrayOf(1f, 0.75f, 0.58f)
        /** Strike length change by round, seconds. */
        val STRIKE_DELTA = floatArrayOf(0f, -0.02f, -0.05f)
        /** Recover multiplier by round. */
        val RECOVER_MUL = floatArrayOf(1f, 0.8f, 0.65f)
        /** Tell multiplier by difficulty (EASY / NORMAL / HARD). */
        val TELL_MUL_DIFF = floatArrayOf(1.2f, 1f, 0.8f)
        /**
         * A QUARTER SECOND MORE TO ANSWER, added to every real tell (the owner, 2026-09-10:
         * *"give .25 more seconds for player to dodge after opponent glove lights up"*).
         *
         * The glove lights on the tell's FIRST FRAME (`enter(Phase.TELL)` sets [flashGlove]), so
         * the window between the light and the strike IS [tellDur] — and this is added AFTER
         * every multiplier, deliberately. Folded in before them it would be scaled by the round,
         * the difficulty and the man, and a quarter second asked for would be 0.16 s on HARD in
         * round three against the Sardine. Added last it is a quarter second for everybody,
         * everywhere, which is what was asked for.
         *
         * IT IS IN WORLD SECONDS, like every other window that can hurt you. At full speed — a
         * player who is moving — that is exactly 0.25 more real seconds; standing still it is
         * more, in the same proportion as everything else. Expressing it in real seconds would
         * make it the one hostile timer outside the bubble, which is the defect LAW.md exists to
         * prevent.
         *
         * IT ALSO REACHES THE ANVIL'S COUNTER, whose tell is deliberately the shortest thing in
         * the game (`COUNTER_TELL` 0.30, "so the player still SEES it coming and can still, just
         * about, answer it"). That nearly doubles it, and it is the one place this number changes
         * a man's signature rather than his margin. Left in on purpose: the counter's glove
         * lights up like any other and the ruling did not carve it out.
         *
         * A FEINT gets nothing. It runs on `feintDur` down its own path, and a feint cannot hurt
         * anybody, so there is nothing there to dodge.
         */
        const val TELL_GRACE = 0.25f
        /** Damage on you by difficulty. */
        val DMG_MUL_DIFF = floatArrayOf(0.8f, 1f, 1.2f)
        /** The round feints begin, by difficulty: R3 only / R2 on / R1 on. */
        val FEINTS_FROM_ROUND = intArrayOf(3, 2, 1)
        /** The short tells of the chained attacks: the 1-2's second (R2), the R3 triple, the double wing's second. */
        const val TELL_CHAIN_R2 = 0.2f
        const val TELL_CHAIN_R3 = 0.15f
        const val TELL_DOUBLE_WING = 0.2f
        const val TELL_FEINT_FOLLOW = 0.25f
        /** D's sucker uppercut on a player still upright and centred after the 1-2. */
        const val TELL_SUCKER = 0.5f
        /** THE ANVIL's counter: short enough to be a punishment, long enough to be an answerable one. */
        const val COUNTER_TELL = 0.30f
        /** THE METRONOME's crawl — his clock at a dead stop. Never zero: see [update]. */
        const val TEMPO_FLOOR = 0.10f
        /** R3's Sunrise column tracks `leanX` for the first 40 % of the strike. */
        const val TRACK_FRAC = 0.4f

        // ---------------------------------------------------------------- the openings (DESIGN.md §4.3, BOXER.md §5)
        /** A body blow opens the guard for this long (world), by round; a second body blow inside it → STAGGER. */
        val GUARD_OPEN_BODY = floatArrayOf(0.6f, 0.5f, 0.4f)
        /**
         * THE LOW GUARD — the elbows, and the missing half of DESIGN.md §4.3.
         *
         * He has ONE PAIR OF ARMS and two things to cover with them: the gloves are up at his jaw
         * and the elbows are down at his ribs, and what drives one into place takes the other out
         * of it. The engine modelled only half of that. `punch()` tested `level == Level.HEAD &&
         * !open` and there was no `Level.BODY` branch anywhere in the guard logic, so the torso
         * was a hole that was open in every state in the game: nine right hands to the ribs TKO'd
         * the Rooster from the opening bell without once reading a tell, and the man whose entire
         * lesson is DO NOT THROW could be cheesed by throwing.
         *
         * This is the other half, and it is deliberately the SAME ARRAY as [GUARD_OPEN_BODY] —
         * a test asserts they stay equal. That equality IS the invariant: **his elbows are in for
         * exactly as long as his gloves are out**, both timers written by the same blow on the
         * same frame, so a second body blow can never arrive inside the window the first one
         * opened. The two-punch body stagger becomes the three-punch BODY–HEAD–BODY, which is a
         * combination rather than a mash, and every number in the stagger rule is untouched.
         */
        val TUCK_BODY = floatArrayOf(0.6f, 0.5f, 0.4f)
        /**
         * A rail, not a knob: no man's elbows may come in for less than the fastest possible
         * follow-up punch, or some future fighter row with a small `lowMul` lets a back-to-back
         * body double through the invariant above.
         */
        const val TUCK_MIN = 0.20f
        /**
         * THE HOLD. A body punch REFUSED by the elbows refreshes them to at least this fraction of
         * a full tuck: he saw that, and he is staying there. A correct read never triggers it; a
         * player probing for the last frames of the window finds the door has moved. A fraction
         * rather than an absolute, so probing cannot LENGTHEN the Sardine's short tuck.
         */
        const val TUCK_HOLD_FRAC = 0.50f
        /**
         * THE SET: the picture leads the rule by this much world time, so the forearms go dark
         * before the ribs are actually available and the cue means THROW NOW rather than THROW A
         * MOMENT AGO. It must stay under `Fight.JAB_LAND_T` (0.15) or the cue would be a lie.
         */
        const val TUCK_SET_T = 0.10f
        /**
         * A stagger earned by BODY WORK is half a stagger earned by a READ, in length and in cap
         * alike (R1: 0.45 s / 0.80 s against the read's 0.90 / 1.60). The perfect dodge, the
         * special and the counter-on-the-Sunrise are reads and keep the whole of it.
         */
        const val BODY_STAGGER_FRAC = 0.5f
        /** Briefly open at the end of every recover: the greedy player's window. */
        const val GUARD_OPEN_RECOVER = 0.2f
        /** A GUARD-COUNTER (his body hook blocked) bounces his glove: open this long (+150). */
        const val GUARD_OPEN_COUNTER = 0.6f
        /** A SPECIAL into the closed guard knocks it open this long. */
        const val GUARD_OPEN_SPECIAL = 0.8f
        /** The stagger's length (world) by round, its per-hit extension and its cap. */
        val STAGGER_T = floatArrayOf(0.9f, 0.7f, 0.55f)
        val STAGGER_CAP = floatArrayOf(1.6f, 1.2f, 0.9f)
        const val STAGGER_EXTEND = 0.12f
        /** He straightens with a shake for this long (world), guard up; in R2+ his next attack is the Sunrise. */
        const val STAGGER_SHAKE_T = 0.4f
        /** The stun (DESIGN.md §4.6): a RIGHT over his incoming LEFT PECK; each hit resets it, up to the cap. */
        const val STUN_T = 0.6f
        const val STUN_CAP = 1.6f
        /** Damage in stagger × 2. */
        const val STAGGER_DMG_MUL = 2

        // ---------------------------------------------------------------- the pattern's glue
        /** Neutral pause between phrases, world seconds. */
        const val PHRASE_GAP = 1.0f
        /** After a STAGGER ends: R1 waits 0.6 then D; R2 waits 0.4 then the Sunrise; R3 the Sunrise at once. */
        val STAGGER_THEN_WAIT = floatArrayOf(0.6f, 0.4f, 0f)
        /** `that_all` at most once per this many real seconds. */
        const val THAT_ALL_COOLDOWN = 6f
        /** Stall pressure (DESIGN.md §2.8, BOXER.md §6): still while he is IDLE for this long (real), by round. */
        val STALL_T = floatArrayOf(3f, 3f, 2f)
        /** R2: after a stall his NEXT tell is 20 % shorter. */
        const val STALL_TELL_MUL = 0.8f
        /** R3: after a stall he throws a HALF-PECK at real-time rate — a forced state 0.2 s — and the round clock ticks 2 s. */
        const val STALL_HALF_PECK_T = 0.2f
        const val STALL_CLOCK_TICK = 2f
        /** The drill's period, world seconds, and its rule: no damage either way. */
        const val DRILL_PERIOD = 2.5f
        /** A wrong-side lean he punished costs this, so the branches are legible in the tally. */
        const val WRONG_SIDE_PENALTY = -50
        /** A hit landed a full duck's worth INTO the body hook is the fatal answer. */
        const val DUCK_INTO = 0.6f
        /** A dodge is a GLANCE when the collider is inside the band by less than this (metres). */
        const val GLANCE_DEPTH = 0.12f

        // ---------------------------------------------------------------- the reads and the reactions (internal numbers)
        /** A lean past this is a slip in the branch tables' legend (BOXER.md §2: `leanX ≥ ±0.30`). */
        const val SLIP_READ = 0.30f
        /** A duck past this is a duck (`duckAmt ≥ 0.6`, the same number the collider clears a hook at). */
        const val DUCK_READ = Fight.DUCK_DODGE
        /** `Clock.m` under this is "still" for the stall (MOTION.md: standing still reads 0.00–0.02). */
        const val STALL_MOVING = 0.08f
        /** The hit reaction's strips: 3 frames at 12 fps (BOXER.md §8), the flashes the renderer rides on. */
        const val HIT_T = 0.25f
        const val HEAD_FLASH_T = 0.15f
        const val BODY_FLASH_T = 0.25f
        const val SPARKS_T = 0.4f
        /** The extend halo: the first two strip frames of the strike (DESIGN.md §7.4 beat 4). */
        const val EXTEND_T = 2f / 12f
        /** The fall (`knockdown` 13 frames), the KO's fall (`ko` 12), the getup (8) and its lead before the count he rises at. */
        const val FALL_T = 13f / 12f
        const val KO_FALL_T = 1.0f
        const val GETUP_T = 8f / 12f
        const val GETUP_LEAD = 0.75f
        /** The feints' authored lengths (BOXER.md §8), used when the strip is not on disk yet. */
        val FEINT_T = floatArrayOf(4f / 12f, 5f / 12f, 3f / 12f)
        /** The stagger wobble: ±20° at 3 Hz (BOXER.md §5). */
        const val WOBBLE_RAD = 0.349f
        const val WOBBLE_HZ = 3f
        /** `STUN_WARBLE` is re-fired this often (real) while he staggers: a loop without a loop API. */
        const val WARBLE_PERIOD = 0.6f
        /** `wake_up` after a landed hit this big (a wing, the Sunrise, the duck into the low one). */
        const val WAKE_UP_DMG = 15
    }

    /** What he is doing. The renderer reads it for the picture; the fight reads it for the floor. */
    enum class Phase { IDLE, TELL, STRIKE, RECOVER, FEINT, STUN, STAGGER, HIT, KNOCKDOWN, DOWN, GETUP, KO, TAUNT, WIN }

    /**
     * THE BAND an attack sweeps (DESIGN.md §3.3, BOXER.md §3). [lo]..[hi] is the height band in
     * WORLD metres (the pecks at 1.50–1.80 are head-high on a standing eye of 1.65; a jab at a
     * ducked head goes over); the collider is a capsule from `Body.bottom` to `Body.top`, and a
     * capsule inside the band by less than [GLANCE_DEPTH] is a GLANCE. [hit] and [reach] are
     * half-widths from the aim x placed on the collider at the tell's commit: a head centre inside
     * [hit] is a full hit, inside [reach] a glance, outside is clear. [fromSide] says where a hook
     * sweeps in from (−1 = the player's left, +1 = right, 0 = a straight or a column); [column]
     * marks the uppercut, which rises through the whole band and catches a ducked head as surely
     * as a level one.
     */
    class Band(val lo: Float, val hi: Float, val hit: Float, val reach: Float, val fromSide: Int, val column: Boolean)

    /**
     * THE MOVESET — five attacks, five telegraphs, five answers (BOXER.md §3). Times are ROUND 1
     * at rate 1.0 for a moving player; the hang and the fuse stretch the tell for a still one,
     * [TELL_MUL] / [STRIKE_DELTA] / [RECOVER_MUL] shorten them by round. Damage is on an open
     * target at NORMAL. [word] names the ANSWER in the player's frame, never his hand.
     */
    enum class Attack(
        val label: String,
        val strip: String,
        val tellT: Float,
        val strikeT: Float,
        val recoverT: Float,
        /** Damage on an open target / into the guard / when the player ducked INTO it (−1 = the duck is not the fatal answer). */
        val dmg: Int,
        val dmgGuard: Int,
        val dmgDuckInto: Int,
        /** A hook CRUSHES the guard (half damage, forced down 0.4 s real); the uppercut ignores it. */
        val crushesGuard: Boolean,
        val unblockable: Boolean,
        /** The right wing stuns you 0.3 s: the plate jitters 4 px. */
        val stunsYou: Boolean,
        /** The head hook's guard is a guard-counter when it is the BODY hook: blocked = his glove bounces, he is open. */
        val guardCounters: Boolean,
        val band: Band,
        val cheap: Answer,
        val word: String,
        /** The tell's sound (the stamp for both wings; the Sunrise is crowed, not played). −1 = none. */
        val tellSfx: Int,
        /** What a hit by it costs the KO meter: 8 for a peck, 12 for a wing or the uppercut. */
        val meterHit: Int,
        /** The corner's one sentence if this is what hit you most. */
        val tip: String,
    ) {
        PECK_L("LEFT PECK", "peck_l", 0.50f, 0.25f, 0.50f, 8, 2, -1, false, false, false, false,
            Band(1.50f, 1.80f, 0.24f, 0.36f, 0, false), Answer.SLIP_R, "LEAN >", Sfx.TELL_PECK_L, 8, Lines.TIP_PECK),
        PECK_R("RIGHT PECK", "peck_r", 0.50f, 0.25f, 0.55f, 10, 2, -1, false, false, false, false,
            Band(1.50f, 1.80f, 0.24f, 0.36f, 0, false), Answer.SLIP_L, "< LEAN", Sfx.TELL_PECK_R, 8, Lines.TIP_PECK),
        WING_R("RIGHT WING", "wing_r", 0.67f, 0.33f, 0.83f, 15, 7, -1, true, false, true, false,
            Band(1.55f, 1.85f, 0.74f, 0.86f, -1, false), Answer.DUCK, "DUCK", Sfx.STAMP, 12, Lines.TIP_WING_R),
        WING_L("LEFT WING", "wing_l", 0.67f, 0.33f, 0.60f, 12, 3, 18, false, false, false, true,
            Band(1.00f, 1.35f, 0.74f, 0.86f, 1, false), Answer.GUARD, "BLOCK", Sfx.STAMP, 12, Lines.TIP_WING_L),
        SUNRISE("THE SUNRISE", "sunrise", 0.83f, 0.33f, 1.17f, 25, 25, 25, false, true, false, false,
            Band(1.00f, 1.90f, 0.22f, 0.30f, 0, true), Answer.SLIP_L, "< LEAN >", -1, 12, Lines.TIP_SUNRISE);

        /** The hand that throws it — which glove goes white. */
        val hand: Hand get() = when (this) { PECK_L, WING_L -> Hand.LEFT; else -> Hand.RIGHT }
        val isPeck: Boolean get() = this == PECK_L || this == PECK_R
        val isWing: Boolean get() = this == WING_R || this == WING_L
    }

    /** The lies (BOXER.md §6): the tell of a feint is that the glove never goes white. */
    enum class Feint(val strip: String) { HALF_PECK("feint_peck"), FALSE_SUNRISE("feint_crow"), HALF_STAMP("half_stamp") }

    /** The lab's instrument: he throws only that attack every [DRILL_PERIOD] world seconds, no damage either way. */
    enum class Drill { OFF, PECK_L, PECK_R, WING_R, WING_L, SUNRISE, ALL }

    /** The eye flash: a pupil = a jab and which hand; both = the uppercut. Slits ([slits]) = a hook. */
    enum class Pupils { NONE, LEFT, RIGHT, BOTH }

    /** The crest's hue: VIOLET at rest, GOLD for a hook (DROOP = low), WHITE for the uppercut, AMBER when he waits you out. */
    enum class Crest { VIOLET, GOLD, GOLD_DROOP, WHITE, AMBER }

    /** The mouth variants the strips carry; the renderer hides all but one (BOXER.md §1). */
    enum class Mouth { GRIN, FLAT, O, GRIMACE, CROW }

    /** His reaction to a landed punch, for the renderer's secondary motion and the crowd. */
    enum class HitKind { HEAD, BODY, SPIN, BLOCKED }

    /**
     * What a branch reads off the player at the end of a phrase (BOXER.md §7). Two sources feed
     * it, and the WINDOW they cover is the thing to understand:
     *
     *  - the ANSWERS to his attacks since the phrase began (or since the last feint or branch),
     *    recorded at each contact frame from the [Answer] and [StrikeResult] the collider test
     *    produced — the `STRIKE answer=` telemetry is the evidence;
     *  - the POSTURE since the last contact frame (or feint, or branch): the peaks of the lean and
     *    the duck, whether a step or a guard was seen, and the body right now.
     *
     * Reads about what the player DID TO A PUNCH ([DUCKED], [SLIPPED_INTO_HIT], [BLOCKED_BOTH],
     * [GUARD_COUNTERED], [STEPPED_BOTH], [HIT]) come from the answers; reads about where the player
     * IS ([SLIPPED_L], [SLIPPED_R], [SLIPPED], [CENTRED], [STILL_OR_GUARDING]) come from the
     * posture, which is what a branch after a feint or a wait can see; [STEPPED] and [GUARDED] take
     * either. [DUCKED] counts a duck only on an attack the duck does not answer — ducking the right
     * wing is the lesson learned, ducking the left wing is the fatal answer, and phrase A's "ducked
     * either peck" is a beginner ducking straights — so that "#3 (wait) #4 [ducked #4]" never fires
     * on the player who ducked #3 correctly.
     */
    enum class Read {
        DUCKED, SLIPPED, SLIPPED_L, SLIPPED_R, SLIPPED_INTO_HIT, GUARDED, BLOCKED_BOTH, GUARD_COUNTERED,
        STEPPED, STEPPED_BOTH, STILL_OR_GUARDING, CENTRED, HIT
    }

    /**
     * THE PATTERN'S GRAMMAR (BOXER.md §7). A phrase is a list of steps; a branch reads the
     * player and plays [Branch.then] or names the [Branch.next] phrase. The interpreter is
     * [update]'s job; the tables below are the whole AI.
     *
     * A RUN of consecutive branches is ONE decision: the first whose read is true fires (its
     * [Branch.then] is spliced in after the run, its [Branch.say] spoken, its [Branch.next]
     * queued); if none fires, the first [Branch.otherwise] in the run is spliced instead; the
     * reads are then cleared. B2's four branches are four answers to one feint, not four
     * questions.
     */
    sealed class Step {
        /** Attack [attack]; a non-negative [tellT] overrides the authored tell (the chained short tells). */
        class Hit(val attack: Attack, val tellT: Float = -1f, val tracking: Boolean = false) : Step()
        /** A neutral pause of [seconds] world seconds with the guard up. Halved when desperate (R3). */
        class Wait(val seconds: Float) : Step()
        class Feint(val kind: Boxer.Feint, val hand: Hand = Hand.LEFT) : Step()
        /** He is open for [seconds] world (skipped the punch, lost you, winded) — the guard down, the crest drooped. */
        class Open(val seconds: Float) : Step()
        /** A branch on the player's state as the previous step ended. */
        class Branch(val read: Read, val then: List<Step> = emptyList(), val otherwise: List<Step> = emptyList(), val next: String? = null, val say: String? = null) : Step()
        /** A score the pattern itself awards (`STEP +400` for clearing the double wing). */
        class Award(val word: String, val points: Int) : Step()
    }

    /** [hpBelow]: only when his HP is under this fraction (E is under 60 %). [repeats]: how often a branch may replay it. */
    class Phrase(val name: String, val steps: List<Step>, val hpBelow: Float = 1f, val repeats: Int = 0)

    /** The result of YOUR punch on him — one object, reused (nothing allocates at 60 Hz). */
    class Outcome {
        var result = PunchResult.GUARD
        /** Damage actually dealt (after the stagger's × 2 and the counter's × 2). */
        var dmg = 0
        /** The guard opened on this punch (a body blow through it, a special into it). */
        var opened = false
        /** He staggered on this punch (the second body blow inside the window, the special on an open guard). */
        var staggered = false
        /** He stunned on this punch (the right over his incoming left peck). */
        var stunned = false
        /** He is going down: the fight forces SLOW then COUNT. */
        var knockdown = false
        /** ...and he will not rise (the special in a stagger, the counter on his uppercut, the fourth knockdown). */
        var ko = false
        /** The punch interrupted his tell (any early hit does; only the stun case also stuns). */
        var interrupted = false
        fun clear() { result = PunchResult.GUARD; dmg = 0; opened = false; staggered = false; stunned = false; knockdown = false; ko = false; interrupted = false }
    }

    /**
     * WHAT HE TELLS THE FIGHT. Every callback is on the GL thread inside [update] or [punch];
     * the fight applies damage to the player, scores, forces the clock and talks to the host.
     * [onKnockdown] fires INSIDE [punch] (the fight's `beginCount` runs before `resolvePunch`
     * returns; the returned [Outcome] carries `knockdown` / `ko` for the score and the word), and
     * [onRise] is the FIGHT's to call when the referee reaches his number — this class never
     * calls it, so the `RISE` line is logged once.
     */
    interface Listener {
        fun onPhrase(name: String)
        /** A tell began — the floor snaps to the hang, the arc is drawn, the word pops. [feint] non-null for a lie. */
        fun onTell(attack: Attack, feint: Feint?, tellT: Float)
        /** The glove leaves: the fight decides what the clock does for the strike (see the class note). */
        fun onStrikeStart(attack: Attack, strikeT: Float)
        /** The contact frame's verdict. [dmg] is already scaled by difficulty and halved for a glance; 0 for a dodge or a block. */
        fun onStrike(attack: Attack, answer: Answer, result: StrikeResult, dmg: Int)
        /** He is open: the floor drops to 0.12. */
        fun onRecover(attack: Attack)
        fun onGuard(open: Boolean, by: String)
        /**
         * His ELBOWS moved (see [TUCK_BODY]). Defaulted empty on purpose: this interface has no
         * other defaults and both implementers write all of it, so a bare declaration would break
         * the test build for a line nothing is obliged to act on.
         */
        fun onLowGuard(open: Boolean, by: String) {}
        /** Opened with the extension so far (0 at entry, the seconds added per landed punch after); closed with 0. */
        fun onStagger(open: Boolean, seconds: Float)
        fun onStun(on: Boolean)
        fun onHitReaction(kind: HitKind)
        /** He is down: [n] in the fight, the count he rises at, or [ko]. The fight forces SLOW then COUNT. */
        fun onKnockdown(n: Int, riseAt: Int, ko: Boolean)
        fun onRise()
        /** A wrong-side lean he punished: −50, and the multiplier resets. */
        fun onWrongSide()
        /** The pattern awarded something itself (`STEP +400`). */
        fun onAward(word: String, points: Int)
        /** Stall pressure fired (the boo in R1, the shorter tell in R2, the half-peck in R3). */
        fun onStall(round: Int)
        fun onSay(id: String, urgent: Boolean)
        fun onSfx(id: Int, pitch: Float, vol: Float)
    }

    // ------------------------------------------------------------------ configuration
    var listener: Listener? = null
    /** Where his own lines go (`HIM …`, `TKO`) — the fight logs the DESIGN.md §13 lines from the callbacks. Null in a test. */
    var log: ((String) -> Unit)? = null
    /** 0 = EASY, 1 = NORMAL, 2 = HARD. */
    /**
     * WHICH MAN IS IN THE OTHER CORNER. Everything below that used to be a constant of "the boxer"
     * is now a constant of THIS boxer: his health, his timing, how long he hangs, how wide his
     * openings are, when he starts lying, and his pattern. The Rooster's profile carries exactly
     * the numbers the owner has already played, so setting the card's first fighter changes
     * nothing — which is the property that made it safe to do this to a working fight.
     */
    var fighter: Fighter = Fighter.ROOSTER

    var difficulty = 1
    /** The round, 1..3 — sets the escalation row. */
    var round = 1; private set
    var drill = Drill.OFF
    /** The strips, attached by whoever loaded them (the renderer, or a test with the files). Null = no picture, same fight. */
    val strip = StripPlayer()
    fun attach(set: StripSet?) { strip.set = set; if (set != null && strip.strip == null) strip.play("idle") }

    // ------------------------------------------------------------------ health
    var hpMax = HP_BY_DIFFICULTY[1]; private set
    var hp = hpMax; private set
    val hpFrac: Float get() = if (hpMax <= 0) 0f else hp.toFloat() / hpMax
    /** Knockdowns this fight (the crest loses a spike per) and this round (three = TKO). */
    var knockdownsFight = 0; private set
    var knockdownsRound = 0; private set
    /** The next rung of the ladder he falls at, an index into [KD_LADDER]. */
    var ladderNext = 0; private set

    // ------------------------------------------------------------------ what he is doing
    var phase = Phase.TAUNT; private set
    /** World seconds in this phase (real seconds in KNOCKDOWN / DOWN / GETUP / KO and the stall feint). */
    var phaseT = 0f; private set
    var attack: Attack? = null; private set
    var feint: Feint? = null; private set
    /** True while the Sunrise's column is following `leanX` (R3, the first 40 % of the strike). */
    var tracking = false; private set
    /** The current tell / strike / recover lengths after escalation, world seconds. */
    var tellDur = 0f; private set
    var strikeDur = 0f; private set
    var recoverDur = 0f; private set
    /** 0..1 through the tell (the bead slides along the arc on this). */
    val tellFrac: Float get() = if (phase == Phase.TELL && tellDur > 0f) (phaseT / tellDur).coerceIn(0f, 1f) else 0f
    val strikeFrac: Float get() = if (phase == Phase.STRIKE && strikeDur > 0f) (phaseT / strikeDur).coerceIn(0f, 1f) else 0f
    /** THE HANG and THE FUSE, real seconds left (DESIGN.md §2.2). The REFLEX rail drains on these. */



    /** Was the aim point INSIDE the collider at the first strike frame? PERFECT needs on-then-off. */
    var onLineAtStrike = false; private set

    // ------------------------------------------------------------------ the aim (DESIGN.md §3.3)
    /** The bead: where the glove is going, placed on the collider when the tell committed. World metres. */
    var aimX = 0f; private set
    var aimY = 1.65f; private set
    var aimZ = 0f; private set
    /** True while there is an arc to draw (a tell or a strike in flight). */
    var aimSet = false; private set

    // ------------------------------------------------------------------ the guard and the openings
    var guardOpen = false; private set
    /** World seconds of opening left; re-closes on your own punches. */
    var guardOpenLeft = 0f; private set
    /** What opened it, for the `GUARD open= by=` line and the picture. */
    var guardOpenBy = ""; private set
    /** A body blow inside the open window: the second one is a STAGGER. */
    var bodyBlowsInWindow = 0; private set
    // ---- the low guard (see [TUCK_BODY]): the exact mirror of the three fields above it
    /** World seconds his elbows are in. A body punch inside this is refused the way a head punch is. */
    var tuckLeft = 0f; private set
    /** What put them there, for the `low=` token and the picture. */
    var tuckBy = ""; private set
    /** Head punches still needed to bring his elbows back out. [Fighter.lowBlows] sets it; a SPECIAL clears it. */
    var lowBlowsLeft = 0; private set
    /**
     * What the RENDERER draws, which leads the rule by [TUCK_SET_T] — a deliberate, bounded lie in
     * the player's favour, because a cue that arrives on the same frame as the opening is a cue
     * nobody can act on.
     */
    var lowOpen = true; private set
    /** This stagger's own cap: a work-stagger must not inflate back to a read-stagger's ceiling. */
    var staggerCapNow = 0f; private set

    // ------------------------------------------------------------------ HIS FEET (DESIGN.md §6.2)
    /**
     * WHERE HE IS STANDING, in polar about THE MARK — signed metres to the player's right, and
     * metres from the mark. `(0, REST_RANGE)` is byte-identical to the fight that shipped, which
     * is the property that lets a fighter with a null footwork row be unchanged.
     *
     * POLAR ABOUT THE MARK AND NOT ABOUT THE EYE, deliberately: the player's head swings ±1.10 m
     * on a lean and a step, and a man who orbited the live eye would be dragged along by every
     * dodge — the dodge would move the world instead of moving the player, which is both useless
     * and, on a head-tracked display, unpleasant.
     */
    var lat = 0f; private set
    var range = REST_RANGE; private set
    /** His gait's own phase, advanced on WORLD time and ONLY while he is uncommitted. */
    private var gaitT = 0f
    /** Where the ratchet has walked his holding distance to (the Anvil), and the flurry's creep. */
    private var restRange = REST_RANGE
    private var creep = 0f
    /** The mirror's eased target (the Metronome) and the plant's settle (Silk). */
    private var mirror = 0f
    private var plantLeft = 0f
    /** The last body [update] saw, so `punch()` can measure a separation without a new parameter. */
    private val lastBody = Body()
    /** Frozen where he fell, so the count and the referee's mark agree with the picture. */
    private var downX = 0f
    private var downZ = -REST_RANGE
    private var riseLeft = 0f
    /** THE POSITION EVERYTHING DRAWS AND EVERYTHING MEASURES. */
    val footX: Float get() = lat
    val footZ: Float get() = -sqrt(max(range * range - lat * lat, 0.25f))
    /** His separation from the player's head, which is what both reach tests read. */
    fun separation(body: Body): Float {
        val dx = footX - body.headX; val dz = footZ - body.headZ
        return sqrt(dx * dx + dz * dz)
    }
    var staggerLeft = 0f; private set
    var stunLeft = 0f; private set
    /** After a stagger in R2+, the next attack is always the Sunrise. */
    var suckerArmed = false; private set
    /** THE ANVIL: a punch of yours died on his guard and he is owed one. See [punch] and [update]. */
    var counterArmed = false; private set
    private var counterCount = 0
    /** After rising: only pecks until this world time. */
    var pecksOnlyLeft = 0f; private set
    /** Real seconds since `that_all` — the once-per-6-s rule. */
    var thatAllAgo = 99f; private set
    /** Stall pressure: real seconds the player has been still while he is IDLE, and whether it fired. */
    var stallT = 0f; private set
    var stallFired = false; private set
    var nextTellMul = 1f; private set

    // ------------------------------------------------------------------ the count (the fight runs the numerals)
    /** He is on the canvas: from `down` until the rise or the KO. */
    val down: Boolean get() = phase == Phase.KNOCKDOWN || phase == Phase.DOWN || phase == Phase.GETUP || phase == Phase.KO
    var riseAt = 0; private set

    // ------------------------------------------------------------------ the picture (the renderer maps these to tints)
    /** He faces the camera; this is the billboard's yaw, re-squared over [RESQUARE_T]. */
    var yaw = 0f; private set
    /** The white-hot glove for the whole tell (and the 1.8× extend for two frames of the strike). */
    var flashGlove: Hand? = null; private set
    var extend = false; private set
    var pupils = Pupils.NONE; private set
    var slits = false; private set
    var crest = Crest.VIOLET; private set
    /** 5 spikes at full, one lost per knockdown. */
    val crestSpikes: Int get() = (5 - knockdownsFight).coerceAtLeast(0)
    /** Spike angle = 90° × HP / max, minimum 40°: 0..1 of droop for the renderer. */
    val crestDroop: Float get() = (1f - hpFrac).coerceIn(0f, 1f)
    var mouth = Mouth.GRIN; private set
    var tongue = false; private set
    var spirals = false; private set
    /** The hit reaction's WHITE ring / head flash, real seconds left; the sweat and the squash ride on it. */
    var headFlashT = 0f; private set
    var bodyFlashT = 0f; private set
    /** The stagger wobble ±20° at 3 Hz and the hit squash: the renderer's secondary matrix reads these. */
    var wobble = 0f; private set
    var squash = 0f; private set
    /** The crest sparks after a COUNTER or the SPECIAL, real seconds left of 0.4. */
    var sparksT = 0f; private set
    /** The stagger's warble loops while this is true (not through the straightening shake). */
    val warbling: Boolean get() = phase == Phase.STAGGER && !shaking

    // ------------------------------------------------------------------ the pattern
    /** The phrase in progress, its step index, and the 4-slot rotation the fight seeded. */
    var phraseName = ""; private set
    private var phrase: Phrase? = null
    /** The phrase's steps, copied so a branch can splice its `then` in without touching the table. */
    private val program = ArrayList<Step>(24)
    private var stepIndex = 0
    private var rotation = IntArray(0)
    private var rotationAt = 0
    private var seed = 0
    /** The rotating phrases of the round in the seeded order (the opening and the HP-gated ones excluded). */
    private val order = ArrayList<String>(4)
    private var openingDone = false
    private var phraseRuns = 0
    private var lastPhraseName = ""
    private var pendingNext: String? = null
    /** What the player did to his attacks in the read window (BOXER.md §7; see [Read]). */
    private class Answered(val attack: Attack, val answer: Answer, val result: StrikeResult)
    private val answers = ArrayList<Answered>(8)
    private var peakLeanL = 0f
    private var peakLeanR = 0f
    private var peakDuck = 0f
    private var sawStep = false
    private var sawGuard = false
    private var lastAnswer = Answer.NONE
    private var lastResult = StrikeResult.CLEAN
    private var waitLeft = 0f
    private var drillLeft = DRILL_PERIOD
    private var drillIndex = 0
    /** True from the bell to the rise or the KO: the pattern and the drill run only then (never on the title). */
    private var fighting = false
    private val outcome = Outcome()

    // ------------------------------------------------------------------ the machine's private state
    /** The attack this tell should track (R3's Sunrise) once its strike begins. */
    private var trackingArmed = false
    /** A chained attack thrown after a STEPPED one swings where you were (the double wing's promise, see [contact]). */
    private var chainedAfterStep = false
    /** He waits you out: the hang is burned and you are still — the crest goes amber on a straight's tell. */
    private var stillInFuse = false
    /** The feint in progress: its length, and whether it is the R3 stall's real-time one. */
    private var feintDur = 0f
    private var stallFeint = false
    /** The hit overlay (see the class note): what is under it and how far along that state is. */
    private var under = Phase.IDLE
    private var underT = 0f
    private var hitLeft = 0f
    /** The stagger: its total after extensions, the world time inside it (the wobble's phase), the straightening shake. */
    private var staggerTotal = 0f
    private var staggerT = 0f
    private var shaking = false
    private var shakeLeft = 0f
    private var warbleT = 0f
    /** The attack whose PERFECT dodge opened the current stagger — a COUNTER on a perfect-countered Sunrise is a KO. */
    private var perfectAttack: Attack? = null
    /** The stun's elapsed world time (the 1.6 s cap counts from the first hit) and whether this STUN is a pattern `Open`. */
    private var stunT = 0f
    private var openStep = false
    private var openBy = ""
    /** Real seconds on the canvas since the `down` pose began (the count's clock), and whether this fall is the KO's (`ko` strip, no rise). */
    private var downT = 0f
    private var koFall = false
    /** The strip segment in play, so the overlay can resume it at the right frame. */
    private var segName = ""
    private var segFrom = 0
    private var segFrames = 0
    private var segSeconds = 0f
    private var segRate = 1f
    /** The attack's segment stashed under a hit overlay, so the recover resumes at its own frame. */
    private var keptName = ""
    private var keptFrom = 0
    private var keptFrames = 0
    private var keptSeconds = 0f
    private var keptRate = 1f
    /** The body hook's whistle follows the stamp, once per tell. */
    private var whistled = false

    // ================================================================== LIFECYCLE

    /** A fresh opponent for a new fight: full HP by difficulty, no knockdowns, the rotation seeded. */
    fun newFight(seed: Int, difficulty: Int) {
        this.difficulty = difficulty.coerceIn(0, 2)
        this.seed = seed
        hpMax = fighter.hp[this.difficulty]
        hp = hpMax
        knockdownsFight = 0; knockdownsRound = 0; ladderNext = 0
        rotation = IntArray(0); rotationAt = 0
        round = 0                      // so newRound(1) below reads as a change of round
        newRound(1)
        taunt()
    }

    /**
     * The bell. His HP recovers to the round's floor (`max(current, 72 / 42)` at NORMAL, as a
     * fraction of max), the round's knockdown count resets, the escalation row moves, the scripted
     * opening phrase (A / A2 / A3) is queued first and the rotation re-seeded.
     *
     * THE FLOOR APPLIES ONLY WHEN THE ROUND NUMBER CHANGES. The desk harness calls this twice for
     * one round — once from `debugStart` and again at the bell — with `setHp(--ei hp)` between,
     * and a floor applied on the second call would silently undo the launch's HP. Everything else
     * (the phrase, the rotation, the knockdown count of the round) resets on every call: the bell
     * is the bell.
     */
    fun newRound(round: Int) {
        val r = round.coerceIn(1, 3)
        if (r != this.round) {
            val floorFrac = when (r) { 2 -> HP_FLOOR_R2; 3 -> HP_FLOOR_R3; else -> 0f }
            hp = maxOf(hp, (hpMax * floorFrac).roundToInt())
            reladder()
        }
        this.round = r
        knockdownsRound = 0
        abandon()
        openingDone = false; phraseRuns = 0; lastPhraseName = ""; pendingNext = null
        rotation = rotationFor(r, seed); rotationAt = 0
        order.clear()
        val pool = pattern(r).drop(1).filter { it.hpBelow >= 1f }.map { it.name }
        for (slot in rotation) if (slot < pool.size) order.add(pool[slot])
        for (name in pool) if (name !in order) order.add(name)
        guardOpenLeft = 0f; guardOpenBy = ""; bodyBlowsInWindow = 0
        tuckLeft = 0f; tuckBy = ""; lowBlowsLeft = 0; lowOpen = true; staggerCapNow = 0f
        lat = 0f; range = REST_RANGE; gaitT = 0f; restRange = REST_RANGE; creep = 0f
        mirror = 0f; plantLeft = 0f; riseLeft = 0f; downX = 0f; downZ = -REST_RANGE; yaw = 0f
        staggerLeft = 0f; staggerTotal = 0f; shaking = false; stunLeft = 0f; stunT = 0f; openStep = false
        pecksOnlyLeft = 0f; suckerArmed = false; perfectAttack = null
        stallT = 0f; stallFired = false; nextTellMul = 1f; stillInFuse = false
        drillLeft = DRILL_PERIOD; drillIndex = 0
        hitLeft = 0f; headFlashT = 0f; bodyFlashT = 0f; sparksT = 0f; wobble = 0f; squash = 0f
        koFall = false; downT = 0f; riseAt = 0
        fighting = true
        enter(Phase.IDLE)
    }

    /** Set his HP directly — the `--ei hp` launch and the tests. */
    fun setHp(v: Int) { hp = v.coerceIn(0, hpMax); reladder() }

    /** The intro's pose: he beckons, tongue out. No pattern runs until the bell. */
    fun taunt() { fighting = false; abandon(); enter(Phase.TAUNT) }

    /**
     * The card that ends the fight. Standing, it is his win (`TIME - NO DECISION`: both gloves up,
     * bouncing); on the canvas it is the KO's flat pose — the fight calls this from its knockout,
     * where he is already down, and a man on the canvas does not bounce.
     */
    fun win() { fighting = false; abandon(); enter(if (down) Phase.KO else Phase.WIN) }

    /**
     * The title's circling — OR THE RISE. The fight calls this from the count when the referee
     * reaches [riseAt]: on the canvas it means "get up" (BOXER.md §4: HP to at least 25 % of max,
     * the ladder re-read from that HP, two world seconds of pecks only, a fresh phrase after the
     * gap); anywhere else it means the attract, no pattern, no drill. After a KO (`win()` put him
     * flat) it is the title again, never a rise.
     */
    fun idle() {
        counterArmed = false
        if (down && phase != Phase.KO) { rise(); return }
        fighting = false; abandon(); enter(Phase.IDLE)
    }

    private fun rise() {
        hp = maxOf(hp, (hpMax * RISE_HP_FRAC).roundToInt())
        reladder()
        pecksOnlyLeft = RISE_PECKS_ONLY_T
        abandon()
        koFall = false
        waitLeft = PHRASE_GAP
        enter(Phase.IDLE)
        log?.invoke("HIM rise hp=$hp/$hpMax ladderNext=$ladderNext pecksOnly=%.1f".format(Locale.US, pecksOnlyLeft))
    }

    /** The next rung below the current HP: a boxer who rose at 25 % has crossed every rung above it. */
    private fun reladder() {
        ladderNext = KD_LADDER.indexOfFirst { hp > (hpMax * it).roundToInt() }.let { if (it < 0) KD_LADDER.size else it }
    }

    /** Drop whatever he was doing: the phrase, the attack, the aim, the pending pattern glue. */
    private fun abandon() {
        phrase = null; phraseName = ""; program.clear(); stepIndex = 0
        attack = null; feint = null; aimSet = false; tracking = false; trackingArmed = false; chainedAfterStep = false
        waitLeft = 0f; pendingNext = null; stallFeint = false; openStep = false
        answers.clear(); resetPosture()
    }

    // ================================================================== THE FRAME

    /**
     * One frame of him. [wdt] is the world's seconds, [dt] the player's; [body] is the collider
     * as of this frame, read and never written. The order is the two-clock audit in miniature:
     * the real-time bookkeeping (the hang, the fuse, the stall, the flashes, the re-square)
     * first, the posture window the branches read, the world-time windows, the phase, the guard's
     * transitions, the picture, and the strip last on whichever clock the phase says.
     */
    fun update(wdt: Float, dt: Float, body: Body) {
        var w = if (wdt.isFinite()) wdt.coerceAtLeast(0f) else 0f
        val r = if (dt.isFinite()) dt.coerceAtLeast(0f) else 0f

        // ---------------------------------------------------------------- THE METRONOME'S CLOCK
        // GONE WITH THE LAW IT WAS BUILT ON (2026-09-10). His gimmick WAS the time law turned
        // into an exam: `w = r × (0.10 + 2.2 × body.moving)`, his own seconds bought with the
        // player's motion, so standing still made him the slowest man on the card and moving
        // made him the fastest. With the world at a flat 1.0 that line does not become
        // pointless, it becomes BACKWARDS — it would run the champion at a tenth speed against
        // a player who had stopped, and hand the last fight on the card to anyone who froze.
        //
        // He keeps everything else that makes him the champion: the tightest openings on the
        // card (`openMul` 0.70), the hardest damage, the most HP, three rounds of pattern, and
        // the footwork mirror in [feet] — three quarters of his lateral target is the player's
        // own displacement, which is the same idea as the old gimmick expressed in the one
        // channel that still has room for it. `Gimmick.TEMPO` therefore still means something;
        // it just means it with his feet instead of with his clock.

        // real time: the flashes and the stall belong to the plate
        thatAllAgo += r
        headFlashT = dec(headFlashT, r); bodyFlashT = dec(bodyFlashT, r); sparksT = dec(sparksT, r)
        // THE OWNER'S RULING (BOXER.md's header): the strike travels on world time, so the read
        // includes the glove in flight — the hang and the fuse go on burning through the STRIKE,
        // and the floor they hand the clock is what brings a hanging glove to a still player.
        if (phase == Phase.TELL || phase == Phase.STRIKE) watchStillness(body)
        if (warbling) { warbleT = dec(warbleT, r); if (warbleT <= 0f) { warbleT = WARBLE_PERIOD; listener?.onSfx(Sfx.STUN_WARBLE, 1f, 0.45f) } }
        resquare(body, r)
        stall(body, w, r)

        // the posture window the branches read (BOXER.md §7)
        watchPosture(body)

        // world time: everything that can hurt
        if (guardOpenLeft > 0f) { guardOpenLeft = dec(guardOpenLeft, w); if (guardOpenLeft <= 0f) bodyBlowsInWindow = 0 }
        if (tuckLeft > 0f) { tuckLeft = dec(tuckLeft, w); if (tuckLeft <= 0f) { tuckBy = ""; lowBlowsLeft = 0 } }
        if (pecksOnlyLeft > 0f) pecksOnlyLeft = dec(pecksOnlyLeft, w)
        lastBody.headX = body.headX; lastBody.headY = body.headY; lastBody.headZ = body.headZ
        feet(w, r, body)
        stepPhase(w, r, body)

        syncGuard()
        syncLowGuard()
        paint(w, body)
        strip.update(stripClock(w, r))
    }

    /**
     * THE FLOOR HE HANDS THE CLOCK this frame (DESIGN.md §2.2), or −1 when a forced state owns
     * it (his fall, the count). [deep] is the difficulty's hang floor.
     *
     * THE STRIKE IS NOT FORCED (the owner's ruling, BOXER.md's header: every punch travels on world
     * time, his and yours). The glove in flight advances on `wdt` under the same hang-then-fuse
     * floor as the tell it came out of: a player whose move completed the tell and who then
     * freezes watches the glove crawl and hang a hand's width from the cheek, and the fuse — still
     * burning on real time, see [update] — is what brings it the rest of the way. A moving player
     * at rate 1 sees the strike the artist authored (0.25 / 0.33 s). The first draft returned −1
     * here and the fight forced `Clock.forceStrike`; that made "yours lands first" a bet nobody
     * could win, because his glove never slowed for anything.
     *
     * ONE FLOOR, EVERYWHERE — the owner's ruling (DESIGN.md §0.1: the enemy sets the pace, never
     * the clock), and the fix for "i dont really see the superhotvr motion mechanics".
     *
     * This used to be a table: 0.35 while he was idle, a deep 0.04–0.10 only during the tell's
     * hang, then a 1.2 s fuse ramping back to 0.60, and 0.12 through a recovery. Four numbers, and
     * every one of them above the deep floor was THE CLOCK APPLYING PRESSURE — the world running
     * without the player. The effect on the glass was a fight that never ran slower than about a
     * third speed, which is slow motion with variations and not the bargain the game is built on.
     * A player standing perfectly still could not freeze anything, so the mechanic was invisible.
     *
     * Now there is one number and it is a constant of the GAME, not of the fight, the round or the
     * fighter: stand still and the world very nearly stops, in round one of the Rooster and in
     * round three of the Metronome alike. A glove leaves his shoulder and HANGS by your cheek for
     * as long as you can hold your nerve.
     *
     * WHAT ANSWERS A STATUE is now entirely his and entirely visible — the crowd's boos, his crest
     * going amber, his next tell shortening, the forced feint the rail openly declares, and a round
     * clock that runs on WORLD time so standing still does not run it out either. Every one of
     * those is something the player can watch arrive. A floor raised behind their back is not.
     *
     * A KNOCKDOWN still returns −1 (the fight owns the clock through the count).
     */
    fun floorNow(deep: Float): Float = when (phase) {
        Phase.KNOCKDOWN, Phase.DOWN, Phase.GETUP, Phase.KO -> -1f
        else -> deep
    }

    // ------------------------------------------------------------------ the real-time bookkeeping

    /**
     * HE IS WAITING YOU OUT. All that survives of the hang and the fuse: the crest goes amber on
     * a peck's tell while the player stands there doing nothing, which is a character note and
     * costs nobody any time.
     */
    private fun watchStillness(body: Body) { stillInFuse = body.moving < STALL_MOVING }

    /**
     * The billboard's heading toward the player's inferred position, eased over [RESQUARE_T] on
     * real time (it is HIS motion, but it must not freeze with the world: a frozen boxer who does
     * not turn with a leaning player reads as a cardboard cut-out). The look-away dim itself is
     * the renderer's — it has the head yaw; this class only has where the body went.
     */
    /**
     * HIS FEET, and the tactical style built on them — out-fighting, "stick and move" (DESIGN.md
     * §6.2). Called from the WORLD half of [update], so a still player sees a still man, feet
     * included: at the floor his gait crawls at 3 % and a circling opponent is as frozen as a
     * thrown glove. There is no randomness in here at all — the gait is a pure function of
     * [gaitT], the phase, and where the player is standing.
     *
     * THE PHASE GATE IS THE WHOLE OF "STICK AND MOVE", and it costs no new state because the
     * state machine already has every phase it needs. **He never travels laterally while there
     * is something to read**: the moment a tell begins the sideways component freezes and the
     * only motion left is the step IN, driven by the tell's own fraction so the lead foot lands
     * with the glove. Through the strike he is held exactly where the tell left him — a target
     * that slides while you are reading it is not a target, it is a lottery — and it is the
     * RECOVER, when he is already open, that walks him back out.
     *
     * THE ARC LIMIT IS DERIVED. [EDGE_RAD] comes off the plate's own right rail, not off taste,
     * and his silhouette's half-angle is subtracted from it so his SHOULDER stays inboard rather
     * than his centre. The last [VIS_SOFT] fades the gait's AMPLITUDE instead of clamping its
     * position, because a hard clamp against a player who has stepped and leaned bites every
     * frame and reads as a ball bouncing off a wall.
     */
    private fun feet(w: Float, r: Float, body: Body) {
        // the count and the fall: he is where he fell, and he walks home on REAL time (the count
        // runs the world at rate 0, so a world-time reset would never finish)
        if (down) { riseLeft = RISE_RESET_T; return }
        if (riseLeft > 0f) {
            riseLeft = max(0f, riseLeft - r)
            val k = 1f - exp(-r / (RISE_RESET_T * 0.35f))
            lat += (0f - lat) * k
            range += (restRange - range) * k
            return
        }
        val f = fighter
        val u = if (phase == Phase.HIT) under else phase
        val committed = u == Phase.TELL || u == Phase.STRIKE || u == Phase.HIT ||
            u == Phase.STAGGER || u == Phase.STUN
        if (!committed) gaitT += w

        // ---- the lateral target: the gait, then the man's own gimmick, then the edge
        var latWant = lat
        if (!committed && f.footArc > 0f) {
            val u2 = 2f * PI.toFloat() * f.footHz * gaitT
            val sn = sin(u2)
            val shaped = (if (sn < 0f) -1f else 1f) * abs(sn).pow(f.footWave)
            latWant = f.footArc * shaped
        }
        if (f.gimmick == Fighter.Gimmick.TEMPO && !committed) {
            // HE KEEPS YOUR TIME, and your position: three quarters of his target is your own
            // displacement, so the ring you thought you were taking closes behind you.
            mirror += (MIRROR_K * body.headX - mirror) * (1f - exp(-w / MIRROR_TAU))
            latWant = MIRROR_MIX * mirror + (1f - MIRROR_MIX) * latWant
        }
        // the derived arc limit, in the EYE's frame, faded rather than clamped
        val zf = -footZ
        val edge = EDGE_RAD - asin((HALF_W / max(range, 1.5f)).coerceIn(-1f, 1f))
        val room = tan(edge) * zf - abs(latWant - body.headX)
        if (room < VIS_SOFT) latWant *= (room / VIS_SOFT).coerceIn(0f, 1f)

        // ---- the range target: the phase gate
        var rangeWant = restRange
        var driven = false
        when (u) {
            Phase.TELL -> {
                val frac = if (tellDur > 0f) (phaseT / tellDur).coerceIn(0f, 1f) else 1f
                val sm = frac * frac * (3f - 2f * frac)
                rangeWant = restRange - f.footClose * sm - creep
                range = rangeWant.coerceIn(RANGE_MIN, RANGE_MAX)   // DRIVEN: the foot lands with the glove
                driven = true
            }
            Phase.STRIKE -> { driven = true }                       // held exactly where the tell left him
            Phase.RECOVER, Phase.FEINT -> rangeWant = restRange + f.footClose * FOOT_OUT_K
            Phase.HIT, Phase.STAGGER, Phase.STUN -> rangeWant = restRange + KNOCK_BACK * f.reactMul
            else -> rangeWant = restRange
        }
        if (plantLeft > 0f) { plantLeft = max(0f, plantLeft - w); rangeWant += PLANT_SETTLE }

        // ---- the integrators
        if (!committed) lat += (latWant - lat) * (1f - exp(-w / LAT_TAU))
        lat = lat.coerceIn(-LAT_MAX, LAT_MAX)
        if (!driven) range += (rangeWant - range) * (1f - exp(-w / RANGE_TAU))
        range = range.coerceIn(RANGE_MIN, RANGE_MAX)
    }

    /** THE ANVIL cuts the ring: your evasion costs him nothing and costs you 5.5 cm of it. */
    private fun ratchet() {
        if (fighter.gimmick != Fighter.Gimmick.COUNTER) return
        restRange = max(RATCHET_MIN, restRange - RATCHET_M)
    }

    /** …and a body blow is how you buy it back. Standing in is the answer to being walked down. */
    private fun ratchetBack() {
        if (fighter.gimmick != Fighter.Gimmick.COUNTER) return
        restRange = min(REST_RANGE, restRange + RATCHET_BACK)
    }

    /**
     * The billboard heading, eased on REAL time and aimed at his LIVE position. This used to be
     * computed from the two constants and read by nothing at all — the renderer took an
     * instantaneous atan2 instead, which snaps the moment the man himself is translating.
     */
    private fun resquare(body: Body, r: Float) {
        val target = atan2(-(body.headX - footX), -(body.headZ - footZ))
        yaw += (target - yaw) * (1f - exp(-r / YAW_T))
    }

    /**
     * STALL PRESSURE (BOXER.md §6, DESIGN.md §2.8): still while he is IDLE for [STALL_T] real
     * seconds. `Clock.still` cannot carry this — it counts under rate 0.10 and his idle floor is
     * 0.35 — so it is read off `Body.moving` directly. A frozen world (`wdt` = 0: the referee's
     * count, a hit-stop) does not accumulate: the referee is not inside the fight. R1: the fight
     * boos (nothing from him). R2: the crest goes amber and the next tell is 20 % shorter. R3: a
     * half-peck at real-time rate — the feint that ignores the law — and the clock tick is the
     * fight's to add from [Listener.onStall] (this class does not own the clock).
     */
    private fun stall(body: Body, w: Float, r: Float) {
        if (body.moving >= STALL_MOVING) { stallT = 0f; stallFired = false; return }
        if (phase != Phase.IDLE || !fighting || drill != Drill.OFF || w <= 0f) return
        stallT += r
        if (stallT < STALL_T[round - 1]) return
        stallT = 0f; stallFired = true
        listener?.onStall(round)
        log?.invoke("HIM stall round=$round")
        when (round) {
            2 -> { nextTellMul = STALL_TELL_MUL; crest = Crest.AMBER; listener?.onSay(Lines.CLUCK, false) }
            3 -> { listener?.onSay(Lines.CLUCK, false); beginFeint(Feint.HALF_PECK, Hand.LEFT, STALL_HALF_PECK_T, realTime = true) }
        }
    }

    private fun watchPosture(body: Body) {
        if (body.leanX < peakLeanL) peakLeanL = body.leanX
        if (body.leanX > peakLeanR) peakLeanR = body.leanX
        if (body.duckAmt > peakDuck) peakDuck = body.duckAmt
        if (body.stepping) sawStep = true
        if (body.guardUp) sawGuard = true
    }

    private fun resetPosture() { peakLeanL = 0f; peakLeanR = 0f; peakDuck = 0f; sawStep = false; sawGuard = false }

    // ------------------------------------------------------------------ the phase

    private fun stepPhase(w: Float, r: Float, body: Body) {
        when (phase) {
            Phase.IDLE -> {
                phaseT += w
                if (fighting) { if (drill != Drill.OFF) runDrill(w, body) else runPattern(w, body) }
            }
            Phase.TAUNT, Phase.WIN -> phaseT += w
            Phase.TELL -> {
                phaseT += w
                if (!whistled && attack == Attack.WING_L && phaseT >= tellDur * 0.3f) { whistled = true; listener?.onSfx(Sfx.WHISTLE, 1f, 0.5f) }
                if (phaseT >= tellDur) beginStrike(body)
            }
            Phase.STRIKE -> {
                phaseT += w
                if (tracking) { if (phaseT < TRACK_FRAC * strikeDur) aimX = body.headX else tracking = false }
                extend = phaseT < EXTEND_T
                if (phaseT >= strikeDur) contact(body)
            }
            Phase.RECOVER -> {
                phaseT += w
                if (phaseT >= recoverDur) endRecover()
            }
            Phase.FEINT -> {
                phaseT += if (stallFeint) r else w
                if (phaseT >= feintDur) endFeint()
            }
            Phase.HIT -> {
                phaseT += w; underT += w
                hitLeft = dec(hitLeft, w)
                if (hitLeft <= 0f) endHit()
            }
            Phase.STAGGER -> {
                phaseT += w
                if (!shaking) {
                    staggerT += w
                    staggerLeft = dec(staggerLeft, w)
                    if (staggerLeft <= 0f) beginShake()
                } else {
                    shakeLeft = dec(shakeLeft, w)
                    if (shakeLeft <= 0f) endStagger()
                }
            }
            Phase.STUN -> {
                phaseT += w; stunT += w
                stunLeft = dec(stunLeft, w)
                if (stunLeft <= 0f) endStun()
            }
            Phase.KNOCKDOWN -> {
                phaseT += r; downT += r
                if (phaseT >= (if (koFall) KO_FALL_T else FALL_T)) enter(if (koFall) Phase.KO else Phase.DOWN)
            }
            Phase.DOWN -> {
                phaseT += r; downT += r
                // dramatised: he pushes up so that the `up` frame lands just before the count he rises at.
                // [downT] restarts as DOWN begins: the fight runs the fall under SLOW (1.1 s real, the
                // strip's own length) and starts the referee's count where the `down` pose begins.
                if (riseAt > 0 && downT >= riseAt - GETUP_LEAD) enter(Phase.GETUP)
            }
            Phase.GETUP -> { phaseT += r; downT += r }      // holds standing until the fight's idle()
            Phase.KO -> phaseT += r
        }
    }

    /** Which clock the strip advances on this frame (see the class note). */
    private fun stripClock(w: Float, r: Float): Float = when (phase) {
        Phase.KNOCKDOWN, Phase.DOWN, Phase.GETUP, Phase.KO -> r
        Phase.FEINT -> if (stallFeint) r else w
        else -> w
    }

    private fun dec(v: Float, by: Float): Float = if (v > 0f) (v - by).coerceAtLeast(0f) else v

    // ================================================================== THE INTERPRETER (BOXER.md §7)

    /**
     * One IDLE frame of the pattern: burn the wait, start a phrase if none is running, then step
     * through the program until a step that takes time (a tell, a wait, a feint, an opening).
     * Branches and awards cost nothing and are consumed in place. The loop is bounded so a
     * table that is all branches can never spin a frame.
     */
    /** Silk plants after a landed shot: a shape cue, not a light cue, which is the honest kind. */
    private fun plant() { if (fighter.footClose >= 0.30f) plantLeft = PLANT_T }

    private fun runPattern(w: Float, body: Body) {
        // ---------------------------------------------------------------- THE ANVIL'S ANSWER
        // It goes FIRST, ahead of the wait and the phrase, because a counter that queues politely
        // behind the pattern is not a counter. The first attempt armed a timer and waited for an
        // IDLE frame; the pattern reached its next tell 0.4 s later and the punish never came —
        // logged on the glasses, which is the only reason it was caught, because the code read
        // perfectly well.
        //
        // It also CANCELS THE WAIT. His long waits are the bait — the whole shape of his pattern is
        // "throw, then stand there invitingly" — so leaving the wait running would mean the greedy
        // punch is answered a second and a half later, by which time the player has stopped
        // connecting the two. The answer has to arrive while the arm is still out.
        if (counterArmed && phase == Phase.IDLE) {
            counterArmed = false
            waitLeft = 0f
            // WHICH hand alternates rather than rolls: this class has no random number generator on
            // purpose (he is a cabinet, not a coin), and a counter whose shape can be learned is a
            // counter that can eventually be beaten — the difference between hard and unfair.
            counterCount++
            listener?.onSay(Lines.THAT_ALL, false)
            beginTell(if (counterCount % 2 == 0) Attack.PECK_R else Attack.WING_R,
                COUNTER_TELL, track = false, chained = true, body = body)
            return
        }
        if (waitLeft > 0f) { waitLeft = dec(waitLeft, w); if (waitLeft > 0f) return }
        if (phrase == null) { startPhrase(); if (phrase == null) return }
        var fuel = 64
        while (fuel-- > 0) {
            if (stepIndex >= program.size) { endPhrase(); return }
            when (val s = program[stepIndex]) {
                is Step.Hit -> { stepIndex++; beginTell(s.attack, s.tellT, s.tracking, chained = false, body); return }
                is Step.Wait -> { stepIndex++; waitLeft = s.seconds * waitMul(); if (waitLeft > 0f) return }
                is Step.Feint -> { stepIndex++; if (feintsOn()) { beginFeint(s.kind, s.hand, feintLength(s.kind), realTime = false); return } }
                is Step.Open -> { stepIndex++; beginOpen(s.seconds * waitMul(), "OPEN"); return }
                is Step.Branch -> evaluateBranches(body)
                is Step.Award -> { stepIndex++; listener?.onAward(s.word, s.points) }
            }
        }
    }

    /**
     * The next phrase: the branch's `next` if one was queued, else the opening once, else an
     * HP-gated phrase when it is eligible and was not the last one played (E "inserted after any
     * phrase when eligible"), else the rotation. Under 25 % HP in round 3 he clucks first.
     */
    private fun startPhrase() {
        val table = pattern(round)
        val name = pendingNext.also { pendingNext = null } ?: nextRotationName(table)
        val ph = table.firstOrNull { it.name == name } ?: synthetic(name) ?: return
        phraseRuns = if (ph.name == lastPhraseName) phraseRuns + 1 else 1
        lastPhraseName = ph.name
        phrase = ph; phraseName = ph.name
        program.clear(); program.addAll(ph.steps); stepIndex = 0
        answers.clear(); resetPosture()
        if (desperate()) listener?.onSay(Lines.CLUCK, false)
        listener?.onPhrase(ph.name)
    }

    private fun nextRotationName(table: List<Phrase>): String {
        if (!openingDone) { openingDone = true; return table[0].name }
        table.firstOrNull { it.hpBelow < 1f && hpFrac < it.hpBelow && it.name != lastPhraseName }?.let { return it.name }
        if (order.isEmpty()) return table[0].name
        return order[rotationAt++ % order.size]
    }

    /** The phrases the tables name but do not list: the post-stagger Sunrise of rounds 2 and 3 (BOXER.md §6). */
    private fun synthetic(name: String): Phrase? = when (name) {
        "SUCKER" -> Phrase("SUCKER", listOf(Step.Hit(Attack.SUNRISE, tracking = round >= 3)))
        else -> null
    }

    private fun endPhrase() {
        phrase = null; phraseName = ""
        program.clear(); stepIndex = 0
        waitLeft = PHRASE_GAP * waitMul()
    }

    /**
     * A run of consecutive branches is one decision (see [Step]). `next` naming the phrase itself
     * is a REPEAT and is honoured at most [Phrase.repeats] times in a row; the `say` is spoken
     * whether or not the repeat is allowed ("wake_up and repeat C (max twice)" still says it the
     * third time). The reads are cleared afterwards: the next decision sees only what follows.
     */
    private fun evaluateBranches(body: Body) {
        var i = stepIndex
        var fired: Step.Branch? = null
        var fallback: Step.Branch? = null
        while (i < program.size) {
            val b = program[i] as? Step.Branch ?: break
            if (fired == null && has(b.read, body)) fired = b
            if (fallback == null && b.otherwise.isNotEmpty()) fallback = b
            i++
        }
        stepIndex = i
        val splice = fired?.then ?: fallback?.otherwise ?: emptyList()
        fired?.say?.let { listener?.onSay(it, false) }
        fired?.next?.let { nx ->
            val ph = phrase
            if (ph == null || nx != ph.name || phraseRuns <= ph.repeats) pendingNext = nx
        }
        if (splice.isNotEmpty()) program.addAll(stepIndex, splice)
        log?.invoke("HIM branch phrase=$phraseName read=${fired?.read?.name ?: "-"} then=${splice.size} next=${pendingNext ?: "-"}")
        answers.clear(); resetPosture()
    }

    /** The reads, over the answer window and the posture window (see [Read]). */
    private fun has(r: Read, body: Body): Boolean {
        val slipL = peakLeanL <= -SLIP_READ || body.leanX <= -SLIP_READ
        val slipR = peakLeanR >= SLIP_READ || body.leanX >= SLIP_READ
        val ducked = peakDuck >= DUCK_READ || body.duckAmt >= DUCK_READ
        val stepped = sawStep || body.stepping
        val guarded = sawGuard || body.guardUp
        val n = answers.size
        return when (r) {
            Read.DUCKED -> if (n > 0) answers.any { it.answer == Answer.DUCK && it.attack.cheap != Answer.DUCK } else ducked
            Read.SLIPPED_L -> slipL
            Read.SLIPPED_R -> slipR
            Read.SLIPPED -> slipL || slipR
            Read.SLIPPED_INTO_HIT -> answers.any { (it.answer == Answer.SLIP_L || it.answer == Answer.SLIP_R) && (it.result == StrikeResult.HIT || it.result == StrikeResult.GLANCE) }
            Read.GUARDED -> if (n > 0) answers.any { it.answer == Answer.GUARD } else guarded
            Read.BLOCKED_BOTH -> n >= 2 && answers[n - 1].result == StrikeResult.BLOCK && answers[n - 2].result == StrikeResult.BLOCK
            Read.GUARD_COUNTERED -> answers.any { it.attack.guardCounters && it.result == StrikeResult.BLOCK }
            Read.STEPPED -> stepped || answers.any { it.answer == Answer.STEP }
            Read.STEPPED_BOTH -> n >= 2 && answers[n - 1].answer == Answer.STEP && answers[n - 2].answer == Answer.STEP
            Read.STILL_OR_GUARDING -> guarded || !(slipL || slipR || ducked || stepped)
            Read.CENTRED -> !slipL && !slipR && !ducked && !stepped && abs(body.leanX) < SLIP_READ
            Read.HIT -> answers.any { it.result == StrikeResult.HIT || it.result == StrikeResult.GLANCE || it.result == StrikeResult.CRUSH }
        }
    }

    private fun desperate(): Boolean = round >= 3 && hpFrac < DESPERATE_HP_FRAC
    private fun waitMul(): Float = if (desperate()) 0.5f else 1f
    private fun feintsOn(): Boolean =
        round >= minOf(FEINTS_FROM_ROUND[difficulty.coerceIn(0, 2)], fighter.feintsFromRound)

    /** The feint's length: the strip's authored count when it is on disk, BOXER.md §8's number until then. */
    private fun feintLength(kind: Feint): Float {
        val st = strip.set
        val s = st?.strip(kind.strip)
        return if (st != null && s != null) s.count * st.frameT else FEINT_T[kind.ordinal]
    }

    /** THE DRILL: one attack every [DRILL_PERIOD] world seconds of idling, ALL cycling the five, no pattern. */
    private fun runDrill(w: Float, body: Body) {
        drillLeft = dec(drillLeft, w)
        if (drillLeft > 0f) return
        val atk = when (drill) {
            Drill.PECK_L -> Attack.PECK_L
            Drill.PECK_R -> Attack.PECK_R
            Drill.WING_R -> Attack.WING_R
            Drill.WING_L -> Attack.WING_L
            Drill.SUNRISE -> Attack.SUNRISE
            Drill.ALL -> Attack.entries[drillIndex++ % Attack.entries.size]
            Drill.OFF -> return
        }
        log?.invoke("HIM drill attack=${atk.name} round=$round")
        beginTell(atk, -1f, atk == Attack.SUNRISE && round >= 3, chained = false, body)
    }

    // ================================================================== TELL → STRIKE → RECOVER

    /**
     * A tell begins. The lengths are the authored ones through the escalation row and the
     * difficulty ([TELL_MUL] × [TELL_MUL_DIFF], the stall's [STALL_TELL_MUL] once); a chained
     * step's [tellT] override is that round's own number and takes only the difficulty. The hang
     * and the fuse are armed on real time; the aim is placed on the collider NOW (DESIGN.md
     * §3.3); after a rise only pecks are thrown for two world seconds, so a wing or the Sunrise
     * becomes the same hand's peck rather than a hole in the phrase.
     */
    private fun beginTell(atk0: Attack, override: Float, track: Boolean, chained: Boolean, body: Body) {
        var atk = atk0
        if (pecksOnlyLeft > 0f && !atk.isPeck) atk = if (atk.hand == Hand.LEFT) Attack.PECK_L else Attack.PECK_R
        attack = atk; feint = null; stallFeint = false
        trackingArmed = track && atk == Attack.SUNRISE; tracking = false
        chainedAfterStep = chained && lastAnswer == Answer.STEP
        val d = difficulty.coerceIn(0, 2)
        var base = if (override >= 0f) override else atk.tellT * TELL_MUL[round - 1]
        // THE FLURRY takes its bite out of the CHAINED shots only — the ones the phrase authored a
        // short tell for. Shortening his opening shot too would just make him a fast boxer; taking
        // it out of the follow-ups is what makes the PHRASE the punch rather than the shot.
        if (fighter.gimmick == Fighter.Gimmick.FLURRY && override >= 0f) {
            base *= fighter.gimmickK
            // HE BOILS: each chained shot closes another 7 cm, three deep, and gives it all back
            // when the phrase ends. A four-shot R3 chain finishes in your face.
            creep = min(FLURRY_CREEP_MAX, creep + FLURRY_CREEP)
        }
        tellDur = (base * fighter.tellMul * TELL_MUL_DIFF[d] * nextTellMul).coerceAtLeast(0.05f) + TELL_GRACE
        nextTellMul = 1f
        strikeDur = ((atk.strikeT + STRIKE_DELTA[round - 1]) * fighter.strikeMul).coerceAtLeast(0.1f)
        recoverDur = atk.recoverT * RECOVER_MUL[round - 1] * fighter.recoverMul
        stillInFuse = false; whistled = false
        onLineAtStrike = false
        if (atk == Attack.SUNRISE) suckerArmed = false
        placeAim(body, atk)
        enter(Phase.TELL)
        listener?.onTell(atk, null, tellDur)
    }

    /** The bead: on the collider, inside the band. A jab at a ducked head aims OVER it — the picture says so. */
    private fun placeAim(body: Body, atk: Attack) {
        val band = atk.band
        aimX = body.headX
        aimY = if (atk == Attack.WING_L) (band.lo + band.hi) * 0.5f else body.headY.coerceIn(band.lo, band.hi)
        aimZ = body.headZ
        aimSet = true
    }

    private fun beginStrike(body: Body) {
        val atk = attack ?: return
        onLineAtStrike = onLine(body, atk)
        tracking = trackingArmed
        enter(Phase.STRIKE)
        extend = true
        listener?.onStrikeStart(atk, strikeDur)
    }

    /** Is the aim point inside the capsule? For the column (the uppercut) only x matters: it rises through every height. */
    private fun onLine(body: Body, atk: Attack): Boolean {
        val dx = abs(body.headX - aimX)
        if (dx >= Fight.BODY_HW) return false
        return atk.band.column || (aimY >= body.bottom && aimY <= body.top)
    }

    /**
     * THE CONTACT FRAME — the collider test, geometric, never a threshold (DESIGN.md §3.3). What
     * the player DID is read off the body as a label (a step in flight, a duck past 0.6, a lean
     * past ±0.30, else nothing); whether it WORKED is the capsule against the band: off the line
     * is CLEAN, or PERFECT if the aim was inside the capsule when the strike began; a guard that
     * the geometry would have hit BLOCKS (a hook CRUSHES it, the uppercut goes under it); inside
     * the band by less than [GLANCE_DEPTH] or inside the reach but outside the hit width is a
     * GLANCE; the rest is a HIT, and a duck INTO the body hook is its fatal damage.
     *
     * A STEP is a 0.35 s flight and the double wing spans 0.86 s from the first strike to the
     * second contact, so "one step clears both" (BOXER.md §7 B3) cannot be geometry: a chained
     * attack thrown after a STEPPED one is thrown where you were and is CLEAN by rule.
     */
    private fun contact(body: Body) {
        val atk = attack ?: return
        // HE THREW FROM TOO FAR OUT. The one geometric term in a collider that is otherwise
        // authored entirely in the player's frame: the band table below decides WHICH WAY you had
        // to move, and that must never depend on where he is standing — only WHETHER he could
        // reach you at all does. `HIS_REACH` is slack on the rest range, so at the distance the
        // fight shipped at this branch is false and nothing changes.
        if (separation(body) > REST_RANGE + HIS_REACH) {
            listener?.onStrike(atk, Answer.NONE, StrikeResult.SHORT, 0)
            beginRecover()
            return
        }
        val band = atk.band
        val posture = when {
            body.stepping || chainedAfterStep -> Answer.STEP
            body.duckAmt >= DUCK_READ -> Answer.DUCK
            body.leanX <= -SLIP_READ -> Answer.SLIP_L
            body.leanX >= SLIP_READ -> Answer.SLIP_R
            else -> Answer.NONE
        }
        val dx = abs(body.headX - aimX)
        val overlap = min(body.top, band.hi) - max(body.bottom, band.lo)
        val vClear = !band.column && overlap <= 0f
        val vGlance = !band.column && overlap < GLANCE_DEPTH
        val hClear = dx >= band.reach
        val hGlance = dx >= band.hit
        var answer = posture
        // THE ANVIL CUTS THE RING on every evasion, read straight off the posture the collider
        // already computed. He never gives it back except to a body blow (see [ratchetBack]).
        if (posture != Answer.NONE) ratchet()
        val result = when {
            posture == Answer.STEP -> StrikeResult.CLEAN
            vClear || hClear -> if (onLineAtStrike) StrikeResult.PERFECT else StrikeResult.CLEAN
            body.guardUp -> {
                answer = Answer.GUARD
                when {
                    atk.unblockable -> if (vGlance || hGlance) StrikeResult.GLANCE else StrikeResult.HIT
                    atk.crushesGuard -> StrikeResult.CRUSH
                    else -> StrikeResult.BLOCK
                }
            }
            vGlance || hGlance -> StrikeResult.GLANCE
            else -> StrikeResult.HIT
        }
        val base = when (result) {
            StrikeResult.HIT -> if (answer == Answer.DUCK && atk.dmgDuckInto >= 0) atk.dmgDuckInto else atk.dmg
            StrikeResult.GLANCE -> atk.dmg
            StrikeResult.BLOCK, StrikeResult.CRUSH -> atk.dmgGuard
            else -> 0
        }
        var dmg = (base * DMG_MUL_DIFF[difficulty.coerceIn(0, 2)] * fighter.dmgMul).roundToInt()
        if (result == StrikeResult.GLANCE) dmg = (dmg * 0.5f).roundToInt()
        if (drill != Drill.OFF) dmg = 0

        answers.add(Answered(atk, answer, result))
        lastAnswer = answer; lastResult = result
        resetPosture()
        tracking = false; extend = false; chainedAfterStep = false
        listener?.onStrike(atk, answer, result, dmg)
        // a peck that found a leaning head was thrown INTO the lean: the branch punished a wrong-side slip (−50)
        if (atk.isPeck && (result == StrikeResult.HIT || result == StrikeResult.GLANCE) && (answer == Answer.SLIP_L || answer == Answer.SLIP_R)) listener?.onWrongSide()
        if (result == StrikeResult.HIT && dmg >= WAKE_UP_DMG) listener?.onSay(Lines.WAKE_UP, false)

        if (result == StrikeResult.PERFECT && !atk.isPeck) { perfectAttack = atk; beginStagger("PERFECT"); return }
        if (result == StrikeResult.BLOCK && atk.guardCounters) openGuard(GUARD_OPEN_COUNTER, "COUNTER", blows = 1)
        // back to back: a chained step skips the recover and re-aims at once
        val nx = program.getOrNull(stepIndex)
        if (phrase != null && nx is Step.Hit && nx.tellT >= 0f && nx.tellT < nx.attack.tellT) {
            stepIndex++
            beginTell(nx.attack, nx.tellT, nx.tracking, chained = true, body)
            return
        }
        beginRecover()
    }

    private fun beginRecover() {
        val atk = attack ?: return
        enter(Phase.RECOVER)
        listener?.onRecover(atk)
    }

    /** The recover is over: the guard stays open [GUARD_OPEN_RECOVER] more — the greedy player's window — then closes. */
    private fun endRecover() {
        if (guardOpenLeft < GUARD_OPEN_RECOVER) openGuard(GUARD_OPEN_RECOVER, "RECOVER", blows = bodyBlowsInWindow)
        attack = null
        drillLeft = DRILL_PERIOD
        enter(Phase.IDLE)
    }

    // ------------------------------------------------------------------ the feints

    /**
     * A lie (BOXER.md §6): the strip of the feint plays, the fight hears it as a tell of the
     * attack it imitates (so the cluck, the stamp or the cut crow sound), and the read window
     * restarts at its start — what the player does NEXT is what the branches after it read.
     * The R3 stall's half-peck runs on real time ([realTime]); every other feint on world time.
     */
    private fun beginFeint(kind: Feint, hand: Hand, seconds: Float, realTime: Boolean) {
        feint = kind
        attack = when (kind) {
            Feint.HALF_PECK -> if (hand == Hand.LEFT) Attack.PECK_L else Attack.PECK_R
            Feint.FALSE_SUNRISE -> Attack.SUNRISE
            Feint.HALF_STAMP -> Attack.WING_R
        }
        feintDur = seconds.coerceAtLeast(0.05f)
        stallFeint = realTime
        aimSet = false
        answers.clear(); resetPosture()
        enter(Phase.FEINT)
        listener?.onTell(attack!!, kind, feintDur)
    }

    private fun endFeint() {
        attack = null; feint = null; stallFeint = false
        enter(Phase.IDLE)
    }

    // ------------------------------------------------------------------ the openings: stun, stagger, the pattern's Open

    /** The pattern says he is open (he skipped the punch, lost you, is winded): hands low, no wake rule, a fixed window. */
    private fun beginOpen(seconds: Float, by: String) {
        openStep = true; openBy = by
        stunLeft = seconds.coerceAtLeast(0.05f); stunT = 0f
        attack = null; aimSet = false
        enter(Phase.STUN)
    }

    /** The counter-hit (DESIGN.md §4.6): a RIGHT over the incoming LEFT PECK before its strike frame. */
    private fun beginStun() {
        dropAttack()
        openStep = false
        stunLeft = STUN_T; stunT = 0f
        enter(Phase.STUN)
        listener?.onStun(true)
        log?.invoke("HIM stun on")
    }

    /** Every hit inside the stun resets its timer, up to [STUN_CAP] from the first hit. */
    private fun restun() {
        stunLeft = min(STUN_T, (STUN_CAP - stunT).coerceAtLeast(0f))
    }

    private fun endStun() {
        val wasStun = !openStep
        openStep = false
        drillLeft = DRILL_PERIOD
        enter(Phase.IDLE)
        if (wasStun) { listener?.onStun(false); log?.invoke("HIM stun off") }
    }

    /** A body blow wakes a stunned boxer: his hands come up (the head/body axis is a decision inside a stun). */
    private fun wake() {
        openStep = false
        drillLeft = DRILL_PERIOD
        enter(Phase.IDLE)
        listener?.onStun(false)
        log?.invoke("HIM wake")
    }

    /**
     * THE STAGGER (BOXER.md §5): entered by a PERFECT dodge of a wing or the Sunrise, two body
     * blows inside one open window, the special on an open guard, a body blow inside a
     * guard-counter's window. The phrase is abandoned — the post-stagger rule decides what comes
     * next when he straightens ([endStagger]).
     */
    private fun beginStagger(by: String, frac: Float = 1f) {
        val r = round - 1
        phrase = null; phraseName = ""; program.clear(); stepIndex = 0; waitLeft = 0f
        attack = null; feint = null; aimSet = false; tracking = false; stallFeint = false; openStep = false
        guardOpenLeft = 0f; bodyBlowsInWindow = 0; stunLeft = 0f
        tuckLeft = 0f; tuckBy = ""; lowBlowsLeft = 0
        // A STAGGER EARNED BY WORK IS HALF A STAGGER EARNED BY A READ ([BODY_STAGGER_FRAC]), and
        // the CAP has to be halved with it: `extendStagger` used to read STAGGER_CAP directly, so
        // a 0.45 s work-stagger inflated straight back to the 1.60 s read ceiling on the first
        // follow-up punch and the fraction bought nothing at all.
        staggerTotal = STAGGER_T[r] * frac; staggerLeft = staggerTotal; staggerT = 0f
        staggerCapNow = STAGGER_CAP[r] * frac
        shaking = false; shakeLeft = 0f; warbleT = 0f
        enter(Phase.STAGGER)
        guardOpenBy = by
        listener?.onStagger(true, 0f)
        log?.invoke("HIM stagger by=$by total=%.2f".format(Locale.US, staggerTotal))
    }

    /** Each landed punch extends the stagger by [STAGGER_EXTEND] up to the round's cap: "about five taps, seven if you're clean". */
    private fun extendStagger() {
        val cap = if (staggerCapNow > 0f) staggerCapNow else STAGGER_CAP[round - 1]
        val total = min(cap, staggerTotal + STAGGER_EXTEND)
        val add = total - staggerTotal
        staggerTotal = total; staggerLeft += add
        listener?.onStagger(true, add)
    }

    private fun beginShake() {
        shaking = true; shakeLeft = STAGGER_SHAKE_T
        spirals = false; tongue = false
        playLoop("guard")
    }

    /**
     * He straightens: guard up, and the round's rule — R1 waits 0.6 then phrase D; R2 waits 0.4
     * then the Sunrise, always; R3 the tracking Sunrise at once — on the player who kept punching
     * after the wobble stopped.
     */
    private fun endStagger() {
        shaking = false; wobble = 0f; perfectAttack = null
        enter(Phase.IDLE)
        listener?.onStagger(false, 0f)
        drillLeft = DRILL_PERIOD
        if (drill == Drill.OFF && fighting) {
            waitLeft = STAGGER_THEN_WAIT[round - 1] * waitMul()
            pendingNext = if (round == 1) "D" else "SUCKER"
            suckerArmed = round >= 2
        }
    }

    // ------------------------------------------------------------------ the hit overlay and the fall

    /** The tell (or the feint) is interrupted by an early hit: the attack is lost, the phrase goes on. */
    private fun dropAttack() {
        val a = attack
        drillLeft = DRILL_PERIOD
        attack = null; feint = null; aimSet = false; tracking = false; trackingArmed = false; stallFeint = false; extend = false
        if (a != null) log?.invoke("HIM interrupt attack=${a.name}")
    }

    /** The reaction strip over whatever he is doing (see the class note). A strike is not negotiable and shows only the flash. */
    private fun hitOverlay(level: Level, interrupts: Boolean) {
        val u = if (phase == Phase.HIT) under else phase
        if (u == Phase.STRIKE || u == Phase.STUN || u == Phase.STAGGER) return   // the flash carries it; the state is not cut short
        if (interrupts) { dropAttack(); under = Phase.IDLE; underT = 0f }
        else if (phase != Phase.HIT) {
            under = u; underT = phaseT
            keptName = segName; keptFrom = segFrom; keptFrames = segFrames; keptSeconds = segSeconds; keptRate = segRate
        }
        hitLeft = HIT_T
        phase = Phase.HIT; phaseT = 0f
        val name = if (level == Level.BODY) "hit_body" else "hit_head"
        val st = strip.set; val s = st?.strip(name)
        if (st != null && s != null) playSegment(name, 0, s.count, HIT_T) else { segName = name; segFrom = 0; segFrames = 3; segSeconds = HIT_T; segRate = 1f }
    }

    /** The overlay ends into whatever the underlying state became while it played. */
    private fun endHit() {
        segName = keptName; segFrom = keptFrom; segFrames = keptFrames; segSeconds = keptSeconds; segRate = keptRate
        when (under) {
            Phase.RECOVER -> {
                if (attack != null && underT < recoverDur) { phase = Phase.RECOVER; phaseT = underT; resumeAttackSegment() }
                else { phase = Phase.RECOVER; phaseT = underT; endRecover() }
            }
            else -> { phase = Phase.IDLE; phaseT = underT; playLoop("idle") }
        }
        under = Phase.IDLE; underT = 0f
    }

    /**
     * HE GOES DOWN (DESIGN.md §5.1). [ko]: the special in a stagger, the counter on his
     * perfect-countered Sunrise. A fourth knockdown in the fight is always a KO; three in one
     * round stop it (TKO — logged, treated as a KO: he does not rise). [Listener.onKnockdown]
     * fires from inside the punch that dropped him.
     */
    /** Where he went down, so the count, the referee's mark and the picture all agree. */
    fun downSpot(out: FloatArray) { out[0] = downX; out[1] = downZ }

    private fun knockdown(ko: Boolean, why: String) {
        knockdownsFight++; knockdownsRound++
        val n = knockdownsFight
        val tko = knockdownsRound >= TKO_KNOCKDOWNS_ROUND
        val isKo = ko || n >= KO_KNOCKDOWNS_FIGHT || tko
        riseAt = if (isKo) 0 else RISE_AT[(n - 1).coerceIn(0, RISE_AT.size - 1)]
        koFall = isKo
        outcome.knockdown = true; outcome.ko = isKo; outcome.result = PunchResult.KNOCKDOWN
        abandon()
        guardOpenLeft = 0f; bodyBlowsInWindow = 0; stunLeft = 0f; staggerLeft = 0f; shaking = false; hitLeft = 0f
        tuckLeft = 0f; tuckBy = ""; lowBlowsLeft = 0
        downX = footX; downZ = footZ                  // he falls where he stood, and stays there
        downT = 0f
        enter(Phase.KNOCKDOWN)
        if (tko && !ko) log?.invoke("TKO round=$round knockdowns=$knockdownsRound")
        log?.invoke("HIM down n=$n why=$why riseAt=$riseAt ko=$isKo hp=$hp")
        listener?.onKnockdown(n, riseAt, isKo)
    }

    /** The knockdown ladder (80 / 40 / 0 of 120): one rung per punch at most. */
    private fun ladder() {
        if (ladderNext >= KD_LADDER.size) return
        if (hp <= (hpMax * KD_LADDER[ladderNext]).roundToInt()) { ladderNext++; knockdown(false, "LADDER") }
    }

    // ------------------------------------------------------------------ the guard

    /**
     * IS THIS LEVEL AVAILABLE RIGHT NOW? The one predicate, and the whole of the guard model.
     *
     * The head's answer is character-for-character what it always was — the closed set is still
     * exactly {IDLE with no window} and {the stagger's straightening SHAKE} — so nothing about
     * the head's behaviour changes anywhere in the game. What is new is that the body has an
     * answer at all.
     *
     * TELL, STRIKE and FEINT are open at BOTH levels because his hands are busy: that is the law
     * of one pair of arms, and it is why interrupting a tell is still the cleanest thing in the
     * game. RECOVER and STUN are open at both because `syncGuard` already says his guard is DOWN
     * there, which is a different fact from being busy.
     */
    private fun openAt(level: Level, u: Phase): Boolean = when (u) {
        Phase.STAGGER -> !shaking
        Phase.STUN, Phase.RECOVER -> true
        Phase.TELL, Phase.STRIKE, Phase.FEINT -> true
        Phase.IDLE -> if (level == Level.HEAD) guardOpenLeft > 0f else tuckLeft <= 0f
        else -> false
    }

    /**
     * THE ELBOWS COME IN. The one place the low guard is written, exactly as [openGuard] is the
     * one place the high one is — so [Fighter.lowMul] is the whole of "his elbows are better" and
     * no call site can forget to apply it, and neither can `openMul`.
     */
    private fun tuck(by: String) {
        val full = (TUCK_BODY[round - 1] * fighter.openMul * fighter.lowMul).coerceAtLeast(TUCK_MIN)
        if (full > tuckLeft) { tuckLeft = full; tuckBy = by }
        lowBlowsLeft = fighter.lowBlows
    }

    /**
     * THE ELBOWS COME OUT. [Fighter.lowBlows] head punches do it (the Anvil wants two), a special
     * does it outright — and it is a CLEAR, not a decrement of the timer, because a long tuck must
     * be defeated by the right ACTION and not merely outwaited.
     */
    private fun untuck(clearAll: Boolean = false) {
        if (tuckLeft <= 0f) return
        if (clearAll || --lowBlowsLeft <= 0) { tuckLeft = 0f; lowBlowsLeft = 0; tuckBy = "" }
    }

    /** The full tuck this man would get this round — the basis for [TUCK_HOLD_FRAC]'s refresh. */
    private fun fullTuck(): Float =
        (TUCK_BODY[round - 1] * fighter.openMul * fighter.lowMul).coerceAtLeast(TUCK_MIN)

    /** A window opens (or a longer one replaces a shorter): what opened it names the `GUARD open= by=` line. */
    private fun openGuard(seconds0: Float, by: String, blows: Int) {
        // ONE PLACE. Every route into an opening — a body blow, a guard-counter, the end of a
        // recover, a special — comes through here, so [Fighter.openMul] is the whole of "his
        // openings are stingier" and there is no second scaling anybody can forget to apply.
        val seconds = seconds0 * fighter.openMul
        if (seconds > guardOpenLeft) { guardOpenLeft = seconds; guardOpenBy = by }
        bodyBlowsInWindow = blows
    }

    /**
     * The picture's guard, derived every frame from where he really is (the state under a hit
     * overlay, never the overlay): open through a stagger (not its shake), a stun, the pattern's
     * Open, every recover, and any window a body blow, a guard-counter, a special or a recover's
     * tail left running. Closed — gloves up — while he winds up, strikes, feints, taunts, or lies
     * on the canvas. The transitions are what the fight logs.
     */
    /**
     * WHAT THE PICTURE SAYS ABOUT HIS RIBS, which is not quite what the rule says: [lowOpen] goes
     * true [TUCK_SET_T] world seconds before a body punch would actually land. Called on the line
     * after [syncGuard] so the two can never drift by a frame.
     */
    private fun syncLowGuard() {
        val u = if (phase == Phase.HIT) under else phase
        val open = openAt(Level.BODY, u) || tuckLeft <= TUCK_SET_T
        if (open == lowOpen) return
        lowOpen = open
        listener?.onLowGuard(open, if (open) "OUT" else tuckBy.ifEmpty { "IN" })
    }

    private fun syncGuard() {
        val u = if (phase == Phase.HIT) under else phase
        val open = when (u) {
            Phase.STAGGER -> !shaking
            Phase.STUN, Phase.RECOVER -> true
            Phase.IDLE -> guardOpenLeft > 0f
            else -> false
        }
        if (open == guardOpen) return
        guardOpen = open
        if (open) {
            val by = when (u) { Phase.STAGGER -> "STAGGER"; Phase.STUN -> if (openStep) openBy else "STUN"; Phase.RECOVER -> "RECOVER"; else -> guardOpenBy.ifEmpty { "WINDOW" } }
            guardOpenBy = by
            listener?.onGuard(true, by)
        } else {
            listener?.onGuard(false, "CLOSE")
        }
    }

    // ================================================================== YOUR PUNCH ON HIM

    /**
     * Resolve the player's punch (DESIGN.md §4.2–§4.6, BOXER.md §4–§5). [dmg] is the punch's
     * base damage for its level; [counter] doubles it and waives nothing here (the clock is the
     * fight's); [special] is the Wake-Up Call. Returns one reused [Outcome] — read it before the
     * next call. The rules, in order:
     *
     *  1. WINDED never reaches here; on the canvas, taunting or on the win card there is nothing
     *     to hit (AIR).
     *  2. A HEAD punch on a CLOSED guard is a whiff: GUARD, `that_all` at most once per 6 s, a
     *     cyan spark (the fight's). The special into it only knocks the guard open 0.8 s. His
     *     guard is closed while he idles with no window running and while he straightens from a
     *     stagger; it is OPEN — the target lands — through a window, a recover, a stun, a stagger,
     *     and MID-ACTION (a tell, a strike, a feint: his hands are busy).
     *  3. A BODY blow always lands: the body is never covered by his gloves.
     *  4. What lands does its damage (× 2 for a COUNTER, × 2 in a STAGGER, 0 in a DRILL), and then:
     *     the special in a stagger is the knockdown he does not rise from; a COUNTER on the
     *     stagger a PERFECT-dodged Sunrise opened is the same; a RIGHT over his incoming LEFT PECK
     *     before its strike frame STUNS him (every hit inside resets the stun to the cap; a body
     *     blow WAKES him); any other early hit INTERRUPTS the tell; the special on an open guard
     *     STAGGERS; a body blow OPENS the guard for the round's window and a second inside it
     *     STAGGERS (a guard-counter's window counts as one already); a hit in a stagger EXTENDS
     *     it; everything else shows the reaction over whatever he was doing.
     *  5. The ladder: crossing 80 / 40 / 0 of his max drops him ([knockdown]).
     */
    fun punch(hand: Hand, level: Level, dmg: Int, counter: Boolean, special: Boolean, body: Body = lastBody): Outcome {
        outcome.clear()
        val u = if (phase == Phase.HIT) under else phase
        if (u == Phase.TAUNT || u == Phase.WIN || down) { outcome.result = PunchResult.AIR; return outcome }
        // YOU THREW FROM TOO FAR OUT. No lean bonus: a lean already moves `body.headX` and so
        // already moves the separation, in whichever direction the geometry says. Adding a reach
        // bonus on top would charge the lean twice.
        if (separation(body) > REST_RANGE + YOUR_REACH) { outcome.result = PunchResult.SHORT; return outcome }
        val inDrill = drill != Drill.OFF
        val staggered = u == Phase.STAGGER && !shaking
        val stunned = u == Phase.STUN && !openStep
        val midAction = u == Phase.TELL || u == Phase.STRIKE || u == Phase.RECOVER || u == Phase.FEINT
        // HIS HANDS ARE BUSY, BUT HIS GUARD IS NOT DOWN. A body blow during a tell, a strike or a
        // feint lands full and interrupts as it always did — but below, it banks NOTHING: no
        // window, no blow count, no tuck. Without that, the free hit a tell already gives you
        // also handed you the first half of a stagger for nothing.
        val busy = u == Phase.TELL || u == Phase.STRIKE || u == Phase.FEINT

        // THE GATE IS TWO GATES NOW (DESIGN.md §4.3, and the whole of [TUCK_BODY]'s note). It used
        // to read `level == Level.HEAD && !open`, so a body punch was never once tested against a
        // guard in the history of this game.
        if (!openAt(level, u)) {
            outcome.result = PunchResult.GUARD
            // THE SPECIAL IS HOISTED AND LEVEL-BLIND. DESIGN.md §4.5 says a special into a closed
            // guard only OPENS it; the shipped code let a BODY special skip the head-only gate
            // entirely and stagger him from a cold stance for 35.
            if (special) {
                openGuard(GUARD_OPEN_SPECIAL, "SPECIAL", blows = 0); outcome.opened = true
                tuckLeft = 0f; lowBlowsLeft = 0
            } else if (level == Level.BODY) {
                // THE HOLD: he saw that, and he is staying there. A correct read never trips it.
                tuckLeft = max(tuckLeft, TUCK_HOLD_FRAC * fullTuck())
            } else if (thatAllAgo >= THAT_ALL_COOLDOWN) {
                thatAllAgo = 0f; listener?.onSay(Lines.THAT_ALL, false)
            }
            listener?.onHitReaction(HitKind.BLOCKED)
            // THE ANVIL'S RULE. Everyone else merely absorbs a punch into a raised guard; he
            // charges for it. The counter is armed here rather than thrown here, because a punch
            // resolving inside another punch's resolution is how a fight state machine ties itself
            // in knots — [update] throws it on the next frame, as a real attack with a real (very
            // short) tell, so the player still SEES it coming and can still, just about, answer it.
            // It is the one thing on the card that punishes the verb the player most wants to use,
            // and it is why he is the third fight and not the first.
            if (fighter.gimmick == Fighter.Gimmick.COUNTER && !inDrill) counterArmed = true
            if (level == Level.HEAD) untuck()
            return outcome
        }

        // A HEAD PUNCH THAT REACHES HIS GLOVES BRINGS THE ELBOWS OUT, landed or blocked, because
        // it is the same motion either way. This is what makes a punch into the closed high guard
        // something other than a pure tax: it costs a heart and half a world second, and it buys
        // you the ribs.
        if (level == Level.HEAD) untuck(clearAll = special)

        // it lands
        var d = dmg
        if (counter) d *= 2
        if (staggered) d *= STAGGER_DMG_MUL
        if (inDrill) d = 0
        hp = (hp - d).coerceAtLeast(0)
        outcome.dmg = d
        val interrupts = u == Phase.TELL || u == Phase.FEINT
        // DESIGN.md §4.6 is a RIGHT thrown over his incoming LEFT PECK. A right to the RIBS at
        // that instant is a different punch and does not buy up to 1.6 s of open time.
        val stuns = u == Phase.TELL && attack == Attack.PECK_L && hand == Hand.RIGHT && level == Level.HEAD
        outcome.interrupted = interrupts
        outcome.result = when { staggered -> PunchResult.STAGGER; counter -> PunchResult.COUNTER; else -> PunchResult.LAND }

        // the reaction the renderer and the crowd see
        if (counter || special) { sparksT = SPARKS_T; headFlashT = HEAD_FLASH_T; mouth = Mouth.GRIMACE; listener?.onHitReaction(HitKind.SPIN) }
        else if (level == Level.BODY) { bodyFlashT = BODY_FLASH_T; mouth = Mouth.O; listener?.onHitReaction(HitKind.BODY) }
        else { headFlashT = HEAD_FLASH_T; mouth = Mouth.GRIMACE; listener?.onHitReaction(HitKind.HEAD) }

        // the knockdowns that do not read the ladder
        if (special && staggered) { if (!inDrill) { knockdown(true, "SPECIAL IN STAGGER"); return outcome } }
        if (counter && staggered && perfectAttack == Attack.SUNRISE) { if (!inDrill) { knockdown(true, "COUNTER ON THE SUNRISE"); return outcome } }

        when {
            staggered -> extendStagger()
            stuns -> { beginStun(); outcome.stunned = true }
            stunned -> if (level == Level.BODY) { tuck("WAKE"); wake() } else restun()
            special -> { beginStagger("SPECIAL"); outcome.staggered = true }
            level == Level.BODY -> {
                if (busy) hitOverlay(level, interrupts)          // banks nothing: see `busy` above
                else if (guardOpenLeft > 0f && bodyBlowsInWindow >= 1) {
                    beginStagger("BODY", BODY_STAGGER_FRAC); outcome.staggered = true
                } else {
                    val opened = !guardOpen
                    openGuard(GUARD_OPEN_BODY[round - 1], "BODY", blows = bodyBlowsInWindow + 1)
                    tuck("BODY")                                  // the same blow writes both timers
                    ratchetBack()                                 // and buys back the ring he cut
                    outcome.opened = opened
                    hitOverlay(level, interrupts)
                }
            }
            else -> hitOverlay(level, interrupts)
        }
        if (!inDrill) ladder()
        return outcome
    }

    // ================================================================== TELEMETRY

    /** The 5 Hz `VERIFY` half that is his: state, strip:frame, HP, guard, the floor, the aim vs the capsule. */
    fun verifyLine(body: Body, floor: Float): String =
        "VERIFY him=%s atk=%s strip=%s:%d hp=%d/%d guard=%s low=%s pos=(%+.2f,%+.2f) rng=%.2f stagger=%.2f stun=%.2f floor=%.2f aim=(%+.2f,%.2f) cap=(%+.2f,%.2f..%.2f) phrase=%s".format(
            Locale.US, phase.name, attack?.name ?: "-", strip.name, strip.local, hp, hpMax, if (guardOpen) "OPEN" else "UP", if (lowOpen) "OUT" else "IN", footX, footZ, range,
            staggerLeft, stunLeft, floor, aimX, aimY, body.headX, body.bottom, body.top, phraseName.ifEmpty { "-" })

    // ================================================================== INTERNALS: the phase's entry, the strip, the picture

    /**
     * Enter a phase: the timer, the strip segment that draws it, and the face and gloves that
     * belong to it. The TELL is the one entry that re-plays a strip already showing (a 1-2 of the
     * same hand starts its second telegraph from frame 0). The aim is placed here for a tell so
     * the arc exists on the tell's first frame.
     */
    private fun enter(p: Phase) {
        phase = p; phaseT = 0f
        if (p != Phase.HIT) { under = Phase.IDLE; underT = 0f; hitLeft = 0f }
        val atk = attack
        when (p) {
            Phase.IDLE -> { playLoop("idle"); flashGlove = null; extend = false; pupils = Pupils.NONE; slits = false; aimSet = false; tongue = false; spirals = false; crest = Crest.VIOLET; if (mouth == Mouth.CROW || mouth == Mouth.FLAT) mouth = Mouth.GRIN }
            Phase.TAUNT -> { playOnce("taunt"); resetFace(); tongue = true; mouth = Mouth.GRIN }
            Phase.WIN -> { playLoop("win"); resetFace(); mouth = Mouth.GRIN }
            Phase.TELL -> {
                if (atk != null) {
                    flashGlove = atk.hand; extend = false
                    pupils = when (atk) { Attack.PECK_L -> Pupils.LEFT; Attack.PECK_R -> Pupils.RIGHT; Attack.SUNRISE -> Pupils.BOTH; else -> Pupils.NONE }
                    slits = atk.isWing
                    crest = when (atk) { Attack.WING_R -> Crest.GOLD; Attack.WING_L -> Crest.GOLD_DROOP; Attack.SUNRISE -> Crest.WHITE; else -> Crest.VIOLET }
                    mouth = if (atk == Attack.SUNRISE) Mouth.CROW else Mouth.FLAT
                    tongue = false; spirals = false
                    val st = strip.set; val s = st?.strip(atk.strip)
                    if (st != null && s != null) playSegment(atk.strip, 0, tellFrames(s), tellDur)
                    else { segName = atk.strip; segFrom = 0; segFrames = 6; segSeconds = tellDur; segRate = 1f }
                }
            }
            Phase.STRIKE -> {
                if (atk != null) {
                    mouth = if (atk == Attack.SUNRISE) Mouth.CROW else Mouth.FLAT
                    val st = strip.set; val s = st?.strip(atk.strip)
                    if (st != null && s != null) { val sf = strikeFrames(atk, st); playSegment(atk.strip, tellFrames(s), sf, sf * st.frameT) }
                }
            }
            Phase.RECOVER -> {
                flashGlove = null; extend = false; pupils = Pupils.NONE; slits = false; aimSet = false; tracking = false
                crest = Crest.VIOLET; mouth = Mouth.FLAT; tongue = false; spirals = false
                if (atk != null) {
                    val st = strip.set; val s = st?.strip(atk.strip)
                    if (st != null && s != null) { val from = tellFrames(s) + strikeFrames(atk, st); playSegment(atk.strip, from, (s.count - from).coerceAtLeast(1), recoverDur) }
                    else { segName = atk.strip; segFrom = 0; segFrames = 6; segSeconds = recoverDur; segRate = 1f }
                }
            }
            Phase.FEINT -> {
                val f = feint
                flashGlove = null; extend = false; slits = false; aimSet = false; tongue = false; spirals = false
                pupils = Pupils.NONE; crest = if (f == Feint.FALSE_SUNRISE) Crest.WHITE else Crest.VIOLET
                mouth = if (f == Feint.FALSE_SUNRISE) Mouth.CROW else Mouth.FLAT
                if (f != null) { val st = strip.set; val s = st?.strip(f.strip); if (st != null && s != null) playSegment(f.strip, 0, s.count, feintDur) }
            }
            Phase.STUN -> { playLoop("stun"); resetFace(); mouth = Mouth.GRIMACE }
            Phase.STAGGER -> { playLoop("stagger"); resetFace(); mouth = Mouth.GRIMACE; spirals = true; tongue = true }
            Phase.HIT -> {}
            Phase.KNOCKDOWN -> { playOnce(if (koFall) "ko" else "knockdown"); resetFace(); mouth = Mouth.GRIMACE; tongue = koFall }
            Phase.DOWN -> { playLoop("down"); mouth = Mouth.GRIMACE; downT = 0f }   // the referee's count starts here (the fall ran under SLOW)
            Phase.GETUP -> { playOnce("getup"); mouth = Mouth.GRIMACE; tongue = false }
            Phase.KO -> {
                // from DOWN (the count reached ten) hold the flat pose; from the fall the `ko` strip is already flat at its end
                if (strip.name != "ko") { val st = strip.set; val s = st?.strip("ko"); if (st != null && s != null) playSegment("ko", s.count - 1, 1, 1f) }
                resetFace(); mouth = Mouth.GRIMACE; tongue = true
            }
        }
    }

    private fun resetFace() { flashGlove = null; extend = false; pupils = Pupils.NONE; slits = false; crest = Crest.VIOLET; tongue = false; spirals = false; aimSet = false; tracking = false }

    /** The strip's tell segment: up to its `strike` event, or the first 40 % of a strip that has none. */
    private fun tellFrames(s: StripSet.Strip): Int = s.event("strike").let { if (it > 0) it else (s.count * 0.4f).toInt().coerceAtLeast(1) }
    /** The strike segment: the authored strike length in frames, always played at 1× (BOXER.md §6). */
    private fun strikeFrames(atk: Attack, st: StripSet): Int = (atk.strikeT * st.fps).roundToInt().coerceAtLeast(1)

    /**
     * Play [frames] frames of [name] from [from] so that they span [seconds] of the strip's clock,
     * by setting the player's rate: the strip follows the phase timer instead of driving it, and
     * a seek at every segment boundary means drift never accumulates. `StripPlayer` has no seek,
     * so the seek is a `play` and one `update` of the frames to skip (their events fire into
     * nobody — this class attaches no `onEvent`). A missing strip leaves the picture on whatever
     * was showing and the fight unchanged: the timers are the truth, the strips are the drawing.
     */
    private fun playSegment(name: String, from: Int, frames: Int, seconds: Float) {
        segName = name; segFrom = from; segFrames = frames.coerceAtLeast(1); segSeconds = seconds.coerceAtLeast(0.001f)
        val st = strip.set ?: return
        val s = st.strip(name) ?: return
        val ft = st.frameT
        segRate = (segFrames * ft) / segSeconds
        if (!strip.play(name)) return
        strip.rate = 1f
        val f = from.coerceIn(0, s.count - 1)
        if (f > 0) strip.update(f * ft + ft * 0.01f)
        strip.rate = segRate
    }

    private fun playLoop(name: String) {
        val st = strip.set; val s = st?.strip(name)
        if (st != null && s != null) playSegment(name, 0, s.count, s.count * st.frameT)
        else { segName = name; segFrom = 0; segFrames = 1; segSeconds = 1f; segRate = 1f }
    }

    private fun playOnce(name: String) = playLoop(name)

    /** After a hit overlay: back to the attack's segment at the frame its clock says. */
    private fun resumeAttackSegment() {
        val st = strip.set ?: return
        val s = st.strip(segName) ?: return
        val atk = attack ?: return
        if (segName != atk.strip) return
        val k = ((phaseT / segSeconds) * segFrames).toInt().coerceIn(0, segFrames - 1)
        val rate = segRate
        if (!strip.play(segName)) return
        strip.rate = 1f
        val f = (segFrom + k).coerceIn(0, s.count - 1)
        if (f > 0) strip.update(f * st.frameT + st.frameT * 0.01f)
        strip.rate = rate
    }

    /**
     * The semantic picture per frame — the renderer's tint pass reads these and never the phase
     * table. What the entry set stands; this is what moves: the feint's two pupil flashes and the
     * false crow's cut, the amber of a straight's stalled fuse, the wobble, the squash, the mouth
     * returning to the grin once the flash is gone.
     */
    private fun paint(w: Float, body: Body) {
        val atk = attack
        when (phase) {
            Phase.TELL -> if (atk != null && atk.isPeck) crest = if (stillInFuse) Crest.AMBER else Crest.VIOLET
            Phase.FEINT -> when (feint) {
                Feint.HALF_PECK -> {
                    val f = ((phaseT / feintDur) * 4f).toInt()
                    pupils = if (f == 0 || f == 2) (if (atk == Attack.PECK_R) Pupils.RIGHT else Pupils.LEFT) else Pupils.NONE
                }
                Feint.FALSE_SUNRISE -> {
                    val cut = phaseT >= feintDur * 0.6f
                    crest = if (cut) Crest.VIOLET else Crest.WHITE
                    mouth = if (cut) Mouth.FLAT else Mouth.CROW
                    pupils = if (cut) Pupils.NONE else Pupils.BOTH
                }
                else -> {}
            }
            Phase.IDLE -> if (headFlashT <= 0f && bodyFlashT <= 0f) mouth = Mouth.GRIN
            else -> {}
        }
        wobble = when {
            phase == Phase.STAGGER && !shaking -> WOBBLE_RAD * sin(2f * PI.toFloat() * WOBBLE_HZ * staggerT)
            phase == Phase.STAGGER -> wobble * exp(-w / 0.08f)
            else -> 0f
        }
        squash = max(headFlashT / HEAD_FLASH_T, bodyFlashT / BODY_FLASH_T).coerceIn(0f, 1f)
    }

    /**
     * The 4-slot rotation of a round's phrases after its opening, seeded per fight so a run is
     * learnable and no two runs are identical. Deterministic: the same seed and round give the
     * same order, which is what makes the `STRIKE answer=` log checkable against the table.
     */
    private fun rotationFor(round: Int, seed: Int): IntArray {
        val n = 4
        val order = IntArray(n) { it }
        var h = seed * 1103515245 + 12345 + round * 7919
        for (i in n - 1 downTo 1) {
            h = h * 1103515245 + 12345
            val j = ((h ushr 16) and 0x7fff) % (i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        return order
    }

    // ================================================================== THE PATTERN TABLES (BOXER.md §7)

    /** Round 1 — THE STRUT: teach each answer, one at a time, twice. A opens; then B C D rotate, E when eligible. */
    val PATTERN_R1: List<Phrase> = listOf(
        Phrase("A", listOf(
            Step.Hit(Attack.PECK_L), Step.Wait(1.2f), Step.Hit(Attack.PECK_L), Step.Wait(1.2f),
            Step.Hit(Attack.PECK_R), Step.Wait(1.2f), Step.Hit(Attack.PECK_R),
            Step.Branch(Read.DUCKED, next = "C"),
        )),
        Phrase("B", listOf(
            Step.Hit(Attack.WING_R), Step.Wait(1.5f), Step.Hit(Attack.WING_R),
            Step.Branch(Read.SLIPPED_INTO_HIT, next = "B"),
        ), repeats = 1),
        Phrase("C", listOf(
            Step.Hit(Attack.WING_L), Step.Wait(1.5f), Step.Hit(Attack.WING_L),
            Step.Branch(Read.DUCKED, next = "C", say = Lines.WAKE_UP),
        ), repeats = 1),
        Phrase("D", listOf(
            Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R), Step.Wait(1.0f),
            Step.Branch(Read.CENTRED, then = listOf(Step.Hit(Attack.SUNRISE, tellT = TELL_SUCKER))),
            Step.Branch(Read.SLIPPED, then = listOf(Step.Hit(Attack.SUNRISE))),
        )),
        Phrase("E", listOf(Step.Hit(Attack.SUNRISE), Step.Wait(2.0f), Step.Hit(Attack.SUNRISE)), hpBelow = 0.6f),
    )

    /** Round 2 — THE RUFFLE: chain and feint. "The third flash is the truth." */
    val PATTERN_R2: List<Phrase> = listOf(
        Phrase("A2", listOf(
            Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = TELL_CHAIN_R2), Step.Wait(1.0f),
            Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R),
            Step.Branch(Read.BLOCKED_BOTH, then = listOf(Step.Feint(Feint.HALF_PECK, Hand.LEFT), Step.Hit(Attack.PECK_R, tellT = TELL_FEINT_FOLLOW))),
        )),
        Phrase("B2", listOf(
            Step.Feint(Feint.HALF_PECK, Hand.LEFT), Step.Wait(0.3f),
            Step.Branch(Read.SLIPPED_L, then = listOf(Step.Hit(Attack.PECK_R, tellT = TELL_FEINT_FOLLOW))),
            Step.Branch(Read.SLIPPED_R, then = listOf(Step.Hit(Attack.WING_L))),
            Step.Branch(Read.DUCKED, then = listOf(Step.Hit(Attack.SUNRISE))),
            Step.Branch(Read.STILL_OR_GUARDING, then = listOf(Step.Hit(Attack.WING_R))),
        )),
        Phrase("C2", listOf(
            Step.Hit(Attack.WING_R), Step.Wait(0.8f), Step.Hit(Attack.WING_L),
            Step.Branch(Read.DUCKED, then = listOf(Step.Hit(Attack.SUNRISE)), say = Lines.WAKE_UP),
        )),
        Phrase("D2", listOf(
            Step.Hit(Attack.WING_L), Step.Wait(0.6f),
            Step.Branch(Read.GUARD_COUNTERED, then = listOf(Step.Open(0.4f)), otherwise = listOf(Step.Hit(Attack.SUNRISE))),
        )),
        Phrase("E2", listOf(
            Step.Hit(Attack.SUNRISE), Step.Wait(1.5f), Step.Feint(Feint.FALSE_SUNRISE, Hand.RIGHT),
            Step.Branch(Read.SLIPPED, then = listOf(Step.Hit(Attack.WING_R))),
            Step.Branch(Read.STEPPED, then = listOf(Step.Open(0.8f))),
            Step.Branch(Read.DUCKED, then = listOf(Step.Hit(Attack.SUNRISE))),
        )),
    )

    /** Round 3 — THE COCKFIGHT: everything, fast, tracking. "The crest never lies, the feet sometimes do." */
    val PATTERN_R3: List<Phrase> = listOf(
        Phrase("A3", listOf(
            Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = TELL_CHAIN_R3), Step.Hit(Attack.PECK_L, tellT = TELL_CHAIN_R3),
            Step.Wait(0.6f), Step.Hit(Attack.SUNRISE, tracking = true),
            Step.Branch(Read.STEPPED, then = listOf(Step.Wait(1.0f))),
        )),
        Phrase("B3", listOf(
            Step.Hit(Attack.WING_R), Step.Hit(Attack.WING_L, tellT = TELL_DOUBLE_WING),
            Step.Branch(Read.STEPPED, then = listOf(Step.Award("STEP +400", 400))),
            Step.Branch(Read.DUCKED, say = Lines.WAKE_UP),
        )),
        Phrase("C3", listOf(Step.Feint(Feint.HALF_STAMP, Hand.RIGHT), Step.Wait(0.4f), Step.Hit(Attack.WING_R))),
        Phrase("D3", listOf(
            Step.Feint(Feint.HALF_PECK, Hand.RIGHT),
            Step.Branch(Read.SLIPPED_R, then = listOf(Step.Hit(Attack.PECK_L, tellT = TELL_FEINT_FOLLOW))),
            Step.Branch(Read.SLIPPED_L, then = listOf(Step.Hit(Attack.WING_R))),
            Step.Branch(Read.DUCKED, then = listOf(Step.Hit(Attack.SUNRISE, tracking = true))),
            Step.Branch(Read.GUARDED, then = listOf(Step.Hit(Attack.WING_L))),
            Step.Branch(Read.STEPPED, then = listOf(Step.Open(0.6f))),
        )),
        Phrase("E3", listOf(
            Step.Hit(Attack.SUNRISE, tracking = true), Step.Wait(1.0f), Step.Hit(Attack.SUNRISE, tracking = true),
            Step.Branch(Read.STEPPED_BOTH, then = listOf(Step.Open(1.5f))),
        )),
    )

    /** The round's table. */
    /**
     * HIS pattern, or the Rooster's. A fighter with an empty [Fighter.patterns] keeps the tables
     * below, which is how the Rooster stays byte-for-byte the fight that was tested; anyone else
     * brings three lists of their own and a short list simply repeats its last round.
     */
    fun pattern(round: Int): List<Phrase> {
        val own = fighter.patterns
        if (own.isNotEmpty()) return own[(round - 1).coerceIn(0, own.size - 1)]
        return when (round) { 2 -> PATTERN_R2; 3 -> PATTERN_R3; else -> PATTERN_R1 }
    }
}
