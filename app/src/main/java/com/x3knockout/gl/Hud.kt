package com.x3knockout.gl

import com.x3knockout.engine.Hand
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * THE PLATE — 640 × 480 per eye, y down, every stroke a glowing line (DESIGN.md §8).
 *
 * The cabinet had two monitors: the fight screen carried NOTHING but the KO meter, and every
 * number, portrait and bar sat on the scoreboard above it. The plate follows that: a SCOREBOARD
 * BAND across the top (y 40–96), the FIGHT PLANE in the middle with only the KO meter and the
 * telegraph's word on it, and YOUR GLOVES AND HEARTS along the bottom. Additive rules: nothing
 * occludes; brighter is in front; black is nothing; the plate stays sparse because the world is
 * the readout.
 *
 * Split out of `GLRenderer` through two narrow seams, both deliberate:
 *  - **[Sink]** is the only way anything gets drawn — a colour, a colour by component, a line in
 *    plate coordinates. `GLRenderer` implements it over its HUD batch in four lines; text, boxes,
 *    rings and arcs are built here from `StrokeFont` and trigonometry, because none of that needs
 *    a GL context and all of it needs to be readable next to the layout it serves.
 *  - **[Model]** is the only way anything gets in. The renderer fills a snapshot once a frame and
 *    hands it over; nothing here reaches back into the fight. That is what lets this plate compile
 *    and draw while `Fight` and `Boxer` are rewritten underneath it, in parallel, by other people.
 *
 * Two habits run through every element: **nothing blinks to zero** (an element at alpha 0 on a
 * waveguide is the room showing through and reads as a dropped frame; the 3–30 Hz photosensitive
 * band forbids a full-depth strobe; every flash is [blink] between a floor and a ceiling, the KO
 * box's 4 Hz floors at 0.4), and **nothing allocates** (`draw` runs sixty times a second; colour
 * blends go straight into the sink through [mix]).
 *
 * THE CONTRAST RULE (§7.2): exactly one thing is WHITE at a time — the telegraph glove during a
 * tell, his head for two frames on a hit, the count. This plate's own whites are the count and
 * the answer word's core; everything else stays in hue. The `+` in every award is overdrawn by
 * [plusses] because `StrokeFont` has no `+`; separators are dashes because it has no `·`.
 */
class Hud(private val out: Sink) {

    /** The three primitives `GLRenderer` supplies. Everything else on the plate is built from them. */
    interface Sink {
        fun color(rgb: FloatArray, a: Float)
        fun colorRGB(r: Float, g: Float, b: Float, a: Float)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float)
    }

    companion object {
        const val W = 640f
        const val H = 480f
        const val CX = 320f
        const val CY = 240f

        // ---------------------------------------------------------------- palette (DESIGN.md §7.2)
        val CYAN = floatArrayOf(0.35f, 0.95f, 1.00f)
        val WHITE = floatArrayOf(0.92f, 1.00f, 1.00f)
        val MAGENTA = floatArrayOf(1.00f, 0.15f, 0.60f)
        val RED = floatArrayOf(1.00f, 0.22f, 0.18f)
        val VIOLET = floatArrayOf(0.60f, 0.20f, 1.00f)
        val ACID = floatArrayOf(0.50f, 1.00f, 0.20f)
        val AMBER = floatArrayOf(1.00f, 0.78f, 0.35f)
        val GOLD = AMBER
        val WHITE_GOLD = floatArrayOf(1.00f, 0.90f, 0.50f)
        val BLUE = floatArrayOf(0.30f, 0.50f, 1.00f)
        val DAMAGE = floatArrayOf(1.00f, 0.20f, 0.15f)

        // ---------------------------------------------------------------- bezel
        val BEZEL_OUT = floatArrayOf(6f, 6f, 634f, 474f); const val BEZEL_OUT_A = 0.30f
        val BEZEL_IN = floatArrayOf(14f, 14f, 626f, 466f); const val BEZEL_IN_A = 0.16f

        // ---------------------------------------------------------------- the rails
        /** LEFT RAIL = THE PULSE: the fill IS the rate. The bar every standing test reads. */
        const val RAIL_L_X = 28f
        const val RAIL_TOP = 62f
        const val RAIL_BOT = 418f
        const val RAIL_CAP_R = 7f
        const val RAIL_CROSS_R = 15f
        const val RAIL_CROSS_Y = 240f
        const val RAIL_HALF_TICK_W = 2f
        /** RIGHT RAIL = THE REFLEX: full (RED) the instant a tell begins, drains through the hang and the fuse on REAL time. */
        const val RAIL_R_X = 612f

        // ---------------------------------------------------------------- the scoreboard band (y 40–96)
        val P_HIS_NAME = floatArrayOf(52f, 46f); const val SC_NAME = 2.2f
        val HIS_HP = floatArrayOf(52f, 60f, 232f, 66f)
        val HIS_KD_X = floatArrayOf(58f, 74f, 90f); const val KD_Y = 80f; const val KD_R = 5f
        val P_YOUR_NAME = floatArrayOf(588f, 46f)
        val YOUR_HP = floatArrayOf(408f, 60f, 588f, 66f)
        val YOUR_KD_X = floatArrayOf(550f, 566f, 582f)
        val P_ROUND_CLOCK = floatArrayOf(320f, 52f); const val SC_ROUND_CLOCK = 3.0f
        val P_ROUND = floatArrayOf(320f, 74f); const val SC_ROUND = 1.4f
        val P_REAL = floatArrayOf(320f, 90f); const val SC_REAL = 1.2f
        /** RED under a quarter. */
        const val HP_LOW = 0.25f

        // ---------------------------------------------------------------- THE KO METER (§4.5)
        val METER = floatArrayOf(128f, 104f, 512f, 110f)
        const val METER_MAX = 30f
        const val METER_LIT = 26f
        val KO_BOX = floatArrayOf(96f, 98f, 124f, 116f); const val SC_KO = 1.4f
        const val KO_BLINK_HZ = 4f

        // ---------------------------------------------------------------- the fight plane's words
        val P_ANSWER = floatArrayOf(320f, 330f); const val SC_ANSWER = 3.0f
        val P_FEEDBACK = floatArrayOf(320f, 300f); const val SC_FEEDBACK = 2.6f
        const val FEEDBACK_T = 0.6f
        val P_PRIORITY = floatArrayOf(320f, 130f); const val SC_PRIORITY = 2.0f
        val P_COUNT = floatArrayOf(320f, 240f); const val SC_COUNT = 5.0f
        const val SC_ONOMATOPOEIA = 2.4f
        /** The comic panel brackets at the floor: L-shapes, 40 px legs, inset 20 px, MAGENTA α 0.3. */
        const val BRACKET_INSET = 20f
        const val BRACKET_LEG = 40f
        const val BRACKET_A = 0.3f

        // ---------------------------------------------------------------- the bottom row
        /** Your gloves: rest L / R, r 44; the guard / aim-low slots at 82 %. */
        val GLOVE_L = floatArrayOf(170f, 410f)
        val GLOVE_R = floatArrayOf(470f, 410f)
        val SLOT_L = floatArrayOf(225f, 325f)
        val SLOT_R = floatArrayOf(415f, 325f)
        const val GLOVE_R_PX = 44f
        const val SLOT_SCALE = 0.82f
        val HEART_X = floatArrayOf(296f, 320f, 344f); const val HEART_Y = 455f; const val HEART_R = 9f
        val P_WINDED = floatArrayOf(320f, 436f); const val SC_WINDED = 1.3f
        val P_MULT = floatArrayOf(588f, 436f); const val SC_MULT = 1.4f
        val P_SCORE = floatArrayOf(588f, 455f); const val SC_SCORE = 1.6f
        val P_HIGH = floatArrayOf(30f, 455f); const val SC_HIGH = 1.3f

        // ---------------------------------------------------------------- captions and cards
        val P_CAPTION_TAG = floatArrayOf(52f, 390f); const val SC_CAPTION_TAG = 1.3f
        val P_CAPTION = floatArrayOf(52f, 410f); const val SC_CAPTION = 1.75f
        val P_CAPTION_2 = floatArrayOf(52f, 404f, 52f, 422f); const val SC_CAPTION_2 = 1.55f
        val P_ROUND_CARD = floatArrayOf(320f, 300f); const val SC_ROUND_CARD = 2.9f
        val P_ROUND_CARD_NAME = floatArrayOf(320f, 330f); const val SC_ROUND_CARD_NAME = 1.8f
        val P_INTRO_1 = floatArrayOf(320f, 175f); const val SC_INTRO_1 = 4.0f
        val P_INTRO_2 = floatArrayOf(320f, 215f); const val SC_INTRO_2 = 2.0f
        val P_INTRO_3 = floatArrayOf(320f, 245f); const val SC_INTRO_3 = 1.6f
        const val INTRO_NAME = "THE ROOSTER"
        const val INTRO_FULL = "ROY RUDD"
        const val INTRO_CORNER = "120 LB - FAR CORNER"
        val P_KO = floatArrayOf(320f, 175f); const val SC_KO_CARD = 4.0f
        val KO_ROWS_A = floatArrayOf(215f, 245f); const val SC_KO_ROWS_A = 2.0f
        val KO_ROWS_B = floatArrayOf(300f, 320f, 340f, 360f); const val SC_KO_ROWS_B = 1.7f
        val P_NO_DECISION = floatArrayOf(320f, 175f); const val SC_NO_DECISION = 3.0f
        val P_GAMEOVER = floatArrayOf(320f, 180f); const val SC_GAMEOVER = 4f
        val P_GO_SCORE = floatArrayOf(320f, 215f)
        val P_GO_HIGH = floatArrayOf(320f, 245f)
        val P_COIN = floatArrayOf(320f, 300f); const val SC_COIN = 2.2f
        val P_CONTINUE = floatArrayOf(320f, 330f); const val SC_CONTINUE = 2.0f

        // ---------------------------------------------------------------- the title
        val P_TITLE = floatArrayOf(320f, 150f); const val SC_TITLE = 6.0f
        const val TITLE = "X3 KNOCKOUT"
        const val TRACE_T = 2.0f
        val P_MARQUEE = floatArrayOf(320f, 46f); const val SC_MARQUEE = 1.6f
        const val MARQUEE = "READ THE ROOSTER. THEN MOVE."
        val P_SUBTITLE = floatArrayOf(320f, 195f); const val SC_SUBTITLE = 1.8f
        const val SUBTITLE = "YOUR BODY IS THE CLOCK. YOUR HANDS ARE YOUR GUARD."
        val P_WARNING = floatArrayOf(320f, 340f); const val SC_SMALL = 1.3f
        const val WARNING = "STAND UP. STAY IN PLACE. STOP IF UNWELL."
        val P_HINT_1 = floatArrayOf(320f, 362f)
        const val HINT_1 = "TAP LEFT TEMPLE - LEFT. TAP RIGHT TEMPLE - RIGHT."
        val P_HINT_2 = floatArrayOf(320f, 380f)
        const val HINT_2 = "TILT TO SLIP - NOD TO DUCK - DON'T SLIDE THE LEFT PAD"
        val P_RECORDS = floatArrayOf(30f, 450f, 610f, 450f); const val SC_RECORDS = 1.6f
        val P_CREDIT = floatArrayOf(320f, 464f); const val SC_CREDIT = 1.4f
        const val NO_HEAD = "HEAD TRACKING REQUIRED"

        // ---------------------------------------------------------------- panels
        val MENU_RECT = floatArrayOf(150f, 86f, 490f, 404f)
        const val MENU_WINDOW = 9
        val P_MENU_CLOSE = floatArrayOf(320f, 392f)
        val P_MENU_HINT = floatArrayOf(320f, 428f)
        /**
         * THE MOTION LAB plate: the body signals, live, so a standing test is a reading. The column
         * starts at `(470, 96)` [on disk]; the pitch is 13 rather than the 14 first drawn because
         * the list grew to 26 rows (§8's twenty-one plus ROLL / PITCH, STEPS and the PAD / KEY
         * attribution rows INPUT_LEFTPAD.md asked for) and at 14 the last rows ran through the
         * multiplier at y 436 and into the score row; at 13 the twenty-sixth baseline is 435 and
         * every glyph sits above 428. [LAB_ROW_CHARS] caps a row's width to 18 characters (103 px
         * at sc 1.15) so no row reaches the right-aligned `X3` at x 578.
         */
        val P_LAB = floatArrayOf(470f, 96f); const val LAB_PITCH = 13f; const val SC_LAB = 1.15f
        const val LAB_ROW_CHARS = 18
        /** The rows the lab carries (DESIGN.md §8 + the attribution rows); whoever fills [Model.labRows] keeps to this list so the two do not drift. */
        val LAB_ROWS = listOf("MOTION", "HEAD", "BODY", "ACT", "RATE", "FLOOR", "BLANK", "KNEE", "LEAN X", "DUCK", "AIM",
            "PAD L", "PAD R", "PAIR", "HANG", "FUSE", "STRIP", "FORCED", "HP", "METER", "HEARTS", "ROLL", "STEPS", "PAD", "KEY")
        /** The special's starburst: twelve WHITE-GOLD rays from the impact, growing over the impact's 0.3 s. */
        const val STARBURST_T = 0.3f
        const val STARBURST_RAYS = 12
        /** Your gloves bob ±3 px on real time at rest, so a frozen boxer reads as your reflexes, not a paused game (§2.8). */
        const val GLOVE_BOB_PX = 3f
        const val GLOVE_BOB_HZ = 0.4f
    }

    /** Which plate is being drawn. Kept separate from `State` so the two can move apart. */
    enum class Phase { TITLE, INTRO, ROUND_CARD, FIGHT, COUNT, CORNER, KO, GAME_OVER, CREDITS }

    /** What a glove of yours is doing on the plate (§7.6). */
    enum class Glove { REST, AIM_LOW, GUARD, PUNCH, SPECIAL }

    /**
     * EVERYTHING THE PLATE DRAWS, filled once a frame by `GLRenderer`. Plain fields on purpose:
     * this is a snapshot, it is re-used between frames, and nothing in here may reach back into
     * the fight.
     */
    class Model {
        // ---- what is happening
        var phase = Phase.TITLE
        /** Seconds of REAL time in this phase — the plate is always on the player's clock. */
        var phaseT = 0f
        var t = 0f
        var menuOpen = false
        var labOn = false
        /** The fight tint (MAGENTA) or the player's ACID after the win: the bezel and the seams. */
        var tint: FloatArray = MAGENTA

        // ---- the clocks
        var rate = 0f
        var floor = 0.05f
        var halfFlash = 0f
        var still = false
        /** 0..1 of the read left: 1 the instant a tell begins, 0 when the fuse has burned. −1 = idle (dim outline). */
        var reflex = -1f

        // ---- the scoreboard
        var hisName = "THE ROOSTER"
        var hisHp = 1f
        var hisKd = 0
        var yourName = "YOU"
        var yourHp = 1f
        var yourKd = 0
        var roundClock = "1:00"
        var roundLine = "ROUND 1 OF 3"
        var realLine = "REAL 0:00"
        /** The last ten world seconds pulse with the clapper. */
        var clockPulse = false
        var score = 0
        var high = 0
        var newHigh = false
        var multiplier = 1

        // ---- the meter
        var meter = 0f
        var meterLit = false

        // ---- the bottom row
        var gloveL = Glove.REST
        var gloveR = Glove.REST
        /** 0..1 out and back for the punching hand; the target's plate position. */
        var punchHand: Hand? = null
        var punchK = 0f
        var punchTargetX = CX
        var punchTargetY = 200f
        var punchTargetOn = false
        /** The landing glove goes WHITE α 1.3 for two frames. */
        var gloveFlashHand: Hand? = null
        /** With `LEFT PAD OFF` the hand the next right-pad tap throws: its outline is lit (§8). Null = both pads live. */
        var armedHand: Hand? = null
        var hearts = 3
        var winded = false

        // ---- the words and FX
        var answerWord = ""
        /** 0..1 of the pop (0.5 → 3.0 over 80 ms real); [answerDrop] 0..1 of the fade-out over 120 ms once the strike begins. */
        var answerPop = 0f
        var answerDrop = 0f
        var feedback = ""
        var feedbackT = 0f
        var priorityText = ""
        var impactWord = ""
        var impactX = CX
        var impactY = CY
        /** Real seconds left of the impact's 0.3 s: the stars and the onomatopoeia's drift. */
        var impactT = 0f
        var starburstT = 0f
        /** The panel brackets fade in as the rate falls under 0.15 (0..1). */
        var brackets = 0f
        var damageFlash = 0f
        /** The right wing's stun: the plate's strokes jitter 4 px. */
        var stunJitter = 0f
        /** The hit-stop kick of the whole drawn scene, px (the plate follows it). */
        var kickX = 0f
        var kickY = 0f

        // ---- the count
        var countN = 0
        var countPop = 0f
        var countYou = false

        // ---- cards
        var roundCard = ""
        var roundCardName = ""
        var captionTag = ""
        var captionLine = ""
        var tally: List<String> = emptyList()
        var noDecision = false
        var continueLeft = 0f

        // ---- the title
        var headOn = true
        var bestKo = ""

        // ---- panels
        var menuItems: List<String> = emptyList()
        var menuValues: List<String> = emptyList()
        var menuSel = 0
        var menuTop = 0
        var labRows: List<Pair<String, String>> = emptyList()
        var creditsLines: List<String> = emptyList()
        var creditsScroll = 0f
    }

    // ------------------------------------------------------------------ primitives
    private var countOnly = false
    private var traceCount = 0
    private var traceLimit = -1f

    private val fontSink = object : StrokeFont.LineSink {
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (countOnly) { traceCount++; return }
            if (traceLimit >= 0f) {
                val i = traceCount++
                if (i >= traceLimit) return
                val f = (traceLimit - i).coerceAtMost(1f)
                out.line(x0, y0, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
                return
            }
            out.line(x0, y0, x1, y1)
        }
    }

    private fun color(c: FloatArray, a: Float = 1f) = out.color(c, a)
    private fun rgb(r: Float, g: Float, b: Float, a: Float = 1f) = out.colorRGB(r, g, b, a)
    private fun hl(x0: Float, y0: Float, x1: Float, y1: Float) = out.line(x0, y0, x1, y1)
    private fun text(s: String, x: Float, y: Float, sc: Float) = StrokeFont.draw(s, x, y, sc, fontSink)
    private fun textC(s: String, cx: Float, y: Float, sc: Float) = StrokeFont.draw(s, cx - StrokeFont.width(s, sc) / 2f, y, sc, fontSink)
    private fun textR(s: String, rx: Float, y: Float, sc: Float) = StrokeFont.draw(s, rx - StrokeFont.width(s, sc), y, sc, fontSink)

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) {
        hl(x0, y0, x1, y0); hl(x1, y0, x1, y1); hl(x1, y1, x0, y1); hl(x0, y1, x0, y0)
    }

    private fun circle(cx: Float, cy: Float, r: Float, n: Int = 16) {
        for (i in 0 until n) {
            val a0 = i * 2f * PI.toFloat() / n; val a1 = (i + 1) * 2f * PI.toFloat() / n
            hl(cx + cos(a0) * r, cy + sin(a0) * r, cx + cos(a1) * r, cy + sin(a1) * r)
        }
    }

    /** A part-ring. Angles in radians, 0 = up. */
    private fun arc(cx: Float, cy: Float, r: Float, from: Float, to: Float, n: Int = 20) {
        for (i in 0 until n) {
            val a0 = from + (to - from) * i / n; val a1 = from + (to - from) * (i + 1) / n
            hl(cx + sin(a0) * r, cy - cos(a0) * r, cx + sin(a1) * r, cy - cos(a1) * r)
        }
    }

    /** A stroke heart of radius [r]: two arcs and a point. Filled = a second, smaller one inside. */
    private fun heart(cx: Float, cy: Float, r: Float, filled: Boolean) {
        val a = PI.toFloat()
        arc(cx - r * 0.5f, cy - r * 0.35f, r * 0.55f, -a * 0.75f, a * 0.5f, 8)
        arc(cx + r * 0.5f, cy - r * 0.35f, r * 0.55f, -a * 0.5f, a * 0.75f, 8)
        hl(cx - r, cy - r * 0.15f, cx, cy + r)
        hl(cx + r, cy - r * 0.15f, cx, cy + r)
        if (filled) { hl(cx - r * 0.5f, cy, cx, cy + r * 0.55f); hl(cx + r * 0.5f, cy, cx, cy + r * 0.55f); hl(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy - r * 0.3f) }
    }

    /** Blend two palette entries straight into the sink — no `FloatArray` temporary per gradient step. */
    private fun mix(a: FloatArray, b: FloatArray, k: Float, alpha: Float) {
        val u = k.coerceIn(0f, 1f)
        rgb(a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u, alpha)
    }

    /** A sine flash between [low] and [high]. Nothing on this plate blinks to zero. */
    private fun blink(t: Float, hz: Float, low: Float, high: Float) =
        low + (high - low) * (0.5f + 0.5f * sin(t * hz * 2f * PI.toFloat()))

    /**
     * Overdraw the `+` signs in [s], drawn at [x], [y], [sc]. `StrokeFont` has no `+` glyph and
     * drops it in silence, so `PERFECT +300` would reach the glass as `PERFECT 300` — a total,
     * not an award. The crossbar is the `-` glyph's own (1,3)-(3,3), so a plus and a minus are
     * visibly the same stroke with and without its upright.
     */
    private fun plusses(s: String, x: Float, y: Float, sc: Float) {
        if (s.indexOf('+') < 0) return
        for (i in s.indices) {
            if (s[i] != '+') continue
            val cx = x + i * StrokeFont.ADVANCE * sc
            hl(cx + 1f * sc, y - 3f * sc, cx + 3f * sc, y - 3f * sc)
            hl(cx + 2f * sc, y - 2f * sc, cx + 2f * sc, y - 4f * sc)
        }
    }

    // ------------------------------------------------------------------ the frame
    /** Draw the whole plate for one frame. The only entry point. */
    fun draw(m: Model) {
        when (m.phase) {
            Phase.TITLE -> title(m)
            Phase.CREDITS -> credits(m)
            else -> fight(m)
        }
        if (m.menuOpen) menu(m)
    }

    /** The fight plate: bezel, rails, the scoreboard, the meter, the fight plane's words, the bottom row, the cards. */
    private fun fight(m: Model) {
        bezel(m)
        pulse(m)
        reflex(m)
        scoreboard(m)
        koMeter(m)
        if (!m.menuOpen) { brackets(m); words(m); gloves(m); starburst(m) }
        hearts(m)
        bottomRow(m)
        captions(m)
        damageFrame(m)
        when (m.phase) {
            Phase.INTRO -> intro(m)
            Phase.ROUND_CARD -> roundCard(m)
            Phase.COUNT -> count(m)
            Phase.KO -> knockout(m)
            Phase.GAME_OVER -> gameOver(m)
            else -> {}
        }
        // the panel is the reading while it is up: its value column ends at x 470 where the lab's begins
        if (m.labOn && !m.menuOpen) lab(m)
    }

    private fun bezel(m: Model) {
        color(m.tint, BEZEL_OUT_A); rect(BEZEL_OUT[0], BEZEL_OUT[1], BEZEL_OUT[2], BEZEL_OUT[3])
        color(m.tint, BEZEL_IN_A); rect(BEZEL_IN[0], BEZEL_IN[1], BEZEL_IN[2], BEZEL_IN[3])
    }

    private fun railY(v: Float) = RAIL_BOT - (RAIL_BOT - RAIL_TOP) * v.coerceIn(0f, 1f)

    /**
     * THE PULSE (x3discs' rail, kept stroke for stroke): a fill from the bottom equal to the
     * rate, cyan at the floor climbing to a white-hot core at 1.0, a tick at the floor and the 2 px
     * latency marker at 0.5. The heat is normalised against the SPENDABLE range `(rate − floor) /
     * (1 − floor)`, so standing still is exactly cyan on every floor the boxer hands the clock.
     */
    private fun pulse(m: Model) {
        val rate = m.rate.coerceIn(0f, 1f)
        val fillY = railY(rate); val floorY = railY(m.floor); val hw = RAIL_CAP_R
        color(m.tint, 0.38f)
        hl(RAIL_L_X - hw, RAIL_TOP, RAIL_L_X - hw, RAIL_BOT); hl(RAIL_L_X + hw, RAIL_TOP, RAIL_L_X + hw, RAIL_BOT)
        circle(RAIL_L_X, RAIL_TOP, RAIL_CAP_R, 12); circle(RAIL_L_X, RAIL_BOT, RAIL_CAP_R, 12)
        val heat = ((rate - m.floor) / (1f - m.floor).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
        val k = heat.pow(0.7f)
        mix(CYAN, WHITE, k, 0.62f + 0.78f * k)
        var c = -hw + 1f
        while (c <= hw - 1f) { hl(RAIL_L_X + c, fillY, RAIL_L_X + c, RAIL_BOT); c += 1f }
        mix(CYAN, WHITE, (k + 0.35f).coerceAtMost(1f), 0.9f + 0.5f * k)
        hl(RAIL_L_X - hw - 2f, fillY, RAIL_L_X + hw + 2f, fillY)
        color(CYAN, 0.75f); hl(RAIL_L_X - hw - 6f, floorY, RAIL_L_X + hw + 6f, floorY)
        color(m.tint, 0.45f)
        hl(RAIL_L_X - RAIL_CROSS_R, RAIL_CROSS_Y, RAIL_L_X - hw - 2f, RAIL_CROSS_Y); hl(RAIL_L_X + hw + 2f, RAIL_CROSS_Y, RAIL_L_X + RAIL_CROSS_R, RAIL_CROSS_Y)
        color(WHITE, 0.34f + 0.96f * m.halfFlash.coerceIn(0f, 1f))
        var t = -RAIL_HALF_TICK_W / 2f + 0.5f
        while (t < RAIL_HALF_TICK_W / 2f) { hl(RAIL_L_X - hw, RAIL_CROSS_Y + t, RAIL_L_X + hw, RAIL_CROSS_Y + t); t += 1f }
        if (m.still) { color(VIOLET, 0.62f); text("STILL", RAIL_L_X + RAIL_CAP_R + 9f, 360f, 1.2f) }
    }

    /** THE REFLEX: full RED the instant a tell begins, draining through the hang and the fuse; empty = he comes. Idle: a dim BLUE outline. */
    private fun reflex(m: Model) {
        color(BLUE, 0.14f); hl(RAIL_R_X, RAIL_TOP, RAIL_R_X, RAIL_BOT)
        if (m.reflex < 0f) return
        val y = railY(m.reflex)
        color(RED, 0.55f)
        var c = -3f
        while (c <= 3f) { hl(RAIL_R_X + c, y, RAIL_R_X + c, RAIL_BOT); c += 1f }
        color(WHITE, 0.6f); hl(RAIL_R_X - 6f, y, RAIL_R_X + 6f, y)
    }

    /** The scoreboard band: names, HP bars (his from the left, yours from the right — the mirror), the pips, the clocks. */
    private fun scoreboard(m: Model) {
        color(MAGENTA, 0.9f); text(m.hisName, P_HIS_NAME[0], P_HIS_NAME[1], SC_NAME)
        color(ACID, 0.9f); textR(m.yourName, P_YOUR_NAME[0], P_YOUR_NAME[1], SC_NAME)
        hpBar(HIS_HP, m.hisHp, fromLeft = true, base = MAGENTA)
        hpBar(YOUR_HP, m.yourHp, fromLeft = false, base = ACID)
        for (i in 0 until 3) { color(MAGENTA, if (i < m.hisKd) 1f else 0.4f); circle(HIS_KD_X[i], KD_Y, KD_R, 10) }
        for (i in 0 until 3) { color(ACID, if (i < m.yourKd) 1f else 0.4f); circle(YOUR_KD_X[i], KD_Y, KD_R, 10) }
        color(m.tint, if (m.clockPulse) blink(m.t, 2f, 0.6f, 1.1f) else 0.95f)
        textC(m.roundClock, P_ROUND_CLOCK[0], P_ROUND_CLOCK[1], SC_ROUND_CLOCK)
        color(m.tint, 0.55f); textC(m.roundLine, P_ROUND[0], P_ROUND[1], SC_ROUND)
        color(WHITE, 0.35f); textC(m.realLine, P_REAL[0], P_REAL[1], SC_REAL)
    }

    private fun hpBar(r: FloatArray, frac: Float, fromLeft: Boolean, base: FloatArray) {
        color(base, 0.5f); rect(r[0], r[1], r[2], r[3])
        val f = frac.coerceIn(0f, 1f)
        if (f <= 0f) return
        color(if (f < HP_LOW) RED else base, 0.95f)
        val w = (r[2] - r[0]) * f
        val x0 = if (fromLeft) r[0] else r[2] - w
        var y = r[1] + 1f
        while (y < r[3]) { hl(x0, y, x0 + w, y); y += 1f }
    }

    /** THE KO METER: fills from the RIGHT toward the box; MAGENTA, WHITE-GOLD from 26; the box blinks 4 Hz between 0.4 and 1.2 while lit. */
    private fun koMeter(m: Model) {
        color(BLUE, 0.5f); rect(METER[0], METER[1], METER[2], METER[3])
        val f = (m.meter / METER_MAX).coerceIn(0f, 1f)
        if (f > 0f) {
            color(if (m.meterLit) WHITE_GOLD else MAGENTA, 0.9f)
            val w = (METER[2] - METER[0]) * f
            var y = METER[1] + 1f
            while (y < METER[3]) { hl(METER[2] - w, y, METER[2], y); y += 1f }
        }
        color(WHITE_GOLD, if (m.meterLit) blink(m.t, KO_BLINK_HZ, 0.4f, 1.2f) else 0.35f)
        rect(KO_BOX[0], KO_BOX[1], KO_BOX[2], KO_BOX[3])
        textC("KO", (KO_BOX[0] + KO_BOX[2]) / 2f, KO_BOX[3] - 4f, SC_KO)
    }

    /** The comic panel brackets at the plate's four corners, fading in as the rate falls under 0.15. */
    private fun brackets(m: Model) {
        if (m.brackets <= 0.01f) return
        color(MAGENTA, BRACKET_A * m.brackets)
        val i = BRACKET_INSET; val l = BRACKET_LEG
        hl(i, i, i + l, i); hl(i, i, i, i + l)
        hl(W - i, i, W - i - l, i); hl(W - i, i, W - i, i + l)
        hl(i, H - i, i + l, H - i); hl(i, H - i, i, H - i - l)
        hl(W - i, H - i, W - i - l, H - i); hl(W - i, H - i, W - i, H - i - l)
    }

    /**
     * The answer word (MAGENTA once, WHITE at 0.6 on top — the one place the plate stacks a white
     * core on a hue, §7.4 beat 3), the feedback word, the priority line, the onomatopoeia. The
     * word pops 0.5 → 3.0 on [Model.answerPop] and fades on [Model.answerDrop]; both clocks are
     * the renderer's, which is where the pop's real time and the hold's world time are told apart.
     */
    private fun words(m: Model) {
        if (m.answerWord.isNotEmpty() && m.answerDrop < 1f) {
            val sc = SC_ANSWER * (0.5f + 0.5f * m.answerPop.coerceIn(0f, 1f))
            val a = 1f - 0.85f * m.answerDrop.coerceIn(0f, 1f)
            color(MAGENTA, a); textC(m.answerWord, P_ANSWER[0], P_ANSWER[1], sc)
            color(WHITE, 0.6f * a); textC(m.answerWord, P_ANSWER[0], P_ANSWER[1], sc)
        }
        if (m.feedback.isNotEmpty() && m.feedbackT > 0f) {
            val k = (m.feedbackT / FEEDBACK_T).coerceIn(0f, 1f)
            val pop = ((k - 0.80f) / 0.20f).coerceIn(0f, 1f)
            val sc = SC_FEEDBACK * (1f + 0.15f * pop)
            val left = P_FEEDBACK[0] - StrokeFont.width(m.feedback, sc) / 2f
            color(if (m.feedback.indexOf('+') >= 0) WHITE_GOLD else ACID, 0.5f + 0.5f * k)
            text(m.feedback, left, P_FEEDBACK[1], sc); plusses(m.feedback, left, P_FEEDBACK[1], sc)
        }
        if (m.priorityText.isNotEmpty()) { color(DAMAGE, blink(m.t, 3f, 0.6f, 1.1f)); textC(m.priorityText, P_PRIORITY[0], P_PRIORITY[1], SC_PRIORITY) }
        if (m.impactWord.isNotEmpty() && m.impactT > 0f) {
            val k = 1f - (m.impactT / 0.3f).coerceIn(0f, 1f)
            color(WHITE_GOLD, 1f - k * 0.7f)
            textC(m.impactWord, m.impactX, m.impactY - 20f * k, SC_ONOMATOPOEIA)
        }
    }

    /**
     * YOUR GLOVES, in plate space — the cabinet's own screen-space transparent boxer (§7.6). ACID
     * at α 0.9, single-weight strokes: you are the light thing, he is the ink thing. Rest, the
     * aim-low / guard slots at 82 %, a punch along a quadratic to the target scaling 1.0 → 0.7,
     * the forearm a rubber band from the plate's bottom, WHITE α 1.3 for two frames on impact.
     */
    private fun gloves(m: Model) {
        glove(m, Hand.LEFT, m.gloveL, GLOVE_L, SLOT_L)
        glove(m, Hand.RIGHT, m.gloveR, GLOVE_R, SLOT_R)
    }

    /**
     * One glove. At rest and in the slots it bobs ±3 px on real time (the two hands out of
     * phase), which is what keeps a frozen world from reading as a paused game. A PUNCH carries
     * only the punching hand to the target; the SPECIAL carries BOTH — the Wake-Up Call is two
     * temples and two gloves converging on the chin, AMBER, under the starburst. With `LEFT PAD
     * OFF` the armed hand wears a second ring so the player can see which glove the next tap is.
     */
    private fun glove(m: Model, hand: Hand, g: Glove, rest: FloatArray, slot: FloatArray) {
        val bob = GLOVE_BOB_PX * sin(m.t * GLOVE_BOB_HZ * 2f * PI.toFloat() + (if (hand == Hand.LEFT) 0f else 2.1f))
        var x = rest[0]; var y = rest[1] + bob; var r = GLOVE_R_PX
        var a = if (m.winded) 0.5f else 0.9f
        val flying = m.punchTargetOn && (g == Glove.SPECIAL || (g == Glove.PUNCH && m.punchHand == hand))
        when (g) {
            Glove.AIM_LOW -> { x = slot[0]; y = slot[1] + bob; r *= SLOT_SCALE }
            Glove.GUARD -> { x = slot[0]; y = slot[1] + bob * 0.5f; r *= SLOT_SCALE; a = 1.1f }
            Glove.PUNCH, Glove.SPECIAL -> if (flying) {
                val k = m.punchK.coerceIn(0f, 1f)
                x = rest[0] + (m.punchTargetX - rest[0]) * k; y = rest[1] + (m.punchTargetY - rest[1]) * (k * (2f - k))
                r *= 1f - 0.3f * k
            }
            Glove.REST -> {}
        }
        if (m.gloveFlashHand == hand) color(WHITE, 1.3f) else color(if (g == Glove.SPECIAL) AMBER else ACID, a)
        circle(x, y, r, 16)
        // two lace strokes and the thumb, then the forearm: two rails down off the plate's bottom, a rubber band when the glove flies
        val lx = if (hand == Hand.LEFT) x - r * 0.55f else x + r * 0.15f
        hl(lx, y + r * 0.15f, lx + r * 0.4f, y + r * 0.15f); hl(lx, y + r * 0.4f, lx + r * 0.4f, y + r * 0.4f)
        hl(x - r * 0.4f, y + r * 0.9f, x - r * 0.4f, y + r * 1.6f); hl(x + r * 0.4f, y + r * 0.9f, x + r * 0.4f, y + r * 1.6f)
        hl(x - r * 0.4f, y + r * 1.6f, rest[0] - GLOVE_R_PX * 0.5f, H); hl(x + r * 0.4f, y + r * 1.6f, rest[0] + GLOVE_R_PX * 0.5f, H)
        val tx = if (hand == Hand.LEFT) x + r * 0.9f else x - r * 0.9f
        circle(tx, y - r * 0.3f, r * 0.28f, 8)
        if (m.armedHand == hand && !flying) { color(ACID, 1f); circle(x, y, r + 5f, 16) }
    }

    /** The special's starburst: twelve rays from the impact, alternating long and short, growing and fading over 0.3 s. */
    private fun starburst(m: Model) {
        if (m.starburstT <= 0f) return
        val k = 1f - (m.starburstT / STARBURST_T).coerceIn(0f, 1f)
        color(WHITE_GOLD, 1f - 0.8f * k)
        for (i in 0 until STARBURST_RAYS) {
            val a = i * 2f * PI.toFloat() / STARBURST_RAYS + 0.13f
            val long = i % 2 == 0
            val r0 = 8f + 40f * k; val r1 = (if (long) 24f else 14f) + (if (long) 80f else 45f) * k
            hl(m.impactX + cos(a) * r0, m.impactY + sin(a) * r0, m.impactX + cos(a) * r1, m.impactY + sin(a) * r1)
        }
    }

    private fun hearts(m: Model) {
        for (i in 0 until 3) { color(RED, if (i < m.hearts) 0.95f else 0.35f); heart(HEART_X[i], HEART_Y, HEART_R, i < m.hearts) }
        if (m.winded) { color(DAMAGE, blink(m.t, 2f, 0.5f, 1f)); textC("WINDED", P_WINDED[0], P_WINDED[1], SC_WINDED) }
    }

    private fun bottomRow(m: Model) {
        if (m.multiplier > 1) { color(WHITE_GOLD, 0.9f); textR("X${m.multiplier}", P_MULT[0], P_MULT[1], SC_MULT) }
        color(ACID, 0.85f); textR("${m.score}", P_SCORE[0], P_SCORE[1], SC_SCORE)
        if (m.newHigh) color(WHITE_GOLD, 0.85f) else color(WHITE, 0.5f)
        text("HIGH ${if (m.score > m.high) m.score else m.high}", P_HIGH[0], P_HIGH[1], SC_HIGH)
    }

    /** The corner's caption: tag `CORNER`, one line (two if it carries a `|`). Raised on the clip's line-start only. */
    private fun captions(m: Model) {
        if (m.captionLine.isEmpty()) return
        color(AMBER, 0.6f); text(m.captionTag, P_CAPTION_TAG[0], P_CAPTION_TAG[1], SC_CAPTION_TAG)
        color(AMBER, 0.92f)
        val bar = m.captionLine.indexOf('|')
        if (bar < 0) text(m.captionLine, P_CAPTION[0], P_CAPTION[1], SC_CAPTION)
        else {
            text(m.captionLine.substring(0, bar).trimEnd(), P_CAPTION_2[0], P_CAPTION_2[1], SC_CAPTION_2)
            text(m.captionLine.substring(bar + 1).trimStart(), P_CAPTION_2[2], P_CAPTION_2[3], SC_CAPTION_2)
        }
    }

    /** The damage frame: rects at 4 / 10 px in DAMAGE, decaying over 0.25 s; the 4 px jitter is the renderer's on the batch. */
    private fun damageFrame(m: Model) {
        if (m.damageFlash <= 0.02f) return
        color(DAMAGE, 0.85f * m.damageFlash)
        rect(4f, 4f, W - 4f, H - 4f); rect(10f, 10f, W - 10f, H - 10f)
    }

    /** The count: one numeral per real second at (320, 240) sc 5.0 WHITE, popping 0.5 → 5.0 over 80 ms. */
    private fun count(m: Model) {
        if (m.countN <= 0) return
        val pop = 1f - (m.countPop / 0.08f).coerceIn(0f, 1f)
        color(WHITE, 1f); textC("${m.countN}", P_COUNT[0], P_COUNT[1], SC_COUNT * (0.1f + 0.9f * pop))
        if (m.countYou) { color(ACID, blink(m.t, 3f, 0.5f, 1f)); textC("TAP LEFT - RIGHT - LEFT TO RISE", CX, 300f, SC_SMALL) }
    }

    private fun intro(m: Model) {
        color(MAGENTA, 0.95f); textC(INTRO_NAME, P_INTRO_1[0], P_INTRO_1[1], SC_INTRO_1)
        color(WHITE, 0.7f); textC(INTRO_FULL, P_INTRO_2[0], P_INTRO_2[1], SC_INTRO_2)
        color(WHITE, 0.5f); textC(INTRO_CORNER, P_INTRO_3[0], P_INTRO_3[1], SC_INTRO_3)
    }

    private fun roundCard(m: Model) {
        val fade = (m.phaseT / 0.12f).coerceIn(0f, 1f)
        color(m.tint, 0.95f * fade); textC(m.roundCard, P_ROUND_CARD[0], P_ROUND_CARD[1], SC_ROUND_CARD)
        color(WHITE, 0.7f * fade); textC(m.roundCardName, P_ROUND_CARD_NAME[0], P_ROUND_CARD_NAME[1], SC_ROUND_CARD_NAME)
    }

    /** `KNOCKOUT` (the tally lands a row at a time on the arcade's cadence); the ring's seams flip to ACID outside. */
    private fun knockout(m: Model) {
        color(ACID, 0.95f); textC("KNOCKOUT", P_KO[0], P_KO[1], SC_KO_CARD)
        for (i in m.tally.indices) {
            val a = ((m.phaseT - 0.6f - i * 0.12f) / 0.12f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val y = if (i < 2) KO_ROWS_A[i] else KO_ROWS_B[(i - 2).coerceAtMost(3)] + (i - 5).coerceAtLeast(0) * 20f
            val sc = if (i < 2) SC_KO_ROWS_A else SC_KO_ROWS_B
            color(if (i == m.tally.size - 1) WHITE_GOLD else WHITE, 0.85f * a)
            textC(m.tally[i], CX, y, sc)
        }
    }

    private fun gameOver(m: Model) {
        if (m.noDecision) { color(VIOLET, 0.95f); textC("TIME - NO DECISION", P_NO_DECISION[0], P_NO_DECISION[1], SC_NO_DECISION) }
        else { color(DAMAGE, 0.95f); textC("GAME OVER", P_GAMEOVER[0], P_GAMEOVER[1], SC_GAMEOVER) }
        color(WHITE, 0.8f); textC("SCORE ${m.score}", P_GO_SCORE[0], P_GO_SCORE[1], 2f)
        if (m.newHigh) { color(WHITE_GOLD, blink(m.t, 1.2f, 0.55f, 1f)); textC("NEW HIGH SCORE", P_GO_HIGH[0], P_GO_HIGH[1], SC_ROUND_CARD_NAME) }
        if (m.phaseT <= 1.2f || m.continueLeft <= 0f) return
        if (sin(m.t * 4f) > -0.2f) { color(WHITE_GOLD, 0.95f); textC("INSERT COIN TO CONTINUE", P_COIN[0], P_COIN[1], SC_COIN) }
        color(CYAN, 0.75f); textC("CONTINUE ${ceil(m.continueLeft).toInt()}", P_CONTINUE[0], P_CONTINUE[1], SC_CONTINUE)
    }

    /** THE TITLE: the marquee, the traced name, the coin, the standing warning, the two hints, the records, the credit. */
    private fun title(m: Model) {
        bezel(m)
        color(CYAN, 0.55f); textC(MARQUEE, P_MARQUEE[0], P_MARQUEE[1], SC_MARQUEE)
        val trace = (m.phaseT / TRACE_T).coerceIn(0f, 1f)
        color(MAGENTA, 0.65f + 0.30f * trace); traceC(TITLE, P_TITLE[0], P_TITLE[1], SC_TITLE, trace)
        color(WHITE, 0.6f); textC(SUBTITLE, P_SUBTITLE[0], P_SUBTITLE[1], SC_SUBTITLE)
        if (!m.headOn) { color(DAMAGE, 0.95f); textC(NO_HEAD, P_COIN[0], P_COIN[1], SC_COIN) }
        else if (sin(m.t * 4f) > -0.2f) { color(WHITE_GOLD, 0.95f); textC("INSERT COIN TO PLAY", P_COIN[0], P_COIN[1], 2.4f) }
        color(VIOLET, 0.6f); textC(WARNING, P_WARNING[0], P_WARNING[1], SC_SMALL)
        color(CYAN, 0.5f); textC(HINT_1, P_HINT_1[0], P_HINT_1[1], SC_SMALL); textC(HINT_2, P_HINT_2[0], P_HINT_2[1], SC_SMALL)
        color(CYAN, 0.5f); text("HIGH ${m.high}", P_RECORDS[0], P_RECORDS[1], SC_RECORDS)
        if (m.bestKo.isNotEmpty()) textR("BEST KO ${m.bestKo}", P_RECORDS[2], P_RECORDS[3], SC_RECORDS)
        color(WHITE, 0.45f); textC("CREDIT 01", P_CREDIT[0], P_CREDIT[1], SC_CREDIT)
    }

    /** Centred text with the power-on beam: the strokes appear in drawing order as [k] runs 0 → 1. */
    private fun traceC(s: String, cx: Float, y: Float, sc: Float, k: Float) {
        if (k >= 1f) { textC(s, cx, y, sc); return }
        countOnly = true; traceCount = 0
        StrokeFont.draw(s, 0f, 0f, sc, fontSink)
        countOnly = false
        traceLimit = k * traceCount; traceCount = 0
        textC(s, cx, y, sc)
        traceLimit = -1f
    }

    /** THE SETTINGS PANEL: nine rows in a window that scrolls with the selection (DESIGN.md §11). */
    private fun menu(m: Model) {
        color(CYAN, 0.9f); rect(MENU_RECT[0], MENU_RECT[1], MENU_RECT[2], MENU_RECT[3])
        color(CYAN, 0.45f); rect(MENU_RECT[0] + 4f, MENU_RECT[1] + 4f, MENU_RECT[2] - 4f, MENU_RECT[3] - 4f)
        color(CYAN, 0.9f); textC("SETTINGS", CX, MENU_RECT[1] + 36f, 3f)
        val n = m.menuItems.size
        val top = m.menuTop.coerceIn(0, (n - MENU_WINDOW).coerceAtLeast(0))
        val y0 = MENU_RECT[1] + 66f; val pitch = 26f
        for (w in 0 until MENU_WINDOW) {
            val i = top + w
            if (i >= n) break
            val y = y0 + w * pitch
            val sel = i == m.menuSel
            color(CYAN, if (sel) 1f else 0.5f)
            if (sel) text(">", MENU_RECT[0] + 12f, y, 2f)
            text(m.menuItems[i], MENU_RECT[0] + 30f, y, 2f)
            val v = m.menuValues.getOrNull(i) ?: ""
            if (v.isEmpty()) continue
            val right = MENU_RECT[2] - 20f
            val room = right - (MENU_RECT[0] + 30f + StrokeFont.width(m.menuItems[i], 2f)) - 8f
            if (sel) color(WHITE_GOLD, 1f) else color(WHITE, 0.55f)
            textR(v, right, y, 2f.coerceAtMost(room / (v.length * StrokeFont.ADVANCE)).coerceAtLeast(1.05f))
        }
        color(CYAN, 0.45f)
        if (top > 0) { hl(CX - 8f, MENU_RECT[1] + 50f, CX, MENU_RECT[1] + 44f); hl(CX, MENU_RECT[1] + 44f, CX + 8f, MENU_RECT[1] + 50f) }
        if (top + MENU_WINDOW < n) { hl(CX - 8f, MENU_RECT[3] - 34f, CX, MENU_RECT[3] - 28f); hl(CX, MENU_RECT[3] - 28f, CX + 8f, MENU_RECT[3] - 34f) }
        rgb(0.8f, 1f, 0.85f, 0.85f); textC("DOUBLE-TAP TO CLOSE", P_MENU_CLOSE[0], P_MENU_CLOSE[1], SC_ROUND_CARD_NAME)
        rgb(0.7f, 0.95f, 0.8f, 0.55f); textC("UP/DOWN MOVE   LEFT/RIGHT ADJUST", P_MENU_HINT[0], P_MENU_HINT[1], SC_CREDIT)
    }

    /** THE CREDITS PAGE: the same panel, scrolling, every licence string verbatim when the tracks arrive. */
    private fun credits(m: Model) {
        color(CYAN, 0.9f); rect(MENU_RECT[0], MENU_RECT[1], MENU_RECT[2], MENU_RECT[3])
        color(CYAN, 0.45f); rect(MENU_RECT[0] + 4f, MENU_RECT[1] + 4f, MENU_RECT[2] - 4f, MENU_RECT[3] - 4f)
        color(CYAN, 0.9f); textC("CREDITS", CX, MENU_RECT[1] + 30f, 2.2f)
        val top = MENU_RECT[1] + 46f; val bot = MENU_RECT[3] - 26f; val pitch = 16f
        color(WHITE, 0.72f)
        for ((row, line) in m.creditsLines.withIndex()) {
            val y = top + row * pitch - m.creditsScroll
            if (line.isEmpty() || y < top || y > bot) continue
            textC(line, CX, y, SC_SMALL.coerceAtMost((MENU_RECT[2] - MENU_RECT[0] - 24f) / (line.length * StrokeFont.ADVANCE)))
        }
        color(CYAN, 0.5f); textC("TAP TO GO BACK", CX, MENU_RECT[3] - 10f, SC_SMALL)
    }

    /** The lab column (see [P_LAB]): a row is `KEY VALUE`, the value cut so the row never passes [LAB_ROW_CHARS]. */
    private fun lab(m: Model) {
        var y = P_LAB[1]
        color(ACID, 0.75f); text("MOTION LAB", P_LAB[0], y, 1.3f); y += LAB_PITCH + 1f
        color(ACID, 0.6f)
        for ((k, v) in m.labRows) {
            val room = LAB_ROW_CHARS - k.length - 1
            val value = if (v.length > room) v.substring(0, room.coerceAtLeast(0)) else v
            text("$k $value", P_LAB[0], y, SC_LAB); y += LAB_PITCH
        }
    }
}
