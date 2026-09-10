package com.x3knockout.engine

import android.util.Log
import com.x3knockout.SettingsStore
import com.x3knockout.audio.Music
import com.x3knockout.audio.Sfx
import com.x3knockout.head.MotionTracker
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Everything the game asks of the device. Implemented by MainActivity; every call is safe from the GL thread. */
interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    /** THE CROWD IS THE RATE METER (DESIGN.md §9.3): the looping bed's loudness 0..1 and its pitch. 0 stops it. */
    fun crowd(level: Float, rate: Float)
    /** The system track (`voice/`): ANNOUNCER, REFEREE, CORNER, CROWD. Urgent lines preempt the other track. */
    fun say(id: String, urgent: Boolean = false, patienceMs: Long = 1500L)
    fun sayAll(ids: List<String>)
    fun stopVoice()
    /** The hero track (`voice_hero/`): THE ROOSTER. His crow is a TELEGRAPH and is always urgent. */
    fun hero(id: String, urgent: Boolean = false, patienceMs: Long = 1500L)
    fun stopHero()
    fun musicEnabled(on: Boolean)
    fun voiceEnabled(on: Boolean)
    fun recentreHead()
    /**
     * THE ROUND-CARD RE-CENTRE IS YAW ONLY. Forward is re-declared wherever the player is looking
     * on the card — where drift hides for free — but the PITCH reference must survive it, or a
     * player who read the card 15° down would have that nod declared level and spend the round
     * with the horizon and the duck's rest disagreeing by 15°. The REST POSTURE (`restRoll`,
     * `restPitch`) IS re-declared there, by the fight, while the head is still (DESIGN.md §3.1).
     */
    fun recentreYaw()
    fun applyVolume(v0to10: Int)
    fun voiceDurationMs(id: String): Int
    fun heroDurationMs(id: String): Int
    fun voiceBusy(): Boolean
    fun quitGame()
    /**
     * THE CABINET'S TRACKS (DESIGN.md §9.5). The fight names the track for the state it is in —
     * `Music.TITLE` on the attract, `Music.FIGHT` / `Music.FIGHT3` from the round card, `Music.COUNT`
     * while somebody is on the canvas, `Music.WIN` on the knockout — and the host CUTS to it, never
     * a crossfade: a boxing cabinet changes its music at the bell, the knockdown and the win, and
     * the cut is the punctuation. The body is a default no-op on purpose: `MainActivity` overrides
     * it with `music.play(track)`, and the JVM tests' recording host has no player and wants
     * nothing to happen — a default here is what lets that host implement only the calls it
     * audits, and a host that forgets the override loses the cuts, never the build.
     */
    fun music(track: String) {}
}

/**
 * The cabinet's flow (DESIGN.md §1.7). `Hud.Phase` mirrors this one for one; the settings menu
 * and the credits are flags over whichever state they were opened in, never states of their own.
 *  - TITLE: the attract — his `idle` in the ring behind the marquee.
 *  - INTRO: the announcer's three lines over his `taunt` (~6 s); a tap after 1.5 s skips to the
 *    card, never mid-line.
 *  - ROUND_CARD: `ROUND n` + the name, 1.2 s, yaw and the rest posture re-declared, then the bell.
 *  - FIGHT: the round, 60 world seconds.
 *  - KNOCKDOWN_COUNT: somebody is down; the fall at `Forced.SLOW`, then the referee counts on the
 *    real clock (`Forced.COUNT`).
 *  - ROUND_END: the bell, then THE CORNER — `Forced.CORNER` 2.2 s, +30 HP, hearts, one sentence.
 *  - KO: the win — the three bells, the roar, the tally; a tap after 1.2 s → the title.
 *  - GAME_OVER: your third knockdown, a count you did not beat, or `TIME - NO DECISION`;
 *    `INSERT COIN TO CONTINUE`, 9 s.
 */
enum class State { TITLE, INTRO, ROUND_CARD, FIGHT, KNOCKDOWN_COUNT, ROUND_END, KO, GAME_OVER }

/**
 * THE FIGHT. One player standing in a ring, one boxer 2.6 m ahead, and the law: time moves only
 * when you move — with the floor handed to the boxer's own state (DESIGN.md §2).
 *
 * WHAT THIS FILE IS AND IS NOT. It is the orchestration: the state machine above, the two clocks,
 * the inferred body and its collider, the player's side of the fight (HP, hearts, the KO meter,
 * the punches and their quanta, the guard, the step, the counter window), the score, the rounds,
 * the timer, the count for either fighter, the settings menu, the event log, and the boundary
 * writes to the host. It is NOT the opponent — `Boxer` owns his HP, his pattern, his guard and
 * everything that opens it, his hang and his fuse, the collider test — and it is NOT the picture:
 * the renderer reads the fields below and `Boxer`'s and draws.
 *
 * THE TWO CLOCKS ARE THE ONE THING TO GET RIGHT (DESIGN.md §2.9). `clock.wdt` goes to the boxer,
 * to the heart refill, to the round clock, to the guard's pop; `clock.dt` runs the hang and the
 * fuse (inside the boxer), the punch animations, the counter window, the meter's pour, the count,
 * the corner, the stall timer, the 3:00 cap, the bells, every flash and every word. The audit is
 * the shape of [update]; the proof is `TwoClocksTest`; the on-glass check is the announcer still
 * talking while the rail sits at the floor.
 *
 * THE PUNCH ECONOMY, in one paragraph (DESIGN.md §2.3, §4.2–4.5): a tap is a punch the instant the
 * finger lifts; the punch is a FORCED window of real time at rate 1.0 (a delicate tap and a slap
 * cost the same), and what happens at its contact frame shapes the rest — a LANDED punch is CUT
 * to its active frames and its recovery is cancellable into the next, a WHIFF (his closed guard
 * or air) is EXTENDED by the mash tax and costs a heart, a COUNTER inside the perfect window is
 * never forced at all. Hearts refill on WORLD time with no punch in flight, so a still player does
 * not heal; the KO meter's target moves on world events and its fill pours on real time; the
 * special is both pads inside [SPECIAL_MS] with the meter lit, upgraded in place from the punch
 * already in flight. Four blind punches into his guard cost ≈ 3 s of world time and four hearts;
 * four that land cost under a second. Rhythm, not mashing, is what the clock rewards.
 *
 * WHAT THE PAD OWNS AND WHAT THE BODY OWNS (DESIGN.md §1): LEFT tap = left punch, RIGHT tap =
 * right punch, both inside [SPECIAL_MS] with the meter lit = the special; head level = head
 * shot, head down = body blow (one axis, §1.3); tilt = slip, nod = duck (the collider moves,
 * §3); right-pad swipe DOWN held = guard; swipe L/R or a real sidestep = step. `MainActivity`
 * attributes taps to pads from the TOUCH stream and calls [punch]; keys never name a pad.
 */
class Fight(private val store: SettingsStore, private val host: GameHost) : Boxer.Listener {

    companion object {
        private const val TAG = "X3Knockout"

        // ---------------------------------------------------------------- the body (MOTION.md; DESIGN.md §3)
        /** Standing eye height, metres: the camera. */
        const val EYE_H = 1.65f
        const val LEAN_MAX = 0.55f
        /** Dead zone ~4°; the full lean and full duck by DODGE SENSE — LOW (on disk) / MEDIUM (ships) / HIGH. */
        const val LEAN_DEAD = 0.07f
        val LEAN_FULL = floatArrayOf(0.38f, 0.28f, 0.21f)
        val DUCK_FULL = floatArrayOf(0.50f, 0.38f, 0.28f)
        const val DUCK_DROP = 0.42f
        /** The collider: a capsule from HEAD_TOP above the eye to BODY_LEN below it, half-width BODY_HW. */
        const val BODY_HW = 0.26f
        const val HEAD_TOP = 0.12f
        const val BODY_LEN = 0.95f
        /** A step: 0.55 m out over the forced STEP_T, back over 0.75 s real. */
        const val STEP_DX = 0.55f
        const val STEP_BACK_T = 0.75f
        /** The aim level's hysteresis (§1.3): enter LOW at 0.35 of a duck, leave at 0.25; a dodge under a hook from 0.6. */
        const val AIM_LOW_ENTER = 0.35f
        const val AIM_LOW_LEAVE = 0.25f
        const val DUCK_DODGE = 0.6f
        /** The camera's roll compensation and its settle (x3discs' ruling, kept); PITCH COMP by the lab row. */
        const val ROLL_COMP = 1f
        const val ROLL_TAU = 0.08f
        val PITCH_COMP = floatArrayOf(0f, 0.5f, 0.7f)
        /**
         * STILLNESS FOR THE STALL PRESSURE: `Clock.m` under this counts as standing still. Measured
         * here and not by `Clock.still`, because that one wants the RATE under 0.10 for a second,
         * and the boxer's idle floor is 0.35 — at that floor the rail never reads still, and the
         * crowd would never boo a player who has not moved for a minute. The stall is about the
         * BODY, so it reads the body's scalar and ignores the floor entirely.
         */
        const val STILL_M = 0.05f
        /** Your fall: the view sinks 0.4 m over 0.4 s real, and rises with you (DESIGN.md §5.2). */
        const val SINK_M = 0.4f
        const val SINK_T = 0.4f

        // ---------------------------------------------------------------- the pad (DESIGN.md §1.4)
        /** Both pads inside this = the special (if lit). A guess; the `PAIR ms=` p90 ships (TEST.md L4). */
        const val SPECIAL_MS = 120L
        /** A tap: finger-lift under this, travel under this (px on the pad's own axis). */
        const val TAP_MAX_MS = 400L
        const val TAP_TRAVEL_PX = 115f
        /** Taps on the round card are ignored for this long after the coin (a double-tap's second lift). */
        const val COIN_DEDUPE_MS = 200L

        // ---------------------------------------------------------------- you (DESIGN.md §4)
        const val HP_MAX = 100
        const val CORNER_HEAL = 30
        const val RISE_HP = 40
        const val KNOCKDOWNS_TO_LOSE = 3
        const val HEARTS = 3
        /** +1 heart per this much WORLD time with no punch in flight. */
        const val HEART_REFILL_T = 1.0f
        /** The jab: to land / recovery; the cross; the special. Real seconds. */
        const val JAB_LAND_T = 0.15f
        const val JAB_RECOVER_T = 0.13f
        const val CROSS_LAND_T = 0.18f
        const val CROSS_RECOVER_T = 0.18f
        const val SPECIAL_LAND_T = 0.30f
        const val SPECIAL_RECOVER_T = 0.30f
        /** Damage on an open guard: jab head / body, cross head / body, the special. */
        const val JAB_DMG_HEAD = 6
        const val JAB_DMG_BODY = 8
        const val CROSS_DMG_HEAD = 8
        const val CROSS_DMG_BODY = 10
        const val SPECIAL_DMG = 35
        /** A punch thrown while `m > 0.6` has a 25 % chance to WHIFF past his head: a shaking head cannot aim. */
        const val WHIFF_M = 0.6f
        const val WHIFF_CHANCE = 0.25f
        /** The counter window after a PERFECT, real seconds, by difficulty. */
        val COUNTER_WINDOW = floatArrayOf(0.8f, 0.6f, 0.45f)
        /** The right wing's stun on you: the plate jitters 4 px for 0.3 s. A crushed guard is down 0.4 s real. */
        const val STUN_YOU_T = 0.3f
        const val GUARD_CRUSH_T = 0.4f
        /** The guard's flick pops for 0.5 s of WORLD time. */
        const val GUARD_POP_T = 0.5f

        // ---------------------------------------------------------------- the KO meter (DESIGN.md §4.5)
        const val METER_MAX = 30
        const val METER_LIT = 26
        const val METER_SEED = 4
        const val METER_FIRST = 2
        const val METER_CHAIN = 5
        const val METER_COUNTER = 10
        const val METER_PERFECT = 3
        const val METER_CLEAN = 1
        const val METER_BLOCKED = -1
        const val METER_DODGED = -2
        /** The fill animates toward the target at one point per this many real seconds. */
        const val METER_POUR_S = 0.133f

        // ---------------------------------------------------------------- the score (DESIGN.md §5.5)
        const val SCORE_HEAD = 100
        const val SCORE_BODY = 150
        const val SCORE_CLEAN = 50
        const val SCORE_PERFECT = 300
        const val SCORE_COUNTER = 300
        const val SCORE_GUARD_COUNTER = 150
        const val SCORE_STEP_DOUBLE = 400
        const val SCORE_SPECIAL = 1000
        const val SCORE_KNOCKDOWN = 2000
        const val SCORE_KO = 5000
        /** `10 000 × max(0, 1 − realSeconds / 180)` at the KO — the only place REAL time is judged. */
        const val TIME_BONUS_MAX = 10_000
        const val TIME_BONUS_S = 180f
        const val MULT_MAX = 4
        val DIFF_MULT = floatArrayOf(0.75f, 1.0f, 1.5f)

        // ---------------------------------------------------------------- rounds (DESIGN.md §5.3)
        const val ROUNDS = 3
        const val ROUND_WORLD_S = 60f
        /** Past this many REAL seconds the floor holds 0.35 for the rest of the round: the soft anti-stall. */
        const val ROUND_REAL_CAP_S = 180f
        /** The fallback only; every fighter carries his own three (see [Fighter.roundNames]). */
        val ROUND_NAMES = arrayOf("THE STRUT", "THE RUFFLE", "THE COCKFIGHT")
        const val CARD_T = 1.2f
        const val CORNER_T = Clock.CORNER_T
        /** The wood-block clapper on each of the last 10 world seconds. */
        const val CLAPPER_FROM_S = 10f
        /** The tally's dramatic beat and the tap that skips it. */
        const val INTRO_SKIP_T = 1.5f
        const val CORNER_SKIP_T = 1.0f
        const val TALLY_SKIP_T = 1.2f
        const val GAMEOVER_SKIP_T = 1.2f
        const val CONTINUE_S = 9f
        /**
         * The intro's floor and ceiling, real seconds. The announcer's three lines run ≈ 6 s when
         * the clips exist; when they do not (the voice bus fires line-end at once for a missing
         * clip) the card would arrive before the plate had drawn `THE ROOSTER`, and when VOICE is
         * OFF the bus fires nothing at all and the intro would never end. So the intro lasts at
         * least the floor whatever the bus says, and at most the ceiling whatever it does not.
         */
        const val INTRO_MIN_T = 3.0f
        const val INTRO_MAX_T = 12f
        /** After the continue countdown expires the card returns to the attract by itself. */
        const val GAMEOVER_HOLD_T = 3.0f
        /** Rise from your own knockdown: 8 alternated taps before "10"; a same-hand double counts once. */
        const val RISE_TAPS = 8
        /** The numeral pops 0.5 → 5.0 over 80 ms with a click; the crowd counts along from 5. */
        const val COUNT_POP_T = 0.08f
        const val COUNT_CROWD_FROM = 5
        /** The bell: three strokes to start a round, one to end it, three fast for a KO (§5.3). */
        const val BELL_GAP_T = 0.45f
        const val BELL_GAP_KO_T = 0.25f
        /** The crowd roars for this long on the knockout. */
        const val KO_ROAR_T = 2.0f
        /** The feedback word's hold and the answer word's pop / drop (real seconds). */
        const val FEEDBACK_T = 0.6f
        const val ANSWER_POP_T = 0.08f
        const val ANSWER_DROP_T = 0.12f
        /** `CAPTIONS AUTO`: the answer word for the first two appearances of each attack, then never. */
        const val CAPTION_AUTO_TIMES = 2
        /** The corner's caption holds the clip plus this, and never less than the corner itself. */
        const val CAPTION_TAIL_T = 0.75f
        /** The damage frame's decay, the hit-stop kick, the KO box's blink. */
        const val DAMAGE_FLASH_DECAY = 1.6f
        const val HIT_KICK_PX = 6f
        const val CAM_SHAKE = 0.18f
        /** The impact FX's life and the special's starburst (real seconds). */
        const val IMPACT_T = 0.3f
        const val STARBURST_T = 0.6f
        /** The crowd bed follows the rate with this lag (real seconds; TEST.md T5 rules on it). */
        const val CROWD_LAG_T = 0.2f
        /** `tip_step` after this many body steps rejected during pad blanks. */
        const val STEP_REJECT_TIP_N = 3
        /** The crowd chants after you have been hit this many times without answering. */
        const val CHANT_AFTER_HITS = 2
        /** The stagger's warble is retriggered at this period while he wobbles: the bank has no looping id for it. */
        const val WARBLE_PERIOD = 0.5f
        /** The desk script's first beat after the bell. */
        const val SCRIPT_DELAY_T = 0.6f
        /** The credits page: rows the panel shows, and how far one swipe scrolls it (px, the Hud's pitch is 16). */
        const val CREDITS_ROW_PX = 16f
        const val CREDITS_VISIBLE = 15
        const val CREDITS_STEP_ROWS = 3

        /** The onomatopoeia at the marker — generic comic words only (DESIGN.md §7.6). */
        const val WORD_JAB = "POW"
        const val WORD_CROSS = "BAM"
        const val WORD_BODY = "THUD"
        const val WORD_COUNTER = "WHAP"
        const val WORD_SPECIAL = "WHAP"

        /**
         * THE CORNER'S SENTENCES, in the plate's alphabet. The voice bus plays the clip by id
         * (BOXER.md §9 is the script) and the plate captions ONLY these — the teaching lines
         * DESIGN.md §8 / §10 raise on the clip's line-start — because a corner tip the player
         * cannot hear (VOICE OFF, or a clip not rendered yet) is a corner that taught nothing.
         * Every character here is in `StrokeFont`'s set (`'` is; `+` and `"` are not) and a `|`
         * splits the two-line captions where the Hud expects it.
         */
        val TIP_CAPTIONS: Map<String, String> = mapOf(
            Lines.TIP_PECK to "LEAN OFF THE LIT GLOVE.",
            Lines.TIP_WING_R to "GOLD CREST, GET LOW.",
            Lines.TIP_WING_L to "NEVER DUCK THE LOW ONE.|BLOCK IT.",
            Lines.TIP_SUNRISE to "WHEN HE CROWS,|GET OFF THE LINE.",
            Lines.TIP_GUARD to "YOUR HANDS ARE WASTED ON HIS GLOVES.|DIG THE BODY.",
            Lines.TIP_STILL to "READ HIM, THEN MOVE.|STANDING STILL IS NOT A PLAN.",
            Lines.TIP_STEP to "DON'T PUNCH AND RUN.",
            Lines.TIP_SPECIAL to "THE METER'S LIT. BOTH HANDS.",
        )

        /** [hitBy]'s extra slots past the five attacks: your punches on his guard, stalls, rejected steps, a lit meter never spent. */
        private const val HIT_BLOCKED = 5
        private const val HIT_STILL = 6
        private const val HIT_STEP = 7
        private const val HIT_SPECIAL_UNSPENT = 8
    }

    /** The desk harness's scripts (`--es script`). COUNTER lands one scripted right through an opened guard for the hit-stop test. */
    enum class Script { NONE, COUNTER }

    /** A player punch in flight. Public so the renderer can draw the glove's arc; written only here. */
    class Punch(val hand: Hand, val level: Level, var special: Boolean, val counter: Boolean, val landT: Float, val recoverT: Float) {
        /** Real seconds since the tap — the glove animation (frozen under HITSTOP). */
        var t = 0f
        var resolved = false
        var landed = false
        /** Decided at the throw: a shaking head's 25 % whiff (DESIGN.md §4.2). */
        var whiffs = false
        val total: Float get() = landT + recoverT
        /** 0..1 out over [landT], back over [recoverT] — the renderer's quadratic. */
        val k: Float get() = if (t < landT) (t / landT).coerceIn(0f, 1f) else (1f - (t - landT) / recoverT).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ the engine
    val clock = Clock()
    val boxer = Boxer()
    /** The inferred body, filled every frame by [body], read by the boxer and the renderer. */
    val body = Body()

    // ------------------------------------------------------------------ state
    @Volatile var state = State.TITLE; private set
    /** REAL seconds in this state. The plate is always on the player's clock. */
    var stateT = 0f; private set
    @Volatile var menuOpen = false; private set
    @Volatile var creditsOpen = false; private set
    var yaw = 0f; private set
    var pitch = 0f; private set
    /** False when the rotation vector is missing: the title says HEAD TRACKING REQUIRED. */
    var headOn = true; private set
    /** Wall-clock seconds since boot — the blinks and the attract run on this. */
    var t = 0f; private set
    var debugBuild = false
    /** A debug launch's constant floor (−1 = none): `--ef floor F` overrides the boxer's floor table entirely. */
    var debugFloor = -1f
    var script = Script.NONE; private set
    private var scriptT = 0f
    private var scriptStep = 0
    /** `--ei hp H`, applied at the bell AFTER the boxer's round floor (`newRound` would otherwise raise a 20 to 42 in round 3). */
    private var debugHp = 0

    // ------------------------------------------------------------------ the body's raw inputs
    var motion = 0f; private set
    var roll = 0f; private set
    var pitchG = 0f; private set
    /** The rest posture, taken at the coin, the triple-tap and every round card while still. */
    private var roll0 = 0f; private var pitch0 = 0f
    private var restPending = false
    private var stepLeft = 0f; private var stepBackLeft = 0f; private var stepDir = 0
    /** The lunge runs from wherever the body was to the full step, so a step taken mid-return never snaps. */
    private var stepFrom = 0f; private var stepTo = 0f; private var stepLanded = 0f
    var lastStep = 0; private set
    var lastStepAge = 9f; private set
    /** Body steps rejected inside a pad blank this fight (the corner says "Don't punch and run" at 3). */
    var stepsRejected = 0; private set
    private var rejectArmed = true
    private var stepTipOwed = false
    /**
     * REAL seconds the player has been still — `Clock.m` under [STILL_M] with nothing forced and
     * no menu — held (neither inflated nor reset) under a forced state or the menu, exactly as
     * `Clock.stillT` is, and for the same reason: a hit-stop, a count or a minute in the settings
     * is not the player standing still. This is what `Body.stillT` carries to the boxer's stall
     * pressure; see [STILL_M] for why it is not `Clock.still`.
     */
    var stillT = 0f; private set
    /** 0..1 of the view's sink after your knockdown. */
    private var sink = 0f
    /** The camera's pitch compensation in force (PITCH COMP), 0 ships. */
    val pitchComp: Float get() = PITCH_COMP[store.pitchComp.coerceIn(0, 2)]

    // ------------------------------------------------------------------ you
    var hp = HP_MAX; private set
    var hearts = HEARTS; private set
    /** True at zero hearts until one refills: no punches, the gloves dim, `WINDED` shows. */
    val winded: Boolean get() = hearts <= 0
    private var heartRefillT = 0f
    /** The meter's target (world events) and the shown fill (pours at 1 per 133 ms real). */
    var meter = 0; private set
    var meterShown = 0f; private set
    val meterLit: Boolean get() = meter >= METER_LIT
    /** Hits in the current unbroken sequence (nothing blocked or dodged by either side in between). */
    var chain = 0; private set
    var knockdownsYou = 0; private set
    /** Your knockdown pips this round, for the plate. */
    var knockdownsYouRound = 0; private set
    var score = 0; private set
    /** Consecutive dodges without being hit: × 1 … × 4, the rope glow and `X3` on the plate. */
    var multiplier = 1; private set
    private var dodgeStreak = 0
    var newHigh = false; private set
    var hits = 0; private set
    var perfects = 0; private set
    /** What hit you most THIS ROUND, for the corner's tip — indexed by `Boxer.Attack.ordinal`, then the [HIT_BLOCKED]… slots. */
    private val hitBy = IntArray(9)
    private var hitsUnanswered = 0
    /** The special was thrown this round: a meter still lit at the bell is otherwise the corner's `tip_special`. */
    private var specialThrownRound = false

    // ------------------------------------------------------------------ the verbs in flight
    var punch: Punch? = null; private set
    /** The 1-2's second beat, queued behind the first's active frames. */
    var queued: Hand? = null; private set
    private var queuedSpecial = false
    /** The pad's guard (right-pad swipe DOWN held, or the 0.5 s pop) and who owns the hold. */
    var guardUp = false; private set
    private var guardOwner = 0
    private var guardPopLeft = 0f
    var guardCrushedT = 0f; private set
    /** The 0.6 s real window after a PERFECT in which the next punch is a COUNTER (§2.6). */
    var counterWindow = 0f; private set
    /** True from the step's take-off to its landing: the collider is invulnerable, exactly as a hop was. */
    val stepping: Boolean get() = stepLeft > 0f
    /** The alternating-hands fallback with LEFT PAD OFF: the hand the next right-pad tap throws. */
    var nextHand = Hand.LEFT; private set
    /** The special's upgrade-in-place: the other pad's lift stamped by `MainActivity` (touch only). */
    @Volatile var lastLeftLiftMs = -10_000L
    @Volatile var lastRightLiftMs = -10_000L
    @Volatile var lastPairMs = -1L
    /** The last pad event and the last key, as text, for the lab plate (INPUT_LEFTPAD.md §5). */
    @Volatile var lastPadText = ""
    @Volatile var lastKeyText = ""
    /** A whiff past his head never reaches him: this stands in for the boxer's verdict (one instance, reused). */
    private val airOutcome = Boxer.Outcome()
    /** The punch being resolved, so a knockdown the boxer reports from inside `punch()` knows whether it was the special. */
    private var resolving: Punch? = null

    // ------------------------------------------------------------------ rounds and the count
    var round = 1; private set
    /** HIS round names, not the Rooster's — the card announces the fight you are actually in. */
    val roundName: String get() = fighter.roundNames[(round - 1).coerceIn(0, fighter.roundNames.size - 1)]
    /**
     * World seconds left on the round clock: `1:00` → `0:00`, visibly stopping when you stop. Off
     * the round — the attract and the intro run the world at 1.0 so his strips animate — it reads
     * a full minute; the card resets the clock before the bell anyway.
     */
    val roundClock: Float get() = if (state == State.TITLE || state == State.INTRO) ROUND_WORLD_S else (ROUND_WORLD_S - clock.worldT).coerceAtLeast(0f)
    /** Real seconds of this fight from the first bell — the counts and the corners included — for `REAL 0:41`, the time bonus and BEST KO. */
    var fightRealT = 0f; private set
    /** Past the 3:00 real cap the floor holds 0.35 for the rest of the round. */
    val realCapped: Boolean get() = clock.realT >= ROUND_REAL_CAP_S
    private var clapperNext = CLAPPER_FROM_S
    var downWho: Who? = null; private set
    var countN = 0; private set
    /** Real seconds since the referee began; the numerals land at 1, 2, 3 … */
    var countT = 0f; private set
    var countPopT = 0f; private set
    var riseTaps = 0; private set
    private var lastRiseHand: Hand? = null
    /** HIM: the count he rises at (0 = he does not). YOU: the alternated taps that rise you (0 = counted out for good). */
    var riseAt = 0; private set
    /** The referee has begun: the fall's SLOW has run and `Forced.COUNT` is in force. */
    private var countStarted = false
    var noDecision = false; private set
    var continueLeft = 0f; private set
    var gameOverT = 0f; private set
    var tally: List<String> = emptyList(); private set
    /** The corner's tip this rest (a line id), and the caption it raises (`CORNER` + one sentence). */
    var cornerTip = ""; private set
    var captionTag = ""; private set
    var captionLine = ""; private set
    private var captionHoldT = 0f
    /** How long the corner lasts: the forced 2.2 s, or the tip's clip plus its tail if that is longer. */
    private var cornerHold = CORNER_T
    /** The intro's lines are queued as one crawl; a skip waits for the current line's end. */
    private var introSkipWanted = false
    private var introEnded = false
    /** The bell's strokes still to ring, and the gap between them (real seconds). */
    private var bellsLeft = 0; private var bellT = 0f; private var bellGap = BELL_GAP_T
    private var koRoarT = 0f

    // ------------------------------------------------------------------ the plate's words and flashes (REAL time)
    var damageFlash = 0f; private set
    /** The right wing's stun: the plate's strokes jitter 4 px for 0.3 s. */
    var stunJitterT = 0f; private set
    /** The hit-stop kick, px, away from the punch; eases back over the stop. */
    var kickX = 0f; private set
    var kickY = 0f; private set
    /** The landing glove goes WHITE α 1.3 for two frames. */
    var gloveFlashT = 0f; private set
    var gloveFlashHand: Hand? = null; private set
    /** Impact FX at the marker: the eight stars, the onomatopoeia and its drift (0.3 s). */
    var impactT = 0f; private set
    var impactWord = ""; private set
    var impactMarker = ""; private set
    /** The special's starburst. */
    var starburstT = 0f; private set
    /**
     * The answer word (`< LEAN` …) and its life: pops 0.5 → 3.0 over 80 ms real, held on WORLD
     * time until the strike (by construction — the tell it belongs to advances on `wdt`, and the
     * word lives until `onStrikeStart`), then dropped over 120 ms real.
     */
    var answerWord = ""; private set
    var answerT = 0f; private set
    var answerDropping = false; private set
    private var answerDropT = 0f
    private val answerSeen = IntArray(Boxer.Attack.entries.size)
    /** The one feedback word at (320, 300): `PERFECT +300` · `DODGE +50` · `BLOCKED` · `WINDED` … */
    var feedback = ""; private set
    var feedbackT = 0f; private set
    /** The priority line above the fight plane: `WINDED` while you cannot punch, `COUNTER` while the window is open, else empty. */
    var priorityText = ""; private set
    /** The crowd's two-note chant while it runs (for the log and the lab; the sound is the voice bus's). */
    var chant = ""; private set
    private var chantT = 0f
    /** The crowd bed's lagged level (real), the meter's `put_him_away` latch, the KO's real time. */
    private var crowdLevel = 0f
    private var putHimAwaySaid = false
    private var koRealMs = 0
    private var warbleT = 0f
    private val rng = Random(7)

    // ------------------------------------------------------------------ settings menu (DESIGN.md §11)
    private val liveMenu = ArrayList<String>(20)
    val menuItems: List<String>
        get() {
            liveMenu.clear()
            liveMenu.add("MUSIC"); liveMenu.add("VOLUME"); liveMenu.add("VOICE")
            liveMenu.add("DIFFICULTY"); liveMenu.add("DODGE SENSE"); liveMenu.add("STEP SENSE")
            liveMenu.add("LEFT PAD"); liveMenu.add("CAPTIONS"); liveMenu.add("MOTION LAB")
            if (store.lab) {
                liveMenu.add("TIME FLOOR"); liveMenu.add("HANG"); liveMenu.add("PITCH COMP"); liveMenu.add("DRILL")
                if (debugBuild) liveMenu.add("KNEE")
            }
            liveMenu.add("CREDITS")
            liveMenu.add("RESET SETTINGS")
            if (canQuit) liveMenu.add("QUIT")
            return liveMenu
        }
    val canQuit: Boolean get() = state == State.TITLE || state == State.GAME_OVER
    var menuSel = 0; private set
    var menuTop = 0; private set
    private var resetArmed = false
    private var quitArmed = false

    fun menuValue(i: Int): String = when (menuItems.getOrNull(i)) {
        "MUSIC" -> if (store.music) "ON" else "OFF"
        "VOLUME" -> store.volume.toString()
        "VOICE" -> if (store.voice) "ON" else "OFF"
        "DIFFICULTY" -> when (store.difficulty) { 0 -> "EASY"; 1 -> "NORMAL"; else -> "HARD" }
        "DODGE SENSE" -> when (store.dodgeSense) { 0 -> "LOW"; 1 -> "MEDIUM"; else -> "HIGH" }
        "STEP SENSE" -> when (store.stepSense) { 0 -> "LOW"; 1 -> "MEDIUM"; else -> "HIGH" }
        "LEFT PAD" -> if (store.leftPad) "ON" else "OFF"
        "CAPTIONS" -> when (store.captions) { 0 -> "AUTO"; 1 -> "ON"; else -> "OFF" }
        "MOTION LAB" -> if (store.lab) "ON" else "OFF"
        // PCT and not '%': `StrokeFont` carries no per-cent glyph and drops the character in silence.
        "TIME FLOOR" -> "${(Clock.LAB_FLOORS[store.timeFloor.coerceIn(0, 4)] * 100f).toInt()} PCT"
        "HANG" -> "%.1f S".format(Locale.US, Boxer.HANG_LAB[store.hang.coerceIn(0, 2)])
        "PITCH COMP" -> "%.1f".format(Locale.US, PITCH_COMP[store.pitchComp.coerceIn(0, 2)])
        "DRILL" -> Boxer.Drill.entries[store.drill.coerceIn(0, Boxer.Drill.entries.size - 1)].name.replace('_', ' ')
        "KNEE" -> if (store.knee == 0) "A" else "B"
        "CREDITS" -> ">"
        "RESET SETTINGS" -> if (resetArmed) "TAP AGAIN TO CONFIRM" else ""
        "QUIT" -> if (quitArmed) "TAP AGAIN TO CONFIRM" else "END OF LINE"
        else -> ""
    }

    val lab: Boolean get() = store.lab
    /**
     * THE CREDITS PAGE. The music is Kevin MacLeod under CC BY 4.0 and attribution is a condition
     * of use, not a courtesy (docs/MUSIC.md), so the credit and the note of modification are here
     * verbatim in the plate's alphabet — uppercase, no `"` (the font has none; the titles stand
     * without quotes), the licence URL as the font can draw it.
     */
    val creditsLines: List<String> = listOf(
        "X3 KNOCKOUT", "", "AN ARCADE BOXING CABINET", "REBUILT FOR A PAIR OF GLASSES",
        "YOU STAND UP IN.", "",
        "MUSIC: KEVIN MACLEOD (INCOMPETECH.COM)", "LICENSED UNDER CREATIVE COMMONS:", "BY ATTRIBUTION 4.0",
        "HTTP://CREATIVECOMMONS.ORG/LICENSES/BY/4.0/", "RE-ENCODED TO OPUS FOR GAPLESS LOOPING.", "",
        "TITLE - BLUE SKA", "THE FIGHT - ROLLIN AT 5", "ROUND THREE - THE CANNERY",
        "THE COUNT - PAST THE EDGE", "THE WIN - MIGHTY AND MEEK", "",
        "VOICES AND SOUNDS ARE THE CABINET'S OWN.", "",
        "SWIPE UP OR DOWN TO SCROLL", "TAP TO GO BACK",
    )
    var creditsScroll = 0f; private set

    // ------------------------------------------------------------------ the input layer's questions (see MainActivity)
    /** The right pad's vertical gestures drive the guard on the ACTION_MOVE crossing only in a fight. */
    val holdDriveArmed: Boolean get() = state == State.FIGHT && !menuOpen && !creditsOpen
    /** Taps commit the instant the finger lifts in a fight and during your own count; off the arena the burst settles them. */
    val tapsAreUrgent: Boolean get() = (state == State.FIGHT || (state == State.KNOCKDOWN_COUNT && downWho == Who.YOU)) && !menuOpen && !creditsOpen
    /** `burstClamped` reads this when a burst opens: in a fight every burst resolves to nothing (gloves up: no menus by tapping). */
    val inFight: Boolean get() = tapsAreUrgent
    /** LEFT PAD ON: the left temple is a punch. OFF: right taps alternate hands and swipe UP is the special. */
    val leftPadEnabled: Boolean get() = store.leftPad

    /** The motion tracker is owned by the activity; the fight reads it each frame and pushes the rest posture into it. */
    var motionSrc: MotionTracker? = null

    /**
     * THE BODY WITHOUT A TRACKER — the JVM harness's way in (`TwoClocksTest`): the raw scalar,
     * roll and pitch the tracker would have produced. A live [motionSrc] overwrites these every
     * frame, so on the glasses this is inert.
     */
    fun feedBody(motion: Float, roll: Float = 0f, pitchG: Float = 0f) {
        this.motion = motion.coerceIn(0f, 1f); this.roll = roll; this.pitchG = pitchG
    }

    // ================================================================== BOOT AND SETTINGS

    fun boot() {
        // THE CARD REMEMBERS. A player who beat the Rooster last night does not have to beat him
        // again to reach the Sardine — the ladder is a record of what they can do, so it is read
        // back here. A debug launch has already set the bout by hand and disabled records, so it
        // must not be overwritten by the stored one.
        if (store.recordsEnabled) boutIndex = store.boutReached.coerceIn(0, Fighter.CARD.size - 1)
        clock.log = { Log.i(TAG, it) }
        boxer.log = { Log.i(TAG, it) }
        boxer.listener = this
        applySettings()
        enterTitle()
    }

    /**
     * Push every setting into the thing that owns it. Called at boot, at a round start and after
     * any menu adjustment, because the clock, the tracker and the boxer each keep their own copy
     * and a setting that is only read at boot is a setting the menu cannot change.
     */
    fun applySettings() {
        clock.difficulty = store.difficulty
        clock.labFloor = if (store.lab) store.timeFloor.coerceIn(0, 4) else Clock.LAB_FLOOR_OFF
        clock.knee = if (store.knee == 0) Clock.Knee.A else Clock.Knee.B
        // THE KNEE HAS TO REACH THE TRACKER, not just the clock (see Clock's class note).
        MotionTracker.W_REF = clock.wRef
        MotionTracker.GAMMA = clock.gamma
        MotionTracker.V_STEP = when (store.stepSense) { 0 -> 0.45f; 1 -> 0.32f; else -> 0.22f }
        boxer.difficulty = store.difficulty
        boxer.hangT = if (store.lab) Boxer.HANG_LAB[store.hang.coerceIn(0, 2)] else Boxer.HANG_T[store.difficulty.coerceIn(0, 2)]
        boxer.drill = if (store.lab) Boxer.Drill.entries[store.drill.coerceIn(0, Boxer.Drill.entries.size - 1)] else Boxer.Drill.OFF
    }

    /** Kept for the activity: the menu's STEP SENSE row used to call this by name. */
    fun applyStepSense() { applySettings() }

    /**
     * The desk harness (debuggable builds only; `SettingsStore.recordsEnabled` is already false):
     * `am start … --ei round N --ef floor F --ei hp H --es drill peck_l|…|all --es script counter`.
     * Straight into the round's card — no coin, no intro — with his HP set at the bell, the drill
     * on, and a constant floor if one was asked for. The rest posture is taken at the first frame
     * of play (the sensors have produced nothing by now), exactly as x3discs learned to.
     */
    fun debugStart(atRound: Int, atFloor: Float, atHp: Int, drill: String?, script: String?) {
        newFight()
        restPending = true
        debugFloor = if (atFloor >= 0f) atFloor.coerceIn(0f, 1f) else -1f
        if (drill != null) {
            val d = Boxer.Drill.entries.firstOrNull { it.name.equals(drill, ignoreCase = true) }
            if (d != null) { store.drill = d.ordinal; boxer.drill = d }
        }
        this.script = if (script.equals("counter", ignoreCase = true)) Script.COUNTER else Script.NONE
        round = atRound.coerceIn(1, ROUNDS)
        debugHp = atHp
        Log.i(TAG, "DEBUG START round=$round floor=%.2f hp=$atHp drill=${boxer.drill.name} script=${this.script.name}".format(Locale.US, debugFloor))
        enterRoundCard()
    }

    // ------------------------------------------------------------------ voice hooks (from the voice threads, via queueEvent)
    /** The corner's caption is raised on the clip's line-start, never before (DESIGN.md §8). */
    fun onVoiceLineStart(id: String) {
        if (state == State.ROUND_END && id == cornerTip) raiseCaption(id)
    }

    /** The intro's skip lands here: a tap asked for it, and the card comes at the END of the line, never mid-line. */
    fun onVoiceLineEnd(id: String) {
        if (state != State.INTRO) return
        if (id == Lines.INTRO_3) introEnded = true
        if (introSkipWanted) enterRoundCard()
        else if (introEnded && stateT >= INTRO_MIN_T) enterRoundCard()
    }

    fun onHeroLineStart(id: String) {}
    fun onHeroLineEnd(id: String) {}

    // ================================================================== INPUT (GL thread)

    /**
     * A TEMPLE TAP ON THE ARENA — the one urgent verb. LEFT is the left pad (`cyttsp6`), RIGHT
     * the right (`cyttsp5`); `MainActivity` attributes from the touch stream and never from a key
     * (a key with no touch behind it defaults to RIGHT, logged `KEY→R`). [pairMs] ≥ 0 means the
     * OTHER pad lifted that many ms ago, from two touch stamps only: inside [SPECIAL_MS] with the
     * meter lit, the punch already in flight is UPGRADED IN PLACE into the special; otherwise the
     * pair is a 1-2, the second queued behind the first's active frames. Never held back to wait
     * for a partner — a 120 ms lag on every punch is the cannon with a beat of lag.
     *
     * In your own count a tap is a RISE tap (alternate hands; a same-hand double counts once).
     * Off the arena it is ignored: the burst's [tap] carries menus and cards.
     */
    fun punch(hand: Hand, pairMs: Long = -1L) {
        if (menuOpen || creditsOpen) return
        when (state) {
            State.FIGHT -> throwPunch(hand, pairMs)
            State.KNOCKDOWN_COUNT -> if (downWho == Who.YOU) riseTap(hand)
            else -> {}
        }
    }

    /** A settled single tap OFF the arena: the coin, the skips, the continue, a menu row. */
    fun tap() {
        if (creditsOpen) { creditsOpen = false; host.sfx(Sfx.TICK); return }
        if (menuOpen) { menuActivate(); return }
        when (state) {
            State.TITLE -> coin()
            State.INTRO -> if (stateT > INTRO_SKIP_T) introSkipWanted = true
            State.ROUND_END -> if (stateT > CORNER_SKIP_T) skipCorner()
            State.KO -> if (stateT > TALLY_SKIP_T) enterTitle()
            State.GAME_OVER -> if (gameOverT > GAMEOVER_SKIP_T) { if (continueLeft > 0f) continueGame() else enterTitle() }
            State.FIGHT, State.KNOCKDOWN_COUNT -> {}   // urgent taps went through [punch]; a settled burst adds nothing
            else -> {}
        }
    }

    /**
     * SETTINGS ARE OFF THE ARENA ONLY — the same ruling the owner made for the tank game, and it
     * arrives here for the same reason with a different weapon. A tap is a PUNCH. In a fight the
     * player is tapping as fast as they can, and two of those taps inside the pad's own double-tap
     * window ARE the gesture that means "pause" — so the game could stop dead mid-exchange because
     * somebody threw a one-two. No arbitration separates them: they are the same gesture, told
     * apart only by an interval nobody in a fight is thinking about.
     *
     * [MainActivity]'s burst clamp is the first line of defence and this is the second, because the
     * clamp can only speak for taps it counted: while HE is on the canvas taps are not urgent, the
     * burst is unclamped, and a right double-tap walked straight into the menu. A rule enforced in
     * one place is a rule with a hole in it — so the menu is simply not on offer unless the fight
     * is over ([canQuit]: the title and the game-over card, where there is nothing to punch and
     * everything the menu holds is a between-fights decision anyway).
     */
    fun doubleTap() {
        if (creditsOpen) { creditsOpen = false; closeMenu(); return }
        if (menuOpen) { closeMenu(); return }
        if (canQuit) openMenu() else host.sfx(Sfx.GUARD_THUD, 0.7f, 0.3f)
    }

    fun tripleTap() {
        host.recentreHead(); roll0 = roll; pitch0 = pitchG; pushRest(); host.sfx(Sfx.TICK)
    }

    private fun pushRest() { motionSrc?.restRoll = roll0; motionSrc?.restPitch = pitch0 }

    /** `INSERT COIN`: forward and the horizon are re-declared, the rest posture taken, the fight begins. */
    private fun coin() {
        host.recentreHead(); roll0 = roll; pitch0 = pitchG; pushRest()
        startGame()
    }

    /** The pad's settled directions: menus navigate; in a fight L/R = STEP, DOWN = the guard's flick, UP = [swipeUp]. */
    fun swipe(dir: Swipe) {
        if (creditsOpen) {
            val maxScroll = (creditsLines.size - CREDITS_VISIBLE).coerceAtLeast(0) * CREDITS_ROW_PX
            when (dir) {
                Swipe.UP, Swipe.FORWARD -> creditsScroll = max(0f, creditsScroll - CREDITS_STEP_ROWS * CREDITS_ROW_PX)
                Swipe.DOWN, Swipe.BACK -> creditsScroll = min(maxScroll, creditsScroll + CREDITS_STEP_ROWS * CREDITS_ROW_PX)
                else -> {}
            }
            host.sfx(Sfx.TICK)
            return
        }
        if (menuOpen) {
            when (dir) {
                Swipe.UP, Swipe.FORWARD -> { menuSel = (menuSel - 1 + menuItems.size) % menuItems.size; disarm(); host.sfx(Sfx.TICK) }
                Swipe.DOWN, Swipe.BACK -> { menuSel = (menuSel + 1) % menuItems.size; disarm(); host.sfx(Sfx.TICK) }
                Swipe.LEFT -> adjust(-1)
                Swipe.RIGHT -> adjust(1)
            }
            scrollMenu()
            return
        }
        if (state != State.FIGHT) return
        when (dir) {
            Swipe.UP, Swipe.FORWARD -> swipeUp()
            Swipe.DOWN, Swipe.BACK -> guardFlick()
            Swipe.LEFT -> step(-1, "PAD")
            Swipe.RIGHT -> step(1, "PAD")
        }
    }

    /**
     * Swipe UP, the one-shot (the old recall path): THE SPECIAL BY PAD with `LEFT PAD OFF`,
     * otherwise a soft click that still spends its quantum (DESIGN.md §1.2).
     */
    fun swipeUp() {
        if (state != State.FIGHT || menuOpen) return
        if (!store.leftPad) { special(); return }
        clock.pulse(Clock.Verb.EMPTY)
        host.sfx(Sfx.TICK, 0.8f, 0.45f)
    }

    /** Swipe DOWN crossed on the right pad: gloves up until the finger lifts (was `deflectorStart`). */
    fun guardStart(source: Int) {
        if (state != State.FIGHT || menuOpen) return
        if (guardCrushedT > 0f) { host.sfx(Sfx.BUMP, 1.4f, 0.4f); return }
        guardOwner = source; guardUp = true; guardPopLeft = 0f
        clock.pulse(Clock.Verb.GUARD)
        host.sfx(Sfx.TICK, 1.2f, 0.5f)
        Log.i(TAG, "GUARD open src=$source")
        ev("GUARD up src=$source")
    }

    /** A DOWN that only the finger-lift saw: a 0.5 s WORLD pop with no owner. */
    fun guardFlick() {
        if (state != State.FIGHT || menuOpen) return
        if (guardCrushedT > 0f) { host.sfx(Sfx.BUMP, 1.4f, 0.4f); return }
        guardOwner = 0; guardUp = true; guardPopLeft = GUARD_POP_T
        clock.pulse(Clock.Verb.GUARD)
        Log.i(TAG, "GUARD pop")
        ev("GUARD pop")
    }

    /** The finger lifted (was `deflectorEnd`). A pop keeps what is left of its 0.5 s. */
    fun guardEnd(source: Int) {
        if (guardOwner != source) return
        guardOwner = 0
        if (guardPopLeft <= 0f) { guardUp = false; Log.i(TAG, "GUARD close"); ev("GUARD down") }
    }

    /**
     * Both pads inside [SPECIAL_MS] (or swipe UP with LEFT PAD OFF): the Wake-Up Call if the meter
     * is lit, else nothing extra. A punch already in flight and still inside the jab's wind-up
     * ([JAB_LAND_T]) is upgraded IN PLACE — a new [Punch] with the special's timing, carrying the
     * old one's elapsed time, so the conversion lands before any contact frame and is invisible
     * (DESIGN.md §1.4); its forced window becomes the special's 0.60 s. Later than that the special
     * is thrown fresh from the right hand (or queued, as the special, behind a punch in its active
     * frames). The meter is spent when the special RESOLVES, not here: see [resolvePunch].
     */
    fun special() {
        if (state != State.FIGHT || menuOpen) return
        val lit = meterLit
        val p = punch
        val how = when {
            !lit -> "DARK"
            winded -> "WINDED"
            p != null && !p.resolved && p.t < JAB_LAND_T -> "UPGRADED"
            p != null && !p.resolved -> "QUEUED"
            else -> "THROWN"
        }
        Log.i(TAG, "SPECIAL lit=$lit result=$how")
        if (!lit || winded) return
        when (how) {
            "UPGRADED" -> {
                val old = p!!
                val u = Punch(old.hand, old.level, true, old.counter, SPECIAL_LAND_T, SPECIAL_RECOVER_T)
                u.t = old.t
                punch = u
                if (!old.counter) clock.forcePunch(Clock.PUNCH_SPECIAL_T)
                specialThrownRound = true
                host.sfx(Sfx.SPECIAL, 1f, 0.8f)
                ev("PUNCH UPGRADED ${old.hand.name} SPECIAL at=%.3f".format(Locale.US, old.t))
            }
            "QUEUED" -> { queued = Hand.RIGHT; queuedSpecial = true }
            else -> throwPunch(Hand.RIGHT, -1L, forceSpecial = true)
        }
    }

    // ================================================================== THE PUNCH MACHINE (DESIGN.md §2.3, §4.2)

    /**
     * Throw a punch NOW. With `LEFT PAD OFF` the hand alternates by rhythm whatever pad the tap
     * came from; the special and the desk script name their hand. A punch in its active frames,
     * or in the recovery of a WHIFF (the mash tax is not cancellable), queues the next one; a
     * LANDED punch's recovery is cancellable into the next punch (§2.3). The COUNTER — the next
     * punch inside the perfect window — spends the window and forces NOTHING: the world stays at
     * the floor while you throw it, the bullet-time hit the whole design is selling.
     */
    private fun throwPunch(hand: Hand, pairMs: Long, forceSpecial: Boolean = false, levelOverride: Level? = null, forceCounter: Boolean = false) {
        val h = if (store.leftPad || forceSpecial || levelOverride != null) hand
                else nextHand.also { nextHand = if (it == Hand.LEFT) Hand.RIGHT else Hand.LEFT }
        // every two-handed pair is logged whatever becomes of it: the p90 of this is SPECIAL_MS (TEST.md L4)
        if (pairMs >= 0L) { lastPairMs = pairMs; Log.i(TAG, "PAIR ms=$pairMs") }
        if (winded) {
            say("WINDED"); host.sfx(Sfx.TICK, 0.6f, 0.4f); clock.pulse(Clock.Verb.EMPTY)
            Log.i(TAG, "PUNCH hand=${h.name.first()} level=- result=WINDED dmg=0 hp=${boxer.hp} meter=$meter hearts=$hearts forced=${clock.forced}")
            return
        }
        if (pairMs in 0..SPECIAL_MS && meterLit) { special(); return }
        val inFlight = punch
        if (inFlight != null && (!inFlight.resolved || !inFlight.landed)) {
            queued = h; queuedSpecial = forceSpecial
            ev("PUNCH QUEUED ${h.name}")
            return
        }
        val counter = forceCounter || counterWindow > 0f
        val level = levelOverride ?: if (body.aimLow) Level.BODY else Level.HEAD
        val p = when {
            forceSpecial -> Punch(h, level, true, counter, SPECIAL_LAND_T, SPECIAL_RECOVER_T)
            h == Hand.LEFT -> Punch(h, level, false, counter, JAB_LAND_T, JAB_RECOVER_T)
            else -> Punch(h, level, false, counter, CROSS_LAND_T, CROSS_RECOVER_T)
        }
        p.whiffs = !forceSpecial && !counter && body.moving > WHIFF_M && rng.nextFloat() < WHIFF_CHANCE
        punch = p
        if (counter) {
            // THE COUNTER'S WINDOW IS WAIVED, and the window is spent: one counter per PERFECT.
            counterWindow = 0f
        } else when {
            forceSpecial -> clock.forcePunch(Clock.PUNCH_SPECIAL_T)
            h == Hand.LEFT -> clock.forcePunch(Clock.PUNCH_JAB_T, Clock.Verb.JAB)
            else -> clock.forcePunch(Clock.PUNCH_HOOK_T, Clock.Verb.HOOK)
        }
        if (forceSpecial) specialThrownRound = true
        host.sfx(if (forceSpecial) Sfx.SPECIAL else Sfx.JAB_WHOOSH, if (h == Hand.LEFT) 1.1f else 0.95f, 0.6f)
        heartRefillT = 0f
        ev("PUNCH THROWN ${h.name} ${level.name}${if (forceSpecial) " SPECIAL" else ""}${if (counter) " COUNTER" else ""}${if (p.whiffs) " WHIFFS" else ""}")
    }

    /**
     * The punch reached its contact frame: ask the boxer, then spend or earn (DESIGN.md §2.3,
     * §4.2–4.5, §5.5). A whiff past his head (decided at the throw) never reaches him at all.
     *
     * LANDED → the forced window is CUT to the active frames (landing is cheaper than missing) —
     * unless his own STRIKE is in flight, in which case the strike keeps its remaining real time
     * rather than being dropped with the punch window that had replaced it in the clock's one
     * timed slot — then the hit-stop by punch kind, the white glove, the stars and the word, the
     * scene's kick, the meter (+2 the first hit of a sequence, +5 each after, +10 a counter), the
     * score with the multiplier, the announcer's call, the crowd's OH (from the boxer's hit
     * reaction). The special LANDING spends the meter to the seed; a special into his CLOSED
     * guard does no damage but knocks the guard open (his `opened`) and is spent all the same —
     * the meter is a rhythm gate, not an ammo store.
     *
     * HIS GUARD / AIR → a heart, the window EXTENDED by the whiff tax, the meter −1 (never once
     * lit) / −2, the sequence broken, `BLOCKED` / `WHIFF`, and `WINDED` if that was the last
     * heart. The drill's "no damage either way" is about HP: a whiff costs its heart there too,
     * because the heart is the punch budget and the mash tax is the lesson TEST.md T6 reads.
     *
     * A KNOCKDOWN can arrive by either of two roads and is counted once: the boxer may call
     * [onKnockdown] from inside `punch()` (this method is then already on the canvas when it reads
     * the outcome), or he may only flag `Outcome.knockdown` — in which case the flow is started
     * here. Whichever road, [resolving] lets the knockdown know it was the special that put him
     * there, for the announcer's `sleep`.
     */
    private fun resolvePunch(p: Punch) {
        p.resolved = true
        val base = when {
            p.special -> SPECIAL_DMG
            p.hand == Hand.LEFT -> if (p.level == Level.BODY) JAB_DMG_BODY else JAB_DMG_HEAD
            else -> if (p.level == Level.BODY) CROSS_DMG_BODY else CROSS_DMG_HEAD
        }
        resolving = p
        val o = if (p.whiffs) airOutcome.also { it.clear(); it.result = PunchResult.AIR }
                else boxer.punch(p.hand, p.level, base, p.counter, p.special)
        resolving = null
        val landed = when (o.result) { PunchResult.LAND, PunchResult.COUNTER, PunchResult.STAGGER, PunchResult.KNOCKDOWN -> true; else -> false }
        val counter = p.counter || o.result == PunchResult.COUNTER
        if (landed) {
            p.landed = true; hits++; hitsUnanswered = 0
            // THE METER (§4.5): the first hit of a sequence, every hit after, a counter
            val add = when { counter -> METER_COUNTER; chain == 0 -> METER_FIRST; else -> METER_CHAIN }
            chain++
            meter = (meter + add).coerceAtMost(METER_MAX)
            // THE CLOCK: landing is cheaper than missing — the punch's window is cut to its active
            // frames whatever he was doing; then the impact frame. His glove, if it was in flight,
            // goes on travelling on WORLD time (the owner's ruling, BOXER.md's header): the first
            // draft re-forced his strike here for its remainder, which made "yours lands first" a
            // bet nobody could win.
            cutPunchWindow()
            clock.forceHitstop(when {
                p.special -> Clock.HITSTOP_SPECIAL_MS
                counter -> Clock.HITSTOP_COUNTER_MS
                p.hand == Hand.RIGHT -> Clock.HITSTOP_HOOK_MS
                else -> Clock.HITSTOP_JAB_MS
            })
            // THE PICTURE: the white glove, the stars and the word at the marker, the scene's kick
            gloveFlashT = 2f / 60f; gloveFlashHand = p.hand
            impactT = IMPACT_T; impactMarker = if (p.level == Level.BODY) "body" else "chin"
            impactWord = when {
                p.special -> WORD_SPECIAL
                counter -> WORD_COUNTER
                p.level == Level.BODY -> WORD_BODY
                p.hand == Hand.LEFT -> WORD_JAB
                else -> WORD_CROSS
            }
            kickX = if (p.hand == Hand.LEFT) HIT_KICK_PX else -HIT_KICK_PX
            kickY = if (p.level == Level.BODY) HIT_KICK_PX * 0.5f else -HIT_KICK_PX * 0.3f
            if (p.special) starburstT = STARBURST_T
            // THE SCORE (§5.5): the hit, × 2 in stagger, + the counter, or the special's 1000
            var pts = if (p.special) SCORE_SPECIAL
                      else (if (p.level == Level.BODY) SCORE_BODY else SCORE_HEAD) * (if (o.result == PunchResult.STAGGER) 2 else 1)
            if (counter) pts += SCORE_COUNTER
            credit(pts, when {
                p.special -> "SPECIAL"
                counter -> "COUNTER"
                o.result == PunchResult.STAGGER -> if (p.level == Level.BODY) "STAGGER BODY" else "STAGGER HEAD"
                p.level == Level.BODY -> "BODY"
                else -> "HEAD"
            })
            if (p.special) { say("SPECIAL +1000"); meter = METER_SEED }
            else if (counter) say("COUNTER +300")
            // THE SOUND AND THE CALL: the announcer names every landed punch, and his are the first lines dropped
            host.sfx(if (p.level == Level.BODY) Sfx.HIT_BODY else Sfx.HIT_HEAD, if (p.special) 0.8f else 1f, if (p.special || counter) 1f else 0.85f)
            if (p.special) host.sfx(Sfx.SPECIAL, 1.2f, 0.8f)
            host.say(when {
                counter -> Lines.COUNTER
                p.level == Level.BODY -> Lines.BODY_BLOW
                p.hand == Hand.LEFT -> Lines.LEFT
                else -> Lines.RIGHT
            }, false, 300L)
            if (o.interrupted) { answerWord = ""; answerDropping = false; ev("TELL INTERRUPTED${if (o.stunned) " STUN" else ""}") }
            if (o.knockdown && state == State.FIGHT) {
                val n = boxer.knockdownsFight.coerceAtLeast(1)
                hisKnockdown(n, if (boxer.riseAt > 0) boxer.riseAt else Boxer.RISE_AT[(n - 1).coerceIn(0, 2)], o.ko, p.special)
            }
        } else if (o.result == PunchResult.GUARD || o.result == PunchResult.AIR) {
            chain = 0
            if (p.special) {
                meter = METER_SEED; starburstT = STARBURST_T * 0.5f
                host.sfx(Sfx.BLOCKED, 0.9f, 0.9f); say(if (o.opened) "GUARD OPEN" else "BLOCKED")
            } else {
                hearts = (hearts - 1).coerceAtLeast(0)
                extendPunchWindow()
                if (o.result == PunchResult.GUARD) {
                    if (!meterLit) meter = (meter + METER_BLOCKED).coerceAtLeast(0)
                    hitBy[HIT_BLOCKED]++
                    say("BLOCKED"); host.sfx(Sfx.BLOCKED)
                } else {
                    meter = (meter + METER_DODGED).coerceAtLeast(0)
                    say("WHIFF"); host.sfx(Sfx.WHIFF)
                }
                if (winded) { say("WINDED"); ev("WINDED") }
            }
        }
        Log.i(TAG, "PUNCH hand=${p.hand.name.first()} level=${p.level} result=${o.result} dmg=${o.dmg} hp=${boxer.hp} meter=$meter hearts=$hearts forced=${clock.forced}")
        ev("PUNCH ${p.hand.name} ${p.level.name} ${o.result.name}${if (p.special) " SPECIAL" else ""}${if (counter) " COUNTER" else ""} dmg=${o.dmg} his=${boxer.hp}")
    }

    /** A landed punch is cut to its active frames — only when the punch's own window is the one running. */
    private fun cutPunchWindow() { if (clock.timedState == Clock.Forced.PUNCH) clock.cutForced(0f) }
    /** A whiff is taxed — only when the punch's own window is the one running (a waived counter has none). */
    private fun extendPunchWindow() { if (clock.timedState == Clock.Forced.PUNCH) clock.extendForced(Clock.WHIFF_EXTEND_T) }

    /**
     * A rise tap in your own count: alternate hands, a same-hand double counts once. With `LEFT
     * PAD OFF` every tap is a right, so the hands alternate by rhythm there too. Taps land from
     * the fall onward but the rise waits for the referee to have begun — rising inside the 1.1 s
     * fall would cut the story beat short and snap the view up.
     */
    private fun riseTap(hand: Hand) {
        if (riseAt <= 0) { host.sfx(Sfx.TICK, 0.5f, 0.3f); return }   // counted out for good: the taps are a heartbeat, nothing more
        val h = if (store.leftPad) hand else (if (lastRiseHand == Hand.LEFT) Hand.RIGHT else Hand.LEFT)
        if (lastRiseHand == h) return
        lastRiseHand = h; riseTaps++
        host.sfx(Sfx.TICK, 1f + 0.05f * riseTaps, 0.6f)
        if (countStarted && riseTaps >= riseAt) rise()
    }

    /** A STEP by pad or body: `Forced.STEP` at rate 1.0, the collider invulnerable for the flight, exposed on landing. */
    private fun step(dir: Int, how: String) {
        if (state != State.FIGHT || stepping) return
        stepDir = dir; stepLeft = clock.stepT; stepBackLeft = 0f
        stepFrom = body.bodyX; stepTo = dir * STEP_DX
        lastStep = dir; lastStepAge = 0f
        clock.forceStep()
        host.sfx(Sfx.THRUST, 1.2f, 0.6f)
        ev("STEP ${if (dir < 0) "L" else "R"} $how")
    }

    // ================================================================== THE MENU

    private fun openMenu() { menuOpen = true; menuSel = 0; menuTop = 0; disarm(); host.sfx(Sfx.SELECT); Log.i(TAG, "MENU open") }
    private fun closeMenu() { menuOpen = false; creditsOpen = false; disarm(); host.sfx(Sfx.TICK); applySettings(); Log.i(TAG, "MENU close") }
    private fun disarm() { resetArmed = false; quitArmed = false }

    /** Nine rows at a time, the window following the selection. */
    private fun scrollMenu() {
        val n = menuItems.size
        if (n <= 9) { menuTop = 0; return }
        if (menuSel < menuTop) menuTop = menuSel
        if (menuSel > menuTop + 8) menuTop = menuSel - 8
        menuTop = menuTop.coerceIn(0, n - 9)
    }

    private fun adjust(d: Int) {
        when (menuItems.getOrNull(menuSel)) {
            "MUSIC" -> { store.music = !store.music; host.musicEnabled(store.music) }
            "VOLUME" -> { store.volume = store.volume + d; host.applyVolume(store.volume) }
            "VOICE" -> { store.voice = !store.voice; host.voiceEnabled(store.voice) }
            "DIFFICULTY" -> store.difficulty = (store.difficulty + d + 3) % 3
            "DODGE SENSE" -> store.dodgeSense = (store.dodgeSense + d + 3) % 3
            "STEP SENSE" -> store.stepSense = (store.stepSense + d + 3) % 3
            "LEFT PAD" -> store.leftPad = !store.leftPad
            "CAPTIONS" -> store.captions = (store.captions + d + 3) % 3
            "MOTION LAB" -> store.lab = !store.lab
            "TIME FLOOR" -> store.timeFloor = (store.timeFloor + d + 5) % 5
            "HANG" -> store.hang = (store.hang + d + 3) % 3
            "PITCH COMP" -> store.pitchComp = (store.pitchComp + d + 3) % 3
            "DRILL" -> { val n = Boxer.Drill.entries.size; store.drill = (store.drill + d + n) % n }
            "KNEE" -> store.knee = 1 - store.knee
            "CREDITS" -> { creditsOpen = true; creditsScroll = 0f }
            "RESET SETTINGS", "QUIT" -> menuActivate()
        }
        applySettings()
        menuSel = menuSel.coerceIn(0, menuItems.size - 1)
        scrollMenu()
        host.sfx(Sfx.TICK)
    }

    private fun menuActivate() {
        when (menuItems.getOrNull(menuSel)) {
            "RESET SETTINGS" -> if (!resetArmed) { resetArmed = true; host.sfx(Sfx.TICK) } else {
                store.resetSettings(); resetArmed = false
                host.musicEnabled(store.music); host.voiceEnabled(store.voice); host.applyVolume(store.volume)
                applySettings(); host.sfx(Sfx.SELECT)
            }
            "QUIT" -> if (!quitArmed) { quitArmed = true; host.sfx(Sfx.TICK) } else {
                host.sfx(Sfx.SELECT); host.say(Lines.END_OF_LINE, urgent = true); host.quitGame()
            }
            else -> adjust(1)
        }
    }

    // ================================================================== FLOW

    private fun enterTitle() {
        state = State.TITLE; stateT = 0f; menuOpen = false; creditsOpen = false
        downWho = null; countStarted = false; koRoarT = 0f
        clearVerbs()
        clock.clearForced(); clock.floorOverride = -1f
        boxer.idle()
        host.crowd(0f, 1f)
        host.music(Music.TITLE)
    }

    /** Everything a fight owns, before the first card. Shared by the coin, the continue and the harness. */
    /**
     * WHERE ON THE CARD WE ARE — an index into [Fighter.CARD], 0 = the Rooster.
     *
     * A KO advances it and the next coin meets the next man; a LOSS does not, so a continue is
     * always a rematch with the boxer who just beat you and never a promotion. That is the arcade's
     * own rule and it is the one that makes the ladder mean something: the card is a record of what
     * you can actually beat, not of how many coins you fed it.
     */
    var boutIndex = 0; private set
    val fighter: Fighter get() = Fighter.at(boutIndex)

    /** Put a specific fighter up — the debug launch (`--ei bout N`) and the tests. */
    fun setBout(i: Int) { boutIndex = i.coerceIn(0, Fighter.CARD.size - 1) }

    private fun newFight() {
        score = 0; multiplier = 1; dodgeStreak = 0; newHigh = false; hits = 0; perfects = 0
        hp = HP_MAX; hearts = HEARTS; heartRefillT = 0f; meter = 0; meterShown = 0f; chain = 0
        knockdownsYou = 0; knockdownsYouRound = 0; noDecision = false; fightRealT = 0f; koRealMs = 0
        stepsRejected = 0; rejectArmed = true; stepTipOwed = false; hitBy.fill(0); hitsUnanswered = 0; answerSeen.fill(0)
        putHimAwaySaid = false; nextHand = Hand.LEFT; specialThrownRound = false
        stillT = 0f; sink = 0f; koRoarT = 0f; countStarted = false; downWho = null; chant = ""; chantT = 0f
        debugHp = 0; scriptT = 0f; scriptStep = 0
        tally = emptyList()
        clearVerbs()
        round = 1
        applySettings()
        boxer.fighter = fighter
        boxer.newFight(seed = (t * 60f).toInt(), difficulty = store.difficulty)
        Log.i(TAG, "BOUT ${boutIndex + 1}/${Fighter.CARD.size} ${fighter.name} hp=${fighter.hp[store.difficulty.coerceIn(0, 2)]} " +
            "tell=x${fighter.tellMul} dmg=x${fighter.dmgMul} hang=x${fighter.hangMul} gimmick=${fighter.gimmick}")
    }

    /** The verbs and the words that belong to a round, dropped between states. */
    private fun clearVerbs() {
        punch = null; queued = null; queuedSpecial = false
        guardUp = false; guardOwner = 0; guardPopLeft = 0f; guardCrushedT = 0f; counterWindow = 0f
        stepLeft = 0f; stepBackLeft = 0f
        answerWord = ""; answerDropping = false; answerDropT = 0f; answerT = 0f
        resolving = null
    }

    private fun startGame() {
        newFight()
        store.fights = store.fights + 1
        host.sfx(Sfx.START)
        enterIntro()
    }

    /** A coin buys the fight back and nothing else: round 1, score 0, straight to the card (DESIGN.md §5.2). */
    private fun continueGame() {
        Log.i(TAG, "CONTINUE")
        newFight()
        enterRoundCard()
    }

    private fun enterIntro() {
        state = State.INTRO; stateT = 0f; introSkipWanted = false; introEnded = false
        boxer.taunt()
        host.sayAll(listOf(Lines.INTRO_1, Lines.INTRO_2, Lines.INTRO_3))
    }

    /** The intro ends at a line's end ([onVoiceLineEnd]); this is its floor and its ceiling, and the skip when the bus is silent. */
    private fun updateIntro() {
        val silent = !store.voice
        when {
            introSkipWanted && (silent || !host.voiceBusy()) -> enterRoundCard()
            (introEnded || silent) && stateT >= INTRO_MIN_T -> enterRoundCard()
            stateT >= INTRO_MAX_T -> enterRoundCard()
        }
    }

    private fun enterRoundCard() {
        val prevWorld = clock.worldT; val prevReal = clock.realT
        state = State.ROUND_CARD; stateT = 0f
        clock.resetRound(); clapperNext = CLAPPER_FROM_S
        knockdownsYouRound = 0; downWho = null; countStarted = false
        captionTag = ""; captionLine = ""; captionHoldT = 0f
        introSkipWanted = false
        host.stopVoice()
        // FORWARD IS RE-DECLARED, YAW ONLY; the rest posture is re-declared by [update] while the head is still.
        host.recentreYaw()
        host.sfx(Sfx.WAVE)
        host.music(fightTrack())
        boxer.idle()
        Log.i(TAG, "ROUND $round world=%.1f real=%.1f".format(Locale.US, prevWorld, prevReal))
    }

    private fun fightTrack(): String = if (round >= ROUNDS) Music.FIGHT3 else Music.FIGHT

    private fun enterFight() {
        state = State.FIGHT; stateT = 0f
        if (restPending) { restPending = false; roll0 = roll; pitch0 = pitchG; pushRest() }
        clock.resetRound()
        boxer.newRound(round)
        if (debugHp > 0) { boxer.setHp(debugHp); debugHp = 0 }
        hearts = HEARTS; heartRefillT = 0f
        specialThrownRound = false; hitBy.fill(0); hitsUnanswered = 0
        // a player who read the card without moving was not stalling: the stall's clock starts at the bell
        stillT = 0f
        clearVerbs()
        scriptT = 0f; scriptStep = 0
        bells(3, BELL_GAP_T)
        host.say(Lines.FIGHT, urgent = true)
        Log.i(TAG, "BELL round=$round")
        ev("BELL round=$round")
    }

    /** The bell ends the round: the corner, or `TIME - NO DECISION` after round 3. */
    private fun endRound() {
        Log.i(TAG, "BELL round=$round end world=%.1f real=%.1f".format(Locale.US, clock.worldT, clock.realT))
        clearVerbs()
        if (round >= ROUNDS) {
            noDecision = true
            boxer.win()
            host.sfx(Sfx.BELL); host.say(Lines.NO_DECISION, urgent = true)
            Log.i(TAG, "NO DECISION"); ev("NO DECISION")
            gameOver()
            return
        }
        state = State.ROUND_END; stateT = 0f
        clock.clearForced(); clock.forceCorner()
        hp = (hp + CORNER_HEAL).coerceAtMost(HP_MAX); hearts = HEARTS; heartRefillT = 0f
        if (meterLit && !specialThrownRound) hitBy[HIT_SPECIAL_UNSPENT]++
        cornerTip = tipFor()
        cornerHold = CORNER_T
        if (cornerTip.isNotEmpty()) {
            cornerHold = max(CORNER_T, host.voiceDurationMs(cornerTip) / 1000f + CAPTION_TAIL_T + 0.3f)
            // the tip is spoken and captioned on its line-start; with the voice off the caption is all there is
            if (store.voice) host.say(cornerTip, false, 3000L) else raiseCaption(cornerTip)
        }
        boxer.idle()
        host.sfx(Sfx.BELL)
        Log.i(TAG, "CORNER tip=${cornerTip.ifEmpty { "-" }}")
    }

    private fun skipCorner() {
        host.stopVoice()
        captionTag = ""; captionLine = ""; captionHoldT = 0f
        round++
        enterRoundCard()
    }

    private fun raiseCaption(id: String) {
        val line = TIP_CAPTIONS[id] ?: return
        captionTag = "CORNER"; captionLine = line
        captionHoldT = max(CORNER_T, host.voiceDurationMs(id) / 1000f + CAPTION_TAIL_T)
    }

    /**
     * The corner's one sentence (DESIGN.md §10): what hit you most this round first; then, if
     * nothing did, the punches you wasted on his gloves, the stalls the crowd booed, the body
     * steps a flurry blanked (once a fight), a lit meter you never spent.
     */
    private fun tipFor(): String {
        var best = -1; var bestN = 0
        for (i in 0 until Boxer.Attack.entries.size) if (hitBy[i] > bestN) { bestN = hitBy[i]; best = i }
        if (best >= 0) return Boxer.Attack.entries[best].tip
        if (hitBy[HIT_BLOCKED] >= 3) return Lines.TIP_GUARD
        if (hitBy[HIT_STILL] >= 1) return Lines.TIP_STILL
        if (stepTipOwed) { stepTipOwed = false; return Lines.TIP_STEP }
        if (hitBy[HIT_SPECIAL_UNSPENT] >= 1) return Lines.TIP_SPECIAL
        return ""
    }

    /**
     * THE WIN: `KNOCKOUT`, the three fast bells, the roar, the announcer, the tally — and the only
     * place REAL time is judged: `10 000 × max(0, 1 − realSeconds / 180)`. The KO's slow-motion
     * (`Forced.SLOW` 0.10 for 2 s) runs his last collapse; the tally lands on the plate's own
     * clock. The score is the run's total × the difficulty's multiplier, and the records are
     * written here (the store refuses them from a debug launch).
     */
    private fun knockout() {
        val prevMult = multiplier
        state = State.KO; stateT = 0f; downWho = null
        koRealMs = (fightRealT * 1000f).toInt()
        val bonus = (TIME_BONUS_MAX * max(0f, 1f - fightRealT / TIME_BONUS_S)).toInt()
        credit(SCORE_KO, "KO", mult = false)
        credit(bonus, "TIME BONUS", mult = false)
        score = (score * DIFF_MULT[store.difficulty.coerceIn(0, 2)]).toInt()
        newHigh = score > store.highScore && score > 0
        store.highScore = score; store.bestKoMs = koRealMs; store.champion = true; store.knockdownScored = true
        // UP THE CARD. The next coin meets the next man; beating the last one leaves the ladder
        // where it is, so the champion can be fought again rather than the card silently wrapping
        // round to the Rooster and making the achievement disappear.
        if (boutIndex < Fighter.CARD.size - 1) {
            boutIndex++
            store.boutReached = boutIndex
            Log.i(TAG, "CARD advanced to ${boutIndex + 1}/${Fighter.CARD.size} ${Fighter.at(boutIndex).name}")
        }
        tally = listOf("TIME ${clockText(fightRealT)}", "HITS $hits", "PERFECTS $perfects", "KNOCKDOWNS ${boxer.knockdownsFight}", "TIME BONUS $bonus", "SCORE $score")
        clearVerbs()
        clock.clearForced(); clock.forceSlow(Clock.SLOW_KO_RATE, Clock.SLOW_KO_T)
        bells(3, BELL_GAP_KO_T)
        host.say(Lines.WINNER_KO, urgent = true)
        host.music(Music.WIN)
        koRoarT = KO_ROAR_T
        Log.i(TAG, "KO real=%.1f score=%d bonus=%d mult=x%d newHigh=%s".format(Locale.US, fightRealT, score, bonus, prevMult, newHigh))
        ev("KO")
    }

    private fun gameOver() {
        state = State.GAME_OVER; stateT = 0f; gameOverT = 0f; downWho = null
        continueLeft = CONTINUE_S
        score = (score * DIFF_MULT[store.difficulty.coerceIn(0, 2)]).toInt()
        newHigh = score > store.highScore && score > 0
        store.highScore = score
        clearVerbs()
        clock.clearForced()
        if (!noDecision) boxer.taunt()   // he stands over you; after a decision he is already bouncing (`win`)
        host.sfx(if (newHigh) Sfx.HISCORE else Sfx.GAMEOVER)
        host.music(Music.TITLE)
        Log.i(TAG, "GAME OVER score=$score newHigh=$newHigh noDecision=$noDecision")
        ev("GAME OVER")
    }

    /** You rose before ten: HP 40, hearts 3, meter 0, back to the fight. */
    private fun rise() {
        hp = RISE_HP; hearts = HEARTS; meter = 0; meterShown = 0f; heartRefillT = 0f
        clock.clearForced()
        state = State.FIGHT; stateT = 0f; downWho = null
        host.sfx(Sfx.ROPES, 1.2f, 0.5f)
        host.music(fightTrack())
        Log.i(TAG, "RISE at=$countN who=YOU taps=$riseTaps")
        ev("RISE YOU at=$countN")
    }

    /** He beat the count (his own rise, or the numeral he rises at): the fight resumes. */
    private fun endCount() {
        clock.clearForced()
        state = State.FIGHT; stateT = 0f; downWho = null
        if (boxer.down) boxer.idle()
        host.music(fightTrack())
        Log.i(TAG, "RISE at=$countN who=HIM")
        ev("RISE HIM at=$countN")
    }

    /** Ring [n] strokes, the first on the next frame, then one per [gap] real seconds. */
    private fun bells(n: Int, gap: Float) { bellsLeft = n; bellT = 0f; bellGap = gap }

    // ================================================================== THE FRAME

    /**
     * One frame. `dt` is real seconds, already clamped to 0.05 by the renderer.
     *
     * The order is the two-clock audit in miniature: the tracker's scalar exists before the clock
     * maps it; the boxer's floor is written before the clock runs; the clock exists before anything
     * reads `wdt`; the plate's flashes, the meter's pour, the bells and the crowd tick on `dt`
     * whatever the state — menu or no menu; the body is inferred before the boxer tests the
     * collider; the boxer runs last, on the numbers the body just produced.
     */
    fun update(dt: Float, headYaw: Float, headPitch: Float, headOn: Boolean) {
        t += dt; stateT += dt
        this.headOn = headOn
        if (headOn) { yaw = headYaw; pitch = headPitch }
        motionSrc?.let { motion = it.motion; roll = it.roll; pitchG = it.pitchG }

        // THE FLOOR IS THE BOXER'S STATE — written before the law runs, every frame (DESIGN.md §2.2)
        clock.floorOverride = floorFor()
        clock.menuOpen = menuOpen || creditsOpen
        val m = motionSrc
        clock.update(dt, motion, m?.angSpeed ?: 0f, m?.step ?: 0, m?.blankLeft ?: 0L)

        // everything on the plate is on the player's clock, menu or no menu
        plate(dt)
        if (menuOpen || creditsOpen) return

        when (state) {
            State.TITLE -> { body(dt); boxer.update(clock.wdt, dt, body) }
            State.INTRO -> { body(dt); boxer.update(clock.wdt, dt, body); updateIntro() }
            State.ROUND_CARD -> {
                body(dt); boxer.update(clock.wdt, dt, body)
                // the rest posture, re-declared while the head is still (twenty taps push the frame on the nose)
                if (stateT >= CARD_T * 0.5f && motion < 0.05f) { roll0 = roll; pitch0 = pitchG; pushRest() }
                if (stateT >= CARD_T) enterFight()
            }
            State.FIGHT -> updateFight(dt)
            State.KNOCKDOWN_COUNT -> { body(dt); fightRealT += dt; updateCount(dt) }
            State.ROUND_END -> {
                body(dt); fightRealT += dt; boxer.update(clock.wdt, dt, body)
                if (clock.forced != Clock.Forced.CORNER && stateT >= cornerHold) { round++; enterRoundCard() }
            }
            State.KO -> { body(dt); boxer.update(clock.wdt, dt, body) }
            State.GAME_OVER -> {
                body(dt); boxer.update(clock.wdt, dt, body)
                gameOverT += dt
                if (continueLeft > 0f) { continueLeft = max(0f, continueLeft - dt); if (continueLeft <= 0f) Log.i(TAG, "CONTINUE expired") }
                else if (gameOverT >= CONTINUE_S + GAMEOVER_HOLD_T) enterTitle()
            }
        }
        priorityText = when {
            state != State.FIGHT -> ""
            winded -> "WINDED"
            counterWindow > 0f -> "COUNTER"
            else -> ""
        }
    }

    /**
     * THE FLOOR THIS FRAME. In a fight it is the boxer's own (0.35 circling, the hang, the fuse's
     * ramp, 0.12 open), −1 from him meaning a forced state owns it and the gap between two forced
     * states is not a read; past the 3:00 real cap it never drops under 0.35 (the soft anti-stall).
     * A debug launch's `--ef floor` overrides all of it. Off the fight — the attract, the intro,
     * the cards, the corner, the tally, the game-over card — the floor is 1: those are story beats
     * on the player's clock, and a still player watching the announcer introduce him would
     * otherwise watch a frozen taunt.
     */
    /** The safety floor once a round has run three REAL minutes — see [floorFor]. */
    private val REAL_CAP_FLOOR = 0.35f

    private fun floorFor(): Float = when {
        debugFloor >= 0f -> debugFloor
        state == State.FIGHT -> {
            // ONE FLOOR (DESIGN.md §0.1). The fallback for a knockdown and the real-time cap both
            // used to reach for FLOOR_IDLE = 0.35 — the number that made the mechanic invisible —
            // so they take the same low floor as everything else now. The `realCapped` case is the
            // one honest exception and it is a SAFETY net, not a difficulty dial: after three real
            // minutes in one round the fight has stopped being a fight, and it is better to let it
            // run out than to leave a wearer standing motionless in front of a frozen man forever.
            val deep = Clock.FLOOR_STILL
            val his = boxer.floorNow(deep)
            val f = if (his < 0f) deep else his
            if (realCapped) max(f, REAL_CAP_FLOOR) else f
        }
        state == State.KNOCKDOWN_COUNT -> Clock.FLOOR_STILL
        else -> 1f
    }

    /** The plate's flashes and words, the meter's pour, the bells, the caption's hold, the crowd — REAL time, every state, menu or no menu. */
    private fun plate(dt: Float) {
        damageFlash = max(0f, damageFlash - dt * DAMAGE_FLASH_DECAY)
        if (stunJitterT > 0f) stunJitterT = max(0f, stunJitterT - dt)
        if (feedbackT > 0f) feedbackT = max(0f, feedbackT - dt)
        if (impactT > 0f) impactT = max(0f, impactT - dt)
        if (starburstT > 0f) starburstT = max(0f, starburstT - dt)
        if (gloveFlashT > 0f) gloveFlashT = max(0f, gloveFlashT - dt)
        if (chantT > 0f) { chantT = max(0f, chantT - dt); if (chantT <= 0f) chant = "" }
        if (koRoarT > 0f) koRoarT = max(0f, koRoarT - dt)
        if (guardCrushedT > 0f) { guardCrushedT = max(0f, guardCrushedT - dt); if (guardCrushedT <= 0f && guardOwner != 0) guardUp = true }
        kickX *= exp(-dt / 0.06f); kickY *= exp(-dt / 0.06f)
        lastStepAge += dt
        // the answer word pops on real time, holds on world time (its tell does), drops on real time
        if (answerWord.isNotEmpty()) {
            answerT += dt
            if (answerDropping) { answerDropT += dt; if (answerDropT >= ANSWER_DROP_T) { answerWord = ""; answerDropping = false } }
        }
        // the meter POURS IN at one point per 133 ms real; a loss, a spend or a reset is instant
        meterShown = if (meterShown > meter) meter.toFloat() else min(meter.toFloat(), meterShown + dt / METER_POUR_S)
        if (meterLit && !putHimAwaySaid) {
            putHimAwaySaid = true
            host.sfx(Sfx.KO_LIT, 1f, 0.8f)
            if (state == State.FIGHT) host.say(Lines.PUT_HIM_AWAY, false, 2000L)
            Log.i(TAG, "METER lit meter=$meter")
        } else if (!meterLit) putHimAwaySaid = false
        // the bell's strokes
        if (bellsLeft > 0) { bellT -= dt; if (bellT <= 0f) { host.sfx(Sfx.BELL); bellsLeft--; bellT = bellGap } }
        // the corner's caption holds the clip plus its tail
        if (captionHoldT > 0f) { captionHoldT = max(0f, captionHoldT - dt); if (captionHoldT <= 0f) { captionLine = ""; captionTag = "" } }
        // the stagger's warble, retriggered while he wobbles
        if (boxer.warbling && state == State.FIGHT) { warbleT -= dt; if (warbleT <= 0f) { warbleT = WARBLE_PERIOD; host.sfx(Sfx.STUN_WARBLE, 1f, 0.35f) } }
        else warbleT = 0f
        crowd(dt)
    }

    /**
     * THE CROWD IS THE RATE METER (DESIGN.md §9.3): the bed's loudness follows the rate with a
     * 200 ms lag in a fight — a hush at the floor, a roar at 1.0 — so the world can be heard to
     * stop. On the canvas the arena holds its breath and counts along from five; on the knockout
     * it roars for two seconds; on the cards it murmurs; on the title it is silent.
     */
    private fun crowd(dt: Float) {
        val target = if (state == State.FIGHT) clock.timeScale else 0.3f
        crowdLevel += (target - crowdLevel) * (1f - exp(-dt / CROWD_LAG_T))
        val level = when (state) {
            State.FIGHT -> 0.25f + 0.75f * crowdLevel
            State.KNOCKDOWN_COUNT -> if (countStarted && countN >= COUNT_CROWD_FROM) 0.35f + 0.12f * (countN - COUNT_CROWD_FROM) else 0.15f
            State.KO -> if (koRoarT > 0f) 1f else 0.5f
            State.INTRO, State.ROUND_CARD, State.ROUND_END -> 0.2f
            State.GAME_OVER -> 0.15f
            State.TITLE -> 0f
        }
        host.crowd(level, 0.7f + 0.5f * crowdLevel)
    }

    // ------------------------------------------------------------------ the body (REAL time)
    /**
     * THE INFERRED BODY, the numbers MOTION.md measured, with DODGE SENSE scaling the full duck
     * and the full lean (DESIGN.md §3.2). All of it runs on `dt`: the step's lunge and recovery,
     * the lean and the duck are the player's own limbs. The aim level's hysteresis lives here too:
     * enter LOW at 0.35 of a duck, leave at 0.25, so a head hovering at the line cannot flip a
     * combo's second punch to the other level. Your fall sinks the eye 0.4 m; the stillness the
     * stall pressure reads is measured here (see [STILL_M]).
     *
     * REJECTED STEPS are approximated: the tracker withholds a step's verdict during a pad blank
     * and says nothing about it, so a body step a flurry blinded is inferred here as the leaky
     * lateral velocity crossing `V_STEP` while a blank is in force — without the tracker's four
     * gates, so a hard lean inside a blank can count. It only ever feeds the corner's "Don't punch
     * and run", once a fight, and a spare sentence of advice is the whole cost of being wrong.
     */
    private fun body(dt: Float) {
        val mo = motionSrc
        if (mo != null && state == State.FIGHT) {
            if (mo.step != 0) step(mo.step, "BODY")
            val v = abs(mo.vLat)
            if (v < MotionTracker.V_STEP * 0.5f) rejectArmed = true
            else if (rejectArmed && v > MotionTracker.V_STEP && mo.blankLeft > 0L) {
                rejectArmed = false; stepsRejected++
                ev("STEP REJECTED blank n=$stepsRejected")
                if (stepsRejected == STEP_REJECT_TIP_N) stepTipOwed = true
            }
        }
        // the step: out over the forced STEP_T from wherever the body was, back over STEP_BACK_T
        if (stepLeft > 0f) {
            stepLeft = max(0f, stepLeft - dt)
            body.bodyX = stepFrom + (stepTo - stepFrom) * smooth(1f - stepLeft / clock.stepT)
            if (stepLeft <= 0f) { stepBackLeft = STEP_BACK_T; stepLanded = body.bodyX; host.sfx(Sfx.BUMP, 1.6f, 0.3f); ev("STEP landed") }
        } else if (stepBackLeft > 0f) {
            stepBackLeft = max(0f, stepBackLeft - dt)
            body.bodyX = stepLanded * smooth(stepBackLeft / STEP_BACK_T)
        } else body.bodyX *= exp(-dt / 0.15f)

        val sense = store.dodgeSense.coerceIn(0, 2)
        val r = roll - roll0
        val k = ((abs(r) - LEAN_DEAD) / (LEAN_FULL[sense] - LEAN_DEAD)).coerceIn(0f, 1f)
        body.leanX = (if (r < 0f) -1f else 1f) * LEAN_MAX * smooth(k)
        body.duckAmt = ((pitch0 - pitchG) / DUCK_FULL[sense]).coerceIn(0f, 1f)
        body.aimLow = if (body.aimLow) body.duckAmt >= AIM_LOW_LEAVE else body.duckAmt >= AIM_LOW_ENTER
        // your fall: the view sinks 0.4 m over 0.4 s real, and rises with you
        val sinkTo = if (state == State.KNOCKDOWN_COUNT && downWho == Who.YOU) 1f else 0f
        sink = if (sinkTo > sink) min(1f, sink + dt / SINK_T) else max(0f, sink - dt / SINK_T)
        body.headX = body.bodyX + body.leanX
        body.headY = EYE_H - DUCK_DROP * body.duckAmt - SINK_M * sink
        body.headZ = 0f
        body.stepping = stepping
        body.guardUp = guardUp && guardCrushedT <= 0f
        body.guardCrushed = guardCrushedT > 0f
        body.moving = clock.m
        // stillness: held under a forced state and the menu, reset by the body, never by the floor
        if (clock.forced == Clock.Forced.NONE && !menuOpen && !creditsOpen) stillT = if (clock.m < STILL_M) stillT + dt else 0f
        body.stillT = stillT
        body.still = stillT >= Clock.STILL_T
    }

    private fun smooth(k: Float): Float { val u = k.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    // ------------------------------------------------------------------ FIGHT
    private fun updateFight(dt: Float) {
        body(dt)
        fightRealT += dt
        val wdt = clock.wdt
        if (script == Script.COUNTER) runScript(dt)

        // the player's verbs, on real time (frozen under the hit-stop: the glove holds on the impact frame)
        if (clock.forced != Clock.Forced.HITSTOP) {
            punch?.let { p ->
                p.t += dt
                if (!p.resolved && p.t >= p.landT) {
                    resolvePunch(p)
                    if (state != State.FIGHT) return
                    // a landed punch's recovery is cancellable: the 1-2's second beat goes now
                    if (p.landed && queued != null) fireQueued()
                }
                // the same punch, still the one in flight (a queued beat may have replaced it above)
                if (punch === p && p.t >= p.total) { punch = null; if (queued != null) fireQueued() }
            }
        }
        if (counterWindow > 0f) { counterWindow = max(0f, counterWindow - dt); if (counterWindow <= 0f) ev("COUNTER WINDOW closed") }
        if (guardPopLeft > 0f) { guardPopLeft = max(0f, guardPopLeft - wdt); if (guardPopLeft <= 0f && guardOwner == 0) { guardUp = false; ev("GUARD pop over") } }
        // hearts refill on WORLD time with no punch in flight: a still player does not heal
        if (punch == null && hearts < HEARTS) {
            heartRefillT += wdt
            if (heartRefillT >= HEART_REFILL_T) { heartRefillT -= HEART_REFILL_T; hearts++; ev("HEART refilled hearts=$hearts") }
        }

        // THE HOSTILE HALF, all of it on world time
        boxer.update(wdt, dt, body)
        if (state != State.FIGHT) return

        // the round clock, on the world clock; the clapper on the last ten seconds; the bell
        if (roundClock <= clapperNext && clapperNext > 0f) { host.sfx(Sfx.CLAPPER, 1f, 0.7f); clapperNext -= 1f }
        if (clock.worldT >= ROUND_WORLD_S) endRound()
    }

    /** The 1-2's second beat, thrown behind the first — as the special if that is what was asked for. */
    private fun fireQueued() {
        val q = queued ?: return
        val s = queuedSpecial
        queued = null; queuedSpecial = false
        throwPunch(q, -1L, forceSpecial = s)
    }

    /**
     * `--es script counter` (TEST.md §1, "Hit-stop never stops the render loop"): 0.6 s after the
     * bell a scripted BODY jab (a body blow always lands and opens his guard), and the instant it
     * resolves a RIGHT to the head thrown as a COUNTER through the opening — `forced=` absent while
     * it flies, 130 ms of `forced=HITSTOP` when it lands. Then the script retires.
     */
    private fun runScript(dt: Float) {
        scriptT += dt
        when (scriptStep) {
            0 -> if (scriptT >= SCRIPT_DELAY_T) { scriptStep = 1; Log.i(TAG, "SCRIPT counter: the body jab"); throwPunch(Hand.LEFT, -1L, levelOverride = Level.BODY) }
            1 -> { val p = punch; if (p == null || p.resolved) { scriptStep = 2; Log.i(TAG, "SCRIPT counter: the right, as a COUNTER"); throwPunch(Hand.RIGHT, -1L, levelOverride = Level.HEAD, forceCounter = true) } }
            2 -> if (punch == null) { scriptStep = 3; script = Script.NONE; Log.i(TAG, "SCRIPT counter: done") }
        }
    }

    /**
     * SOMEBODY IS ON THE CANVAS. First the fall — `Forced.SLOW` 0.22 for 1.1 s real, forced by
     * [beginCount], his `knockdown` strip or your sinking view — and only when it has run does the
     * referee begin: `Forced.COUNT` (rate 0), one numeral per real second popping with a click and
     * spoken, the crowd along from five, `get_up` at four when it is you. HIM: he rises at the
     * count he was given (or his own `onRise`, whichever first); none = the knockout at ten. YOU:
     * eight alternated taps rise you; ten without them is the count-out, and the third knockdown
     * of the fight was final from the start. The referee is not inside your fight: nothing here
     * reads `wdt`.
     */
    private fun updateCount(dt: Float) {
        boxer.update(clock.wdt, dt, body)
        if (!countStarted) {
            if (clock.timedState == Clock.Forced.SLOW) return
            countStarted = true; countT = 0f; countN = 0
            clock.forceCount()
            Log.i(TAG, "COUNT who=${downWho?.name ?: "-"} riseAt=$riseAt")
            if (downWho == Who.YOU && riseAt > 0 && riseTaps >= riseAt) rise()
            return
        }
        countT += dt
        if (countPopT > 0f) countPopT = max(0f, countPopT - dt)
        val n = countT.toInt().coerceIn(0, 10)
        if (n != countN) {
            countN = n; countPopT = COUNT_POP_T
            host.sfx(Sfx.COUNT_CLICK, if (n >= COUNT_CROWD_FROM) 1.15f else 1f)
            host.say(Lines.ref(n), urgent = true)
            if (downWho == Who.YOU && n == 4) host.say(Lines.GET_UP, false, 800L)
            if (n >= COUNT_CROWD_FROM) host.sfx(Sfx.CROWD_OH, 0.8f + 0.04f * n, 0.45f)
        }
        when (downWho) {
            Who.HIM -> if (riseAt > 0 && countN >= riseAt) endCount() else if (countN >= 10) knockout()
            Who.YOU -> if (countN >= 10) { Log.i(TAG, "COUNTED OUT taps=$riseTaps knockdowns=$knockdownsYou"); ev("COUNTED OUT"); gameOver() }
            null -> {}
        }
    }

    /** Somebody is on the canvas: the fall is forced here; the count follows when it has run ([updateCount]). */
    private fun beginCount(who: Who, riseAt: Int) {
        state = State.KNOCKDOWN_COUNT; stateT = 0f
        downWho = who; countN = 0; countT = 0f; countPopT = 0f; riseTaps = 0; lastRiseHand = null; this.riseAt = riseAt
        countStarted = false
        meter = 0; meterShown = 0f
        hearts = if (who == Who.YOU) 0 else HEARTS
        heartRefillT = 0f
        clearVerbs()
        clock.clearForced(); clock.forceSlow(Clock.SLOW_KNOCKDOWN_RATE, Clock.SLOW_KNOCKDOWN_T)
        host.sfx(Sfx.FALL); host.sfx(Sfx.ROPES, 1f, 0.6f)
        host.music(Music.COUNT)
    }

    /** His knockdown, by whichever road it arrived (see [resolvePunch]): the score, the announcer, the fall, the count he rises at — or none. */
    private fun hisKnockdown(n: Int, riseAtN: Int, ko: Boolean, bySpecial: Boolean) {
        val tko = boxer.knockdownsRound >= Boxer.TKO_KNOCKDOWNS_ROUND
        val final = ko || tko || n >= Boxer.KO_KNOCKDOWNS_FIGHT
        credit(SCORE_KNOCKDOWN, "KNOCKDOWN", mult = false); say("KNOCKDOWN +2000")
        store.knockdownScored = true
        chain = 0; hitsUnanswered = 0
        host.say(if (bySpecial && final) Lines.SLEEP else Lines.KNOCKDOWN, urgent = true)
        beginCount(Who.HIM, if (final) 0 else riseAtN)
        Log.i(TAG, "KNOCKDOWN who=HIM n=$n count=${if (final) 0 else riseAtN} ko=$final")
        if (tko && !ko) Log.i(TAG, "TKO round=$round knockdowns=${boxer.knockdownsRound}")
        ev("KNOCKDOWN HIM n=$n ko=$final${if (bySpecial) " SPECIAL" else ""}")
    }

    /** Your knockdown: the hearts, the fall, the count — and the third is final from the start. */
    private fun yourKnockdown(attack: Boxer.Attack) {
        knockdownsYou++; knockdownsYouRound++
        hearts = 0; chain = 0
        val final = knockdownsYou >= KNOCKDOWNS_TO_LOSE
        Log.i(TAG, "KNOCKDOWN who=YOU n=$knockdownsYou count=${if (final) 0 else RISE_TAPS} ko=$final by=${attack.name}")
        ev("KNOCKDOWN YOU n=$knockdownsYou by=${attack.name}")
        beginCount(Who.YOU, if (final) 0 else RISE_TAPS)
    }

    // ================================================================== WORDS, SCORE, TELEMETRY

    /** Score [points] — the multiplier applies to the verbs (hits, dodges, counters), never to a knockdown, the KO or the bonus. */
    private fun credit(points: Int, reason: String, mult: Boolean = true) {
        if (points == 0) return
        val p = if (points > 0 && mult) points * multiplier else points
        score = (score + p).coerceAtLeast(0)
        Log.i(TAG, "SCORE ${if (p >= 0) "+" else ""}$p reason=$reason${if (mult && multiplier > 1 && points > 0) " x$multiplier" else ""}")
    }

    /** The one feedback word at (320, 300), one at a time, never a stack. */
    private fun say(word: String) { feedback = word; feedbackT = FEEDBACK_T }

    /** `0:14` — the plate's clock format (whole seconds). */
    fun clockText(seconds: Float): String {
        val s = seconds.coerceAtLeast(0f)
        val mins = (s / 60f).toInt()
        return "%d:%02d".format(Locale.US, mins, (s - mins * 60).toInt())
    }

    private fun ev(msg: String) = Log.i(TAG,
        "EVENT t=%.2f %s | head=(%+.2f,%.2f) lean=%+.2f duck=%.2f step=%+.2f ts=%.2f"
            .format(Locale.US, clock.realT, msg, body.headX, body.headY, body.leanX, body.duckAmt, body.bodyX, clock.timeScale))

    /** The 5 Hz `VERIFY` half that is yours. */
    fun verifyLine(): String =
        "VERIFY you=%s hp=%d hearts=%d meter=%d/%d lean=%+.2f duck=%.2f bodyX=%+.2f aimLow=%s guard=%s punch=%s counter=%.2f still=%.1f floor=%.2f forced=%s".format(
            Locale.US, state.name, hp, hearts, meter, METER_MAX, body.leanX, body.duckAmt, body.bodyX, body.aimLow, guardUp,
            punch?.let { "${it.hand.name.first()}${if (it.special) "!" else ""}" } ?: "-", counterWindow, stillT, clock.floor, clock.forced.name)

    // ================================================================== Boxer.Listener

    override fun onPhrase(name: String) { Log.i(TAG, "PHRASE $name round=$round") }

    /**
     * A tell began: the held breath (the snap into the deep floor has a sound), the attack's own
     * cue, the crow for the Sunrise (a telegraph, always urgent; the cut crow for its lie), and the
     * answer word by CAPTIONS — the first two appearances of each attack on AUTO, never for a
     * feint (a lie must not be captioned with an answer).
     */
    override fun onTell(attack: Boxer.Attack, feint: Boxer.Feint?, tellT: Float) {
        host.sfx(Sfx.HANG, 1f, 0.5f)
        if (attack.tellSfx >= 0) host.sfx(attack.tellSfx, 1f, 0.6f)
        if (attack == Boxer.Attack.SUNRISE) host.hero(if (feint == Boxer.Feint.FALSE_SUNRISE) Lines.RISE_CUT else Lines.RISE_AND_SHINE, urgent = true)
        if (feint == null) {
            val i = attack.ordinal
            answerSeen[i]++
            val show = when (store.captions) { 1 -> true; 2 -> false; else -> answerSeen[i] <= CAPTION_AUTO_TIMES }
            if (show) { answerWord = attack.word; answerT = 0f; answerDropping = false; answerDropT = 0f }
        }
        Log.i(TAG, "TELL attack=${attack.name} feint=${feint?.name ?: "-"} round=$round floorAtStart=%.2f hangLeft=%.2f tellT=%.2f".format(Locale.US, clock.floor, boxer.hangLeft, tellT))
    }

    /**
     * The glove leaves. It is NOT forced (the owner's ruling, BOXER.md's header: every punch
     * travels on world time, his and yours). The boxer advances the strike on `wdt` under the
     * hang-then-fuse floor he hands the clock ([Boxer.floorNow]), so a still player watches it
     * crawl and the fuse brings it; mashing runs the world, the glove on its way to your face
     * included, and countering a hanging glove accelerates both — you are betting yours lands
     * first. The first draft called `clock.forceStrike(strikeT)` here; `Clock.forceStrike` stays
     * for `ClockTest` and the lab, and a `forced=STRIKE` in a `CLK` line means somebody put it back.
     * The answer word drops with the glove.
     */
    override fun onStrikeStart(attack: Boxer.Attack, strikeT: Float) {
        if (answerWord.isNotEmpty()) { answerDropping = true; answerDropT = 0f }
        host.sfx(Sfx.EXTEND, 1f, 0.5f)
    }

    /**
     * THE VERDICT ON HIS PUNCH (DESIGN.md §2.6, §4.1, §4.4, §4.5, §5.5). [dmg] arrives scaled by
     * difficulty and halved for a glance; in a drill it is taken as 0 and no heart is spent.
     *  - HIT: the damage, a heart, the meter's hit (−8 / −12), the flash and the shake, the stun
     *    of the right wing, the chain and the multiplier broken, the chant after two unanswered;
     *    at 0 HP, your knockdown.
     *  - GLANCE: half in — half the damage (already halved), no heart, no knockdown check.
     *  - CRUSH: a hook through the guard — the damage, a heart, the guard forced down 0.4 s real.
     *  - BLOCK: the guard held — a peck's chip (never a knockdown), or the body hook's GUARD-COUNTER
     *    (+150; the boxer opens himself).
     *  - CLEAN: off the line before the strike — `DODGE +50`, +1 meter, the streak.
     *  - PERFECT: off the line INSIDE the strike — `PERFECT +300`, every heart back, +3 meter, the
     *    counter window on REAL time, the streak, the crowd's swell.
     */
    override fun onStrike(attack: Boxer.Attack, answer: Answer, result: StrikeResult, dmg: Int) {
        val drill = boxer.drill != Boxer.Drill.OFF
        val d = if (drill) 0 else dmg
        var word = ""
        when (result) {
            StrikeResult.HIT, StrikeResult.GLANCE, StrikeResult.CRUSH -> {
                val glance = result == StrikeResult.GLANCE
                hp = (hp - d).coerceAtLeast(if (glance) 1 else 0)
                if (!glance && !drill) hearts = (hearts - 1).coerceAtLeast(0)
                hitBy[attack.ordinal]++; hitsUnanswered++; chain = 0
                meter = (meter - (if (glance) attack.meterHit / 2 else attack.meterHit)).coerceAtLeast(0)
                damageFlash = if (glance) 0.5f else 1f
                multiplier = 1; dodgeStreak = 0
                if (attack.stunsYou && !glance) stunJitterT = STUN_YOU_T
                if (result == StrikeResult.CRUSH) { guardCrushedT = GUARD_CRUSH_T; guardUp = false; word = "CRUSHED"; host.sfx(Sfx.GUARD_THUD, 0.7f, 0.9f) }
                host.sfx(when { glance -> Sfx.GLANCE; attack == Boxer.Attack.WING_L -> Sfx.HIT_BODY; else -> Sfx.HIT_HEAD }, 0.85f, if (glance) 0.6f else 0.9f)
                if (glance) word = "GLANCE"
                if (!drill && hitsUnanswered >= CHANT_AFTER_HITS && hitsUnanswered % CHANT_AFTER_HITS == 0) {
                    host.say(Lines.CHANT, false, 1500L); chant = "ROO-STER"; chantT = 2f
                }
                if (winded && hp > 0) word = "WINDED"
                if (hp <= 0 && !drill) yourKnockdown(attack)
            }
            StrikeResult.CLEAN -> {
                credit(SCORE_CLEAN, "DODGE"); word = "DODGE +50"
                meter = (meter + METER_CLEAN).coerceAtMost(METER_MAX); dodgeStreak++
                host.sfx(Sfx.WHIFF, 0.75f, 0.5f)
            }
            StrikeResult.PERFECT -> {
                perfects++; credit(SCORE_PERFECT, "PERFECT"); word = "PERFECT +300"
                hearts = HEARTS; heartRefillT = 0f
                meter = (meter + METER_PERFECT).coerceAtMost(METER_MAX)
                counterWindow = COUNTER_WINDOW[store.difficulty.coerceIn(0, 2)]
                dodgeStreak++
                host.sfx(Sfx.CROWD_OH, 1.1f, 0.8f)
            }
            StrikeResult.BLOCK -> {
                if (attack.guardCounters) { credit(SCORE_GUARD_COUNTER, "GUARD COUNTER"); word = "GUARD +150"; host.sfx(Sfx.BLOCKED, 0.8f, 0.8f) }
                else word = "GUARD"
                hp = (hp - d).coerceAtLeast(1)
                damageFlash = max(damageFlash, 0.3f)
                host.sfx(Sfx.GUARD_THUD, 1f, 0.7f)
            }
        }
        multiplier = (1 + dodgeStreak / 2).coerceIn(1, MULT_MAX)
        if (word.isNotEmpty()) say(word)
        answerWord = ""; answerDropping = false
        Log.i(TAG, "STRIKE attack=${attack.name} answer=${answer.name} result=${result.name} dmg=$d | head=(%+.2f,%.2f) lean=%+.2f duck=%.2f step=%+.2f ts=%.2f".format(
            Locale.US, body.headX, body.headY, body.leanX, body.duckAmt, body.bodyX, clock.timeScale))
        ev("HIS ${attack.name} ${result.name} answer=${answer.name} dmg=$d hp=$hp")
    }

    override fun onFuseBurned(attack: Boxer.Attack) { Log.i(TAG, "FUSE burned attack=${attack.name}"); host.sfx(Sfx.TICK, 0.7f, 0.4f) }
    override fun onRecover(attack: Boxer.Attack) { ev("HIS ${attack.name} RECOVER") }
    override fun onGuard(open: Boolean, by: String) { Log.i(TAG, "GUARD open=$open by=$by") }
    override fun onStagger(open: Boolean, seconds: Float) {
        Log.i(TAG, "STAGGER open=$open extended=%.2f".format(Locale.US, seconds))
        if (open) { host.sfx(Sfx.STUN_WARBLE, 1f, 0.5f); host.sfx(Sfx.CROWD_OH, 0.9f, 0.8f); warbleT = WARBLE_PERIOD }
    }
    override fun onStun(on: Boolean) { if (on) host.sfx(Sfx.STUN_WARBLE, 1.3f, 0.4f); ev("STUN ${if (on) "on" else "off"}") }
    override fun onHitReaction(kind: Boxer.HitKind) {
        when (kind) {
            Boxer.HitKind.BLOCKED -> {}
            Boxer.HitKind.SPIN -> host.sfx(Sfx.CROWD_OH, 0.9f, 0.9f)
            else -> host.sfx(Sfx.CROWD_OH, 1f, 0.5f)
        }
    }

    override fun onKnockdown(n: Int, riseAt: Int, ko: Boolean) {
        if (state == State.KNOCKDOWN_COUNT) return   // already on the canvas: the Outcome road took it
        hisKnockdown(n, riseAt, ko, resolving?.special == true)
    }

    override fun onRise() {
        if (state == State.KNOCKDOWN_COUNT && downWho == Who.HIM) endCount()
        else Log.i(TAG, "RISE at=$countN who=HIM (his own)")
    }

    override fun onWrongSide() {
        credit(Boxer.WRONG_SIDE_PENALTY, "WRONG SIDE"); say("WRONG SIDE -50")
        multiplier = 1; dodgeStreak = 0
    }

    override fun onAward(word: String, points: Int) { credit(points, word); say(word) }

    override fun onStall(round: Int) {
        host.sfx(Sfx.BOO, 1f, 0.6f)
        if (round == 1) host.say(Lines.STICK_AND_MOVE, false, 2000L)
        hitBy[HIT_STILL]++
        ev("STALL round=$round still=%.1f".format(Locale.US, stillT))
    }

    override fun onSay(id: String, urgent: Boolean) { if (id in Lines.HERO) host.hero(id, urgent) else host.say(id, urgent) }
    override fun onSfx(id: Int, pitch: Float, vol: Float) = host.sfx(id, pitch, vol)
}
