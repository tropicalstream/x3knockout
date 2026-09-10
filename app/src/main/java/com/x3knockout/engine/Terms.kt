package com.x3knockout.engine

/**
 * THE WORDS THE FIGHT AND THE BOXER SHARE — and nothing else. `Fight.kt` and `Boxer.kt` are
 * written by two people at once; every enum both of them name lives here so that neither can
 * move it out from under the other. During the parallel build this file belongs to nobody but
 * the integrator: add a value if you must, never rename one, and never move a type in or out.
 *
 * Nothing here imports Android. The fight's arithmetic is proved on the JVM (`TwoClocksTest`),
 * and a vocabulary file is the last place to lose that.
 */

/** Which glove — yours or his. LEFT is the LEFT temple pad (`cyttsp6`), RIGHT is `cyttsp5`. */
enum class Hand { LEFT, RIGHT }

/** Where a player punch is aimed: level head = HEAD, `duckAmt ≥ 0.35` = BODY (DESIGN.md §1.3). */
enum class Level { HEAD, BODY }

/**
 * What the player did to an incoming punch, read off the collider at the contact frame — never a
 * threshold on a gesture (DESIGN.md §3.3). The `STRIKE answer=` telemetry key carries the name.
 */
enum class Answer { NONE, SLIP_L, SLIP_R, DUCK, GUARD, STEP }

/**
 * The verdict on HIS punch (DESIGN.md §2.6, §4.1): HIT and GLANCE (half in, half damage, no
 * knockdown check) land; CLEAN was off the line before the strike began (+50); PERFECT left the
 * line INSIDE the strike (+300, hearts, +3 meter, the counter window); BLOCK is a guard that
 * held; CRUSH is a guard a hook went through (half damage, guard forced down 0.4 s).
 */
/** …plus SHORT: he threw from too far out and it never arrived. See `Boxer.HIS_REACH`. */
enum class StrikeResult { HIT, GLANCE, CLEAN, PERFECT, BLOCK, CRUSH, SHORT }

/**
 * The verdict on YOUR punch (the `PUNCH result=` key): LAND on an open target; GUARD into his
 * closed guard (a whiff: a heart and the mash tax); AIR past his head (the moving-head whiff);
 * COUNTER inside the perfect window; STAGGER landed on a staggered boxer (× 2); KNOCKDOWN when
 * it dropped him; WINDED never thrown — no hearts.
 */
enum class PunchResult { LAND, GUARD, AIR, COUNTER, STAGGER, KNOCKDOWN, WINDED, SHORT }

/** Who is on the canvas. */
enum class Who { HIM, YOU }

/** The pad's directions, as `MainActivity` settles them. FORWARD / BACK are the D-pad's aliases for UP / DOWN. */
enum class Swipe { FORWARD, BACK, UP, DOWN, LEFT, RIGHT }

/**
 * THE INFERRED BODY, one struct, filled by the fight every frame and READ by the boxer.
 *
 * The glasses know the head's attitude and nothing about where the body went (MOTION.md), so the
 * body is extrapolated: the lean moves the eye and the collider sideways, the duck drops them,
 * the step lunges them. The collider is a capsule from [top] to [bottom], half-width
 * [Fight.BODY_HW], centred on ([headX], [headY]); a punch is thrown at where it WAS when the tell
 * committed and lands or misses by where it IS at the contact frame. The boxer never writes here.
 */
class Body {
    /** The eye, world metres: the camera and the collider's axis. */
    var headX = 0f
    var headY = Fight.EYE_H
    var headZ = 0f
    /** The shaped lean, metres, + = right (up to ±0.55). */
    var leanX = 0f
    /** 0..1 of a full crouch (DODGE SENSE scales what "full" is). */
    var duckAmt = 0f
    /** The step's lunge, metres, + = right. */
    var bodyX = 0f
    /** True from the step's take-off to its landing: invulnerable, exactly as a hop was. */
    var stepping = false
    /** The right pad's guard: gloves up until the finger lifts, or the 0.5 s world pop. */
    var guardUp = false
    /** True while the guard is CRUSHED (forced down 0.4 s real after a hook went through it). */
    var guardCrushed = false
    /** The aim level armed by the duck's hysteresis: true = every punch is a body blow. */
    var aimLow = false
    /** `Clock.m` this frame — a punch thrown above 0.6 may whiff (DESIGN.md §4.2). */
    var moving = 0f
    /** `Clock.still` — the stall pressure's trigger while he is idle. */
    var still = false
    /** Real seconds the player has been still (`Clock.stillT`). */
    var stillT = 0f

    val top: Float get() = headY + Fight.HEAD_TOP
    val bottom: Float get() = headY - Fight.BODY_LEN
}

/**
 * THE VOICE LINES, by id — BOXER.md §9's table, which `tools/extract_lines.py` renders into
 * `assets/voice/` (ANNOUNCER, REFEREE, CORNER, CROWD) and `assets/voice_hero/` (the ROOSTER).
 * The strings themselves never appear in the code: the bus plays a clip by id and the plate
 * captions nothing (the game speaks in its own names, and only the names in that table).
 */
object Lines {
    // ANNOUNCER
    const val INTRO_1 = "intro_1"
    const val INTRO_2 = "intro_2"
    const val INTRO_3 = "intro_3"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val BODY_BLOW = "body_blow"
    const val COUNTER = "counter"
    const val KNOCKDOWN = "knockdown"
    const val SLEEP = "sleep"
    const val WINNER_KO = "winner_ko"
    const val WINNER_TKO = "winner_tko"
    const val WINNER_BELT = "winner_belt"
    const val NO_DECISION = "no_decision"
    const val END_OF_LINE = "end_of_line"
    // THE CAREER (VOICE.md 6.6): the announcer carries the STAKES — the city, the number, the title.
    const val INTRO_YOU = "intro_you"
    const val TITLE_SHOT = "title_shot"
    /** `rank_4` … `rank_1`: the number the player has just taken off the man he beat. */
    fun rank(n: Int) = "rank_" + n.coerceIn(1, 4)
    // REFEREE
    const val FIGHT = "fight"
    // THE REFEREE'S OTHER MOMENTS (VOICE.md 5.5). All five clips have been rendered since the
    // first pass and none of them was ever named here, so the only thing the third man in the ring
    // ever said was "Box!" and the count. He has a body now; he gets his voice with it.
    const val REF_BREAK = "ref_break"
    const val REF_TIME = "ref_time"
    const val REF_STOP = "ref_stop"
    const val REF_NEUTRAL = "ref_neutral"
    /** `ref_1` … `ref_10`. */
    fun ref(n: Int) = "ref_" + n.coerceIn(1, 10)
    // CORNER
    const val PUT_HIM_AWAY = "put_him_away"
    const val STICK_AND_MOVE = "stick_and_move"
    const val GET_UP = "get_up"
    const val TIP_PECK = "tip_peck"
    const val TIP_WING_R = "tip_wing_r"
    const val TIP_WING_L = "tip_wing_l"
    const val TIP_SUNRISE = "tip_sunrise"
    const val TIP_GUARD = "tip_guard"
    const val TIP_STILL = "tip_still"
    const val TIP_STEP = "tip_step"
    const val TIP_SPECIAL = "tip_special"
    // THE CAREER (VOICE.md 6.6): the trainer carries everything that is about the PLAYER rather
    // than about the fight — he is the only person in the building who is on his side.
    /** `corner_1` … `corner_5`: what he tells you on the bout card, one per man. */
    fun corner(bout: Int) = "corner_" + (bout + 1).coerceIn(1, 5)
    /** `climb_1` … `climb_5`: what he tells you on the rise card, after you have taken a ranking. */
    fun climb(bout: Int) = "climb_" + (bout + 1).coerceIn(1, 5)
    const val NOT_BEATEN = "not_beaten"
    // CROWD
    const val CHANT = "chant"
    const val OH = "oh"
    // THE ROOSTER (voice_hero/)
    const val RISE_AND_SHINE = "rise_and_shine"
    const val RISE_CUT = "rise_cut"
    const val WAKE_UP = "wake_up"
    const val CLUCK = "cluck"
    const val THAT_ALL = "that_all"

    /** The lines that live on the hero track rather than the system one. */
    val HERO = setOf(RISE_AND_SHINE, RISE_CUT, WAKE_UP, CLUCK, THAT_ALL)
}
