package com.x3knockout.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * THE CLOCK. One law, in one file: **time moves only when you move.**
 *
 * `MotionTracker` measures the body and hands over a single scalar, [Clock.update]'s `motion`
 * (0..1). This class turns that into the only two numbers the rest of the game is allowed to ask
 * for — [timeScale] and [wdt] — and owns everything that legitimately bends them: the action
 * quanta (acting always spends time), the floor (a stopped world is a screenshot), the forced
 * states (a step, the corner, a punch, his strike, a hit-stop, a fall and the count are not the
 * body's to spend), the world clock [worldT], the 10 Hz `CLK` telemetry line, and the rail's
 * 0.5-crossing flag that the latency test reads (DESIGN.md §13).
 *
 * **This file imports nothing from Android, and it must stay that way.** The whole two-clock
 * promise — every hostile timer on world time, every caption and cue on real time (DESIGN.md
 * §2.9) — is a claim about arithmetic, and the only cheap way to keep proving it through a fight's
 * worth of churn is a JVM unit test (`ClockTest`). A single `android.util.Log` or `SystemClock` in
 * here costs that test, so the log goes out through [log] and the caller supplies the sink.
 * (`java.util.Locale` below is JVM, not Android, and is here for a reason of its own — see
 * [clkLine].)
 *
 * WHAT CHANGED FOR THE FIGHT (DESIGN.md §2, against the x3discs clock this file was inherited
 * from — the law itself is untouched):
 *
 *  - **[floorOverride]** — the fight state machine writes the floor every frame from the boxer's
 *    own state (0.35 while he circles, 0.06 for the hang of a tell, a ramp to 0.60 through the
 *    fuse, 0.12 while he is open), so the world hangs exactly when there is something to read and
 *    runs when there is not. [floor] returns the override when it is ≥ 0 and the difficulty (or
 *    lab) floor otherwise. Everything else in the formula is untouched.
 *  - **[Verb]** is the fight's: JAB / HOOK / SPECIAL / GUARD / STEP / EMPTY. The punches are FORCED
 *    states first and pulses second — see [forcePunch] and its tail.
 *  - **[Forced]** gained PUNCH, STRIKE, HITSTOP, SLOW and COUNT beside STEP (was HOP), CORNER (was
 *    DOCKING) and MENU. The timed-vs-latched split is kept and grew a third layer: MENU latches
 *    over everything, HITSTOP is a PAUSE that holds every timer under it (a hit-stop must not
 *    eat the strike it landed inside), and the rest are one timer at a time.
 *
 * It also owns the KNEE, because the knee is a clock decision and not a sensor one. The measured
 * ruling in MOTION.md is already made — **Set B ships** ([Knee.B]: `W_REF` 1.9, `GAMMA` 1.8), so
 * that an ordinary reading scan of the room costs 0.20 of the clock instead of Set A's 0.53 and a
 * real snap still saturates. Clock holds the numbers; whoever owns `MotionTracker` copies [wRef]
 * and [gamma] into `MotionTracker.W_REF` / `MotionTracker.GAMMA` whenever the lab's `KNEE` row
 * moves, because that is the direction that keeps this file pure. **That copy is load-bearing**:
 * [update] takes the mapped scalar and does not re-derive it, so a build that forgets the wire
 * ships Set A's curve while [target] and the `CLK` line's `knee=` both claim Set B.
 *
 * There is deliberately **no anti-wiggle patch** in here. A head-shake buys time because it is
 * motion; what stops it being an exploit is the shape of the curve itself (monotonic, saturating)
 * plus a `RELEASE` longer than a half-shake and a punch that WHIFFS past his head when thrown
 * at `m > 0.6` (DESIGN.md §4.2). Anything that special-cased a reversal would be a second, hidden
 * clock.
 */
class Clock {

    companion object {
        // ---------------------------------------------------------------- the map (MOTION.md)
        /** Angular speed below which the clock stays at its floor, rad/s: rest noise and breathing buy nothing. */
        const val DEAD_W = 0.12f
        /**
         * WHAT A LOOK COSTS, as a fraction of what a dodge of the same head speed costs
         * (LAW.md §1.3). See [omegaEff] — the whole argument is there.
         */
        const val K_YAW = 0.15f

        /**
         * THE AXIS SPLIT: the one angular rate the clock is allowed to charge (LAW.md §1.3), and
         * see the note on [target] for the arithmetic. `wx` is pitch (the duck), `wy` yaw (the
         * look), `wz` roll (the slip). Yaw is priced at [K_YAW]; the other two, which ARE the
         * dodge collider, at full weight.
         */
        fun omegaEff(wx: Float, wy: Float, wz: Float): Float {
            val ky = wy * K_YAW
            return kotlin.math.sqrt(wx * wx + ky * ky + wz * wz)
        }
        /** Linear acceleration dead band, m/s². */
        const val DEAD_A = 0.25f
        /** Body acceleration that counts as fully moving, m/s². A brisk step peaks at 3–5. */
        const val A_REF = 2.2f
        /** Smoothing of the motion scalar, seconds. Fast in, slow out: motion is spent, not banked. */
        const val ATTACK = 0.04f
        /**
         * Longer than a half head-shake (~100 ms), which is what makes the anti-wiggle rule work:
         * a reversal never hands back a free frozen frame.
         */
        const val RELEASE = 0.16f

        /** Set A — the prototype's calibrated pair, kept only so the lab can A/B it. */
        const val W_REF_A = 1.2f
        const val GAMMA_A = 1.3f
        /** Set B — SHIPS. Measured on the owner's recorded fight, MOTION.md "The knee". */
        const val W_REF_B = 1.9f
        const val GAMMA_B = 1.8f

        // ---------------------------------------------------------------- the base floor
        /**
         * THE FLOOR. One number, the whole game (LAW.md §1.2, DESIGN.md §0.1): the fraction of real
         * time the world creeps at when the player is perfectly still.
         *
         * It used to be three — 0.03 / 0.05 / 0.08 by difficulty, on top of a four-branch table in
         * the boxer that raised it to 0.35 whenever he was merely standing there. That made THE
         * CLOCK the difficulty dial, which is the one thing the owner's ruling forbids: in SUPERHOT
         * the time function is the same in the first room and the last, and what gets harder is the
         * ROOM. Nobody gets a faster world for being on HARD; the difficulty rows keep every other
         * lever they have (his HP, his tells, his damage, his patience, which men are on the card).
         *
         * 0.03 is what x3discs shipped on this hardware and what SUPERHOT itself sits near. At 0.03
         * a 12 fps strip frame holds 2.78 real seconds — unambiguously a held pose — and a thrown
         * glove crawls at about 7 cm/s: still moving, so the picture is alive rather than hung.
         */
        const val FLOOR_STILL = 0.03f
        /** The lab's override ladder, 3 / 5 / 8 / 12 / 20 % — lab only; off the lab the floor follows difficulty. */
        val LAB_FLOORS = floatArrayOf(0.03f, 0.05f, 0.08f, 0.12f, 0.20f)
        /** [labFloor] set to this means "follow difficulty", which is what a shipped build does. */
        const val LAB_FLOOR_OFF = -1

        // ---------------------------------------------------------------- the quanta (DESIGN.md §2.3)
        // The honest answer to "give me a button that advances time": you can always make the
        // world move by ACTING, never by waiting. Each verb adds its pulse to `act`, which decays
        // on REAL time — so the cost is paid whether or not the player then stands still.
        //
        // A PUNCH is a forced window FIRST (rate 1.0 for its active + recovery frames) and a
        // pulse SECOND, applied when that window closes naturally — see [forcePunch]. The pulse
        // alone was measured to be unfair: on the owner's recorded run a delicate cut-tap drove the
        // accelerometer's `motion` to 0.29 and a slap to 0.96 for ≈ 0.8 s. A forced window makes a
        // delicate tap and a slap cost the same, which is what fairness needs.
        const val PULSE_JAB = 1.0f
        const val TAU_JAB = 0.20f
        const val PULSE_HOOK = 1.0f
        const val TAU_HOOK = 0.25f
        /** The guard raise or pop — the deflector's old numbers, ≈ 0.10 s of world. */
        const val PULSE_GUARD = 0.5f
        const val TAU_GUARD = 0.20f
        /** The trigger-on-empty rule: a click off the arena's verbs is still an act, ≈ 0.03 s. */
        const val PULSE_EMPTY = 0.2f
        const val TAU_EMPTY = 0.15f

        // ---------------------------------------------------------------- forced states (DESIGN.md §2.4)
        /** The step's slide, seconds, at rate 1.0; the lab's ladder is [STEP_T_CHOICES] (was HOP T). */
        const val STEP_T_DEFAULT = 0.35f
        val STEP_T_CHOICES = floatArrayOf(0.28f, 0.35f, 0.45f, 0.60f)
        /** The ropes slide you to your corner between rounds: when the world moves, time moves. */
        const val CORNER_T = 2.2f
        /** A player's punch, active + recovery, real seconds: the jab, the cross, the special. */
        const val PUNCH_JAB_T = 0.28f
        const val PUNCH_HOOK_T = 0.36f
        const val PUNCH_SPECIAL_T = 0.60f
        /** A whiff (air, or his closed guard) EXTENDS the forced window by this: the mash tax. */
        const val WHIFF_EXTEND_T = 0.15f
        /** His glove in flight, real seconds — the pecks, then the hooks and the uppercut. */
        const val STRIKE_PECK_T = 0.25f
        const val STRIKE_HOOK_T = 0.33f
        /** The 1984 impact frame, milliseconds of real time with BOTH clocks stopped (§2.5). */
        const val HITSTOP_JAB_MS = 70
        const val HITSTOP_HOOK_MS = 110
        const val HITSTOP_COUNTER_MS = 130
        const val HITSTOP_SPECIAL_MS = 200
        /** His fall: a quarter speed for 1.1 s; the KO: a tenth for 2.0 s. */
        const val SLOW_KNOCKDOWN_RATE = 0.22f
        const val SLOW_KNOCKDOWN_T = 1.1f
        const val SLOW_KO_RATE = 0.10f
        const val SLOW_KO_T = 2.0f
        /** The referee's ten, real seconds; the fight clears the state at the rise. */
        const val COUNT_MAX_T = 10f

        // ---------------------------------------------------------------- telemetry
        /** The `CLK` line's period, seconds (10 Hz). */
        const val LOG_PERIOD = 0.1f
        /** The rail's latency marker: the rate crossing this flashes a 2 px tick at mid-rail. */
        const val HALF_MARK = 0.5f
        /** How long that flash lasts, seconds of REAL time — it is a HUD event, not a world one. */
        const val HALF_FLASH_T = 0.35f
        /** `STILL` appears beside the rail after this long under [STILL_RATE] (DESIGN.md §2.8). */
        const val STILL_T = 1.0f
        const val STILL_RATE = 0.10f

        /**
         * The frame clamp, seconds. `GLRenderer` already clamps to exactly this before it calls
         * us, so on the glasses this line never fires; it is here because [worldT] is the one
         * number in the game with no way back. A single unclamped frame — a resumed activity, a
         * GC pause, a debug harness driving the clock by hand — would hand his glove a teleport,
         * and a single non-finite one would leave `worldT` NaN for the rest of the run, silently
         * killing the round clock and every comparison that reads it.
         */
        private const val MAX_DT = 0.05f
    }

    /** Which pair of knee constants the body is being charged at. B ships; A stays for the lab row. */
    enum class Knee { A, B }

    /** Everything that spends a quantum. One meaning per pad verb per state (DESIGN.md §1.7). */
    enum class Verb { JAB, HOOK, SPECIAL, GUARD, STEP, EMPTY }

    /**
     * The states in which the clock is NOT the body's to spend (DESIGN.md §2.4). They are not
     * exceptions to the law so much as its other half: a step and the corner move the world
     * bodily; a punch is a swing that takes time; his strike is in flight and not negotiable; a
     * hit-stop is the impact frame; the fall is a story beat you watch; the count belongs to the
     * referee, who is not inside your fight; and the menu is outside the fiction.
     */
    enum class Forced { NONE, STEP, CORNER, MENU, PUNCH, STRIKE, HITSTOP, SLOW, COUNT }

    // ------------------------------------------------------------------ configuration
    /** Set B by measurement (MOTION.md). The lab's `KNEE` row is the only thing that should move this. */
    var knee = Knee.B
    val wRef: Float get() = if (knee == Knee.A) W_REF_A else W_REF_B
    val gamma: Float get() = if (knee == Knee.A) GAMMA_A else GAMMA_B

    /** 0 = EASY, 1 = NORMAL, 2 = HARD — the same indices `SettingsStore.difficulty` uses. */
    var difficulty = 1
    /** An index into [LAB_FLOORS], or [LAB_FLOOR_OFF] to follow [difficulty]. Lab builds only. */
    var labFloor = LAB_FLOOR_OFF
    /** How long a step forces rate 1.0 — the lab's ladder, [STEP_T_CHOICES]. */
    var stepT = STEP_T_DEFAULT

    /**
     * THE FLOOR IS THE BOXER'S STATE (DESIGN.md §2.2). The fight writes this every frame; a
     * negative value means "nobody is overriding: use the difficulty or lab floor". It is a
     * plain field and not a setter with side effects because it is written sixty times a second
     * by whoever owns the fight, and the only thing it may do is be read by [floor].
     */
    var floorOverride = -1f

    /** The difficulty or lab floor — what [floor] falls back to with no override in force. */
    val baseFloor: Float
        get() = if (labFloor in LAB_FLOORS.indices) LAB_FLOORS[labFloor] else FLOOR_STILL

    /** The floor the world creeps at when the player is perfectly still, override first. */
    val floor: Float
        get() = if (floorOverride >= 0f) floorOverride.coerceIn(0f, 1f) else baseFloor

    // ------------------------------------------------------------------ this frame
    /** How fast the world ran this frame, 0..1. The left rail IS this number. */
    var timeScale = 0f; private set
    /** `dt × timeScale`: the seconds every hostile thing may advance by. Nothing else may use it. */
    var wdt = 0f; private set
    /** The real seconds of the last frame, clamped by the renderer — captions and cues run on this. */
    var dt = 0f; private set
    /** The WORLD CLOCK on the plate: seconds the world has been allowed to have. The round clock is on it. */
    var worldT = 0f; private set
    /** Real seconds since the round began — the 3:00 cap and the time bonus read this. */
    var realT = 0f; private set
    /** The smoothed body scalar this frame, as handed in — kept for the `CLK` line and the lab plate. */
    var motion = 0f; private set
    /** The action charge: what the hands have added to the body's motion. Decays on real time. */
    var act = 0f; private set
    /** `clamp(motion + act, 0, 1)`: the whole input to the map. */
    var m = 0f; private set

    /** Which forced state (if any) overrode the body this frame. */
    var forced = Forced.NONE; private set
    /**
     * The timed half of [forced] — STEP, CORNER, PUNCH, STRIKE, SLOW or COUNT, one at a time. It
     * is kept apart from the public field because the menu is a LATCH and these are TIMERS:
     * opening the settings mid-strike must not cancel the strike, and the strike expiring must
     * not close the menu.
     */
    private var timed = Forced.NONE
    private var forcedT = 0f
    /** The rate a SLOW state runs at — 0.22 for a knockdown, 0.10 for the KO. */
    private var slowRate = SLOW_KNOCKDOWN_RATE
    /**
     * THE HIT-STOP IS A PAUSE, NOT A TIMER IN THE SLOT. It sits between the menu latch and the
     * timed state and HOLDS whatever timer is running, for the same reason the menu does: a
     * punch that lands inside his strike must not eat the strike, and a special's 200 ms must
     * not shorten the recover it opened. The render loop never stops — only the clocks (§2.5).
     */
    private var hitstopT = 0f
    /** The pulse a PUNCH window pays out when it closes naturally — see [forcePunch]. */
    private var tail: Verb? = null
    /** True while the settings menu is up: rate 0, no quantum, and the fight is paused. */
    var menuOpen = false

    /** True for exactly the frame in which [timeScale] crossed [HALF_MARK] in either direction. */
    var crossedHalf = false; private set
    /** 1 → 0 over [HALF_FLASH_T] real seconds after that crossing: the rail tick's brightness. */
    var halfFlash = 0f; private set
    /**
     * How long the rate has been under [STILL_RATE] with NO forced state in force; past
     * [STILL_T] the rail says `STILL` and the fight's stall pressure keys off it (DESIGN.md §2.8:
     * still while he is IDLE for 3 real seconds → the boo, the shorter tell, the half-peck).
     *
     * REAL seconds, which is why this accumulates [dt] and not [wdt]; and it is HELD — neither
     * inflated nor reset — under the menu and under every forced state, because a hit-stop, a
     * count or a fall at rate 0 is not the player standing still, and a minute in the settings
     * must not make the crowd boo the instant the panel closes.
     */
    var stillT = 0f; private set
    val still: Boolean get() = stillT >= STILL_T

    /** The τ the current [act] charge is decaying on — set by the most recent verb (see [pulse]). */
    private var actTau = TAU_JAB
    private var logT = 0f

    /**
     * Where the `CLK` line goes. `MainActivity` sets this to `{ Log.i("X3Knockout", it) }`; a unit
     * test leaves it null. See the class note on why this is a sink and not a direct call.
     */
    var log: ((String) -> Unit)? = null

    // ------------------------------------------------------------------ the verbs
    /**
     * Spend a quantum. The newest pulse also takes over the decay constant rather than each verb
     * keeping its own envelope: two verbs inside a quarter of a second are one action as far as
     * the body is concerned, and a single scalar is the thing the rail can honestly draw.
     *
     * Charges are capped at 1.0 rather than summed, so a flurry cannot bank a second of world
     * time to be spent standing still afterwards. A jab's 1.0 at τ 0.20 is worth ≈ 0.20 s of
     * world time above the floor (DESIGN.md §2.3's table is the integral of the decay, not a
     * separate number); the empty tap's 0.2 at τ 0.15 is worth ≈ 0.03 s, enough for the player
     * to feel that a wasted tap was still a real one.
     *
     * SPECIAL and STEP pulse NOTHING: both are forced windows for their whole length (0.60 s and
     * [stepT]) and end where they end — the special in a hit-stop, the step on the landing.
     */
    fun pulse(verb: Verb) {
        // DESIGN.md §1.7: the double-tap into settings, and everything done in there, costs
        // nothing. The menu is the one place the game takes input without charging for it.
        if (menuOpen) return
        val (p, tau) = when (verb) {
            Verb.JAB -> PULSE_JAB to TAU_JAB
            Verb.HOOK -> PULSE_HOOK to TAU_HOOK
            Verb.GUARD -> PULSE_GUARD to TAU_GUARD
            Verb.EMPTY -> PULSE_EMPTY to TAU_EMPTY
            Verb.SPECIAL, Verb.STEP -> return
        }
        act = (act + p).coerceAtMost(1f)
        actTau = tau
    }

    /** Rate 1.0 for the length of a step: the world moved you; it moves too (was `forceHop`). */
    fun forceStep(seconds: Float = stepT) { timed = Forced.STEP; forcedT = seconds; tail = null; syncForced() }

    /** Rate 1.0 while the ropes slide you to your corner: when the world moves, time moves (was `forceDock`). */
    fun forceCorner(seconds: Float = CORNER_T) { timed = Forced.CORNER; forcedT = seconds; tail = null; syncForced() }

    /**
     * YOU SWUNG; THE SWING TAKES TIME. Rate 1.0 for [seconds] — [PUNCH_JAB_T], [PUNCH_HOOK_T] or
     * [PUNCH_SPECIAL_T] — and then, if the window closes on its own, [tail] is pulsed so the world
     * keeps running for the punch's follow-through (JAB → τ 0.20, HOOK → τ 0.25). The fight
     * shapes the window afterwards: a WHIFF [extendForced]s it by [WHIFF_EXTEND_T] (the mash
     * tax), a LANDED punch [cutForced]s it to the active frames and drops the tail (landing is
     * cheaper than missing), and a COUNTER inside the perfect window never calls this at all —
     * the world stays at the floor while you throw it (DESIGN.md §2.6).
     */
    fun forcePunch(seconds: Float, tail: Verb? = null) { timed = Forced.PUNCH; forcedT = seconds; this.tail = tail; syncForced() }

    /**
     * Rate 1.0 for his strike — DESIGN.md §2.4's first draft. NO LONGER CALLED BY THE FIGHT: the
     * owner's ruling (BOXER.md's header) has every punch travel on world time, his included, so
     * his glove in flight runs under the boxer's hang-then-fuse floor (`Boxer.floorNow`) instead.
     * The state and its entry point stay — `ClockTest` pins the timed-state mechanics on it and
     * the lab may want a forced strike to A/B the ruling on-head — but a `forced=STRIKE` in a
     * `CLK` line today means somebody put it back.
     */
    fun forceStrike(seconds: Float) { timed = Forced.STRIKE; forcedT = seconds; tail = null; syncForced() }

    /** The fall is a story beat you watch, not a read: [rate] for [seconds] (0.22 / 1.1 s, or the KO's 0.10 / 2.0). */
    fun forceSlow(rate: Float, seconds: Float) { timed = Forced.SLOW; forcedT = seconds; slowRate = rate.coerceIn(0f, 1f); tail = null; syncForced() }

    /** The referee is outside the bubble: rate 0 until the rise calls [clearForced], or ten seconds. */
    fun forceCount(seconds: Float = COUNT_MAX_T) { timed = Forced.COUNT; forcedT = seconds; tail = null; syncForced() }

    /**
     * The impact frame: both clocks stop for [ms] of real time and every timer under it is held.
     * The LONGER stop wins and stops are never summed — a 1-2 that lands twice inside 70 ms is
     * one impact, not a 140 ms freeze.
     */
    fun forceHitstop(ms: Int) { hitstopT = max(hitstopT, ms / 1000f); syncForced() }

    /** Lengthen the running timer — the whiff's [WHIFF_EXTEND_T]. No timer, no effect. */
    fun extendForced(seconds: Float) { if (timed != Forced.NONE) forcedT += seconds }

    /**
     * Shorten the running timer to at most [seconds] from now and drop its tail — a landed punch
     * is cut to its active frames and its recovery is cancellable (DESIGN.md §2.3).
     */
    fun cutForced(seconds: Float) {
        if (timed == Forced.NONE) return
        forcedT = min(forcedT, seconds); tail = null
        // A window cut to nothing is over NOW, not on the next frame: a timed state with a zero
        // timer would otherwise sit in the slot forever, because the countdown below only runs
        // while there is something left to count.
        if (forcedT <= 0f) { timed = Forced.NONE; forcedT = 0f; syncForced() }
    }

    /**
     * Drop any forced state early — a step cut short by a knockdown, the count ended by the rise,
     * a hit-stop abandoned by a restart. It cannot drop [Forced.MENU]: the menu is closed by
     * clearing [menuOpen], and a caller that could cancel it from here would be able to leave
     * the world running under an open panel.
     */
    fun clearForced() { timed = Forced.NONE; forcedT = 0f; tail = null; hitstopT = 0f; syncForced() }

    /** Real seconds left on the timed state (0 when none) — the lab plate's `FORCED` row and the tests. */
    val forcedLeft: Float get() = if (timed == Forced.NONE) 0f else forcedT.coerceAtLeast(0f)
    /** Real seconds left on the hit-stop pause (0 when none). */
    val hitstopLeft: Float get() = hitstopT.coerceAtLeast(0f)
    /** The timed state itself, MENU and HITSTOP seen through — what will resume when they lift. */
    val timedState: Forced get() = timed

    /** A new round: the world clock, the real clock, the still counter and the override all start again. */
    fun resetRound() {
        worldT = 0f; realT = 0f; act = 0f; m = 0f; motion = 0f; stillT = 0f
        crossedHalf = false; halfFlash = 0f; logT = 0f; floorOverride = -1f; clearForced()
        // Start the rail where a standing player starts, not at the 1.0 the corner just left it
        // at: otherwise the first frame of the round reads as a crossing of 0.5 and the latency
        // tick flashes at a player who has not moved.
        timeScale = floor; wdt = 0f
    }

    /** MENU latches over HITSTOP, which pauses over whichever timer is running; nothing else may write [forced]. */
    private fun syncForced() { forced = if (menuOpen) Forced.MENU else if (hitstopT > 0f) Forced.HITSTOP else timed }

    // ------------------------------------------------------------------ the frame
    /**
     * Advance the clock. Call once per frame, AFTER `MotionTracker.update` and BEFORE anything
     * reads [wdt].
     *
     * @param dt        real seconds, already clamped to 0.05 by the renderer.
     * @param motion    the smoothed body scalar 0..1 from `MotionTracker`.
     * @param angSpeed  |ω| rad/s, for the `CLK` line only — the clock does not re-derive the map.
     * @param step      -1 / 0 / +1, the step recognised this frame, for the `CLK` line only.
     * @param blankMsLeft milliseconds of pad blanking left, for the `CLK` line only.
     */
    fun update(dt: Float, motion: Float, angSpeed: Float = 0f, step: Int = 0, blankMsLeft: Long = 0L) {
        // The one entry point, so the one place worth defending. See MAX_DT.
        val d = if (dt.isFinite()) dt.coerceIn(0f, MAX_DT) else 0f
        val mo = if (motion.isFinite()) motion.coerceIn(0f, 1f) else 0f
        this.dt = d
        this.motion = mo
        realT += d

        // the action charge decays on REAL time: an act is paid for once, and standing still
        // afterwards does not refund it
        if (act > 0f) {
            act *= exp(-d / actTau)
            if (act < 1e-4f) act = 0f
        }
        m = (mo + act).coerceIn(0f, 1f)

        // THE FORCED TIMERS RUN ON REAL TIME. A punch is 0.28 s of the player's own life, and a
        // punch counted on world time could never end anyway — it is the thing holding the rate
        // up, so it would be counting its own output. They are held while the menu is open and
        // while a hit-stop is in force, for the same reason the world is: a strike interrupted by
        // the settings panel, or by the punch that landed inside it, resumes where it left off.
        if (!menuOpen) {
            if (hitstopT > 0f) {
                hitstopT -= d
                if (hitstopT <= 0f) hitstopT = 0f
            } else if (timed != Forced.NONE) {
                forcedT -= d
                if (forcedT <= 0f) {
                    val t = tail
                    timed = Forced.NONE; forcedT = 0f; tail = null
                    // the follow-through: paid only when the window ran its course (a landed
                    // punch was cut and dropped its tail; a whiff kept it)
                    if (t != null) pulse(t)
                }
            }
        }
        syncForced()

        val prev = timeScale
        timeScale = when (forced) {
            Forced.MENU -> 0f                                          // outside the fiction; nothing runs
            Forced.HITSTOP -> 0f                                       // the impact frame
            Forced.COUNT -> 0f                                         // the referee is outside the bubble
            Forced.STEP, Forced.CORNER, Forced.PUNCH, Forced.STRIKE -> 1f   // not the body's to spend
            Forced.SLOW -> slowRate                                    // the fall, watched
            Forced.NONE -> floor + (1f - floor) * m                    // the law
        }
        wdt = d * timeScale
        worldT += wdt

        // the latency marker: the tick the owner watches to say "the world stopped when I did"
        crossedHalf = (prev < HALF_MARK) != (timeScale < HALF_MARK)
        if (crossedHalf) halfFlash = 1f
        else if (halfFlash > 0f) halfFlash = (halfFlash - d / HALF_FLASH_T).coerceAtLeast(0f)

        if (!menuOpen && forced == Forced.NONE) stillT = if (timeScale < STILL_RATE) stillT + d else 0f

        // 10 Hz, carrying the remainder rather than dropping it. Zeroing the accumulator instead
        // would throw away the overshoot — half a frame on average — and the line would arrive
        // every 108 ms on a jittery frame time. The review reads elapsed time off the CLK lines,
        // so a period that is quietly 8 % long is a measurement error, not a cosmetic one.
        logT += d
        if (logT >= LOG_PERIOD) {
            logT -= LOG_PERIOD
            if (logT >= LOG_PERIOD) logT = 0f            // a stall, not a rhythm: do not burst
            log?.invoke(clkLine(angSpeed, step, blankMsLeft))
        }
    }

    /**
     * The 10 Hz telemetry line of DESIGN.md §13 — the review process reads this, so keep the keys.
     * `floor=` shows the override in force and `forced=` carries the new names (PUNCH / STRIKE /
     * HITSTOP / SLOW / COUNT / STEP / CORNER / MENU) so the tooling keeps parsing.
     *
     * Formatted in [Locale.US] on purpose: the default locale would write `rate=0,53` on a
     * comma-decimal device and every parser in `tools/` would read the line as garbage — a
     * failure that would appear only after a language change, and only in the data.
     */
    fun clkLine(angSpeed: Float, step: Int, blankMsLeft: Long): String =
        "CLK |w|=%.2f m=%.2f act=%.2f rate=%.2f floor=%.2f step=%+d blank=%d knee=%s%s".format(
            Locale.US, angSpeed, m, act, timeScale, floor, step, blankMsLeft, knee.name,
            if (forced == Forced.NONE) "" else " forced=" + forced.name)

    /**
     * The map itself, exposed so the lab can plot what a given head speed would cost under either
     * knee without waiting for the body to produce it. `|ω|` in rad/s, `|a|` in m/s².
     *
     * This is the same arithmetic `MotionTracker` runs per frame, which is the point: it is what
     * pins the MOTION.md ruling in `ClockTest` (an ordinary 42.6°/s scan reads 0.20 under Set B
     * and 0.53 under Set A) without a sensor or a head in the loop. Whichever of the two is
     * edited, the other must follow.
     */
    /**
     * THE AXIS SPLIT: the one angular rate the clock is allowed to charge (LAW.md §1.3).
     *
     * `sqrt(wx² + wy² + wz²)` cannot tell "I slipped my head off the line of that punch" from
     * "I turned to look at the man", and it charged the same for both. That was survivable while
     * the opponent stood on one spot; it is not survivable now that he has feet, because a man
     * who circles MAKES the player turn their head and a raw-magnitude clock then bills them for
     * it. On the shipping constants, tracking the Sardine's dart would charge rate 0.123 against
     * a floor of 0.03, and tracking the Metronome 0.437 — fourteen times the floor, paid
     * continuously, for keeping the man who is punching you in frame. The mechanic upside down.
     *
     * So: yaw ([wy]) is LOOKING, at [K_YAW]; pitch ([wx], the duck) and roll ([wz], the slip) are
     * DODGING, at full weight, because those two ARE the dodge collider. An ordinary 42.6°/s scan
     * (MOTION.md "The knee", measured on the owner) lands at 0.111 rad/s — under [DEAD_W], so it
     * is free — while the same 42.6°/s spent as a slip costs the whole of it. Same head speed,
     * two different meanings, correctly priced. A 300°/s panic whip still costs rate 0.20.
     *
     * `MotionTracker` calls this per gyro sample; `ClockTest` pins it without a head.
     */
    fun target(angSpeed: Float, accelMag: Float): Float {
        val mw = ((angSpeed - DEAD_W) / (wRef - DEAD_W)).coerceIn(0f, 1f)
        val ma = ((abs(accelMag) - DEAD_A) / (A_REF - DEAD_A)).coerceIn(0f, 1f)
        val t = max(mw, ma)
        return if (gamma == 1f) t else t.pow(gamma)
    }
}
