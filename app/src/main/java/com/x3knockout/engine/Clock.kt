package com.x3knockout.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * THE CLOCK. One law, in one file: **time moves only when you move.**
 *
 * `MotionTracker` measures the body and hands over a single scalar, [Clock.update]'s `motion`
 * (0..1). This class turns that into the only two numbers the rest of the game is allowed to ask
 * for — [timeScale] and [wdt] — and owns everything that legitimately bends them: the action
 * quanta (acting always spends time), the floor (a stopped world is a screenshot), the three
 * forced states (a hop, a docking cell and the settings menu are not negotiable), the world clock
 * [worldT], the 10 Hz `CLK` telemetry line, and the rail's 0.5-crossing flag that the latency test
 * reads (DESIGN.md §13, test A3).
 *
 * **This file imports nothing from Android, and it must stay that way.** The whole two-clock
 * promise — every hostile timer on world time, every caption and cue on real time (DESIGN.md
 * §1.5) — is a claim about arithmetic, and the only cheap way to keep proving it through twelve
 * levels of churn is a JVM unit test (`ClockTest`). A single `android.util.Log` or `SystemClock`
 * in here costs that test, so the log goes out through [log] and the caller supplies the sink.
 * (`java.util.Locale` below is JVM, not Android, and is here for a reason of its own — see
 * [clkLine].)
 *
 * It also owns the KNEE, because the knee is a clock decision and not a sensor one. The measured
 * ruling in MOTION.md is already made — **Set B ships** ([Knee.B]: `W_REF` 1.9, `GAMMA` 1.8), so
 * that an ordinary reading scan of the room costs 0.20 of the clock instead of Set A's 0.53 and a
 * real snap still saturates. Clock holds the numbers; whoever owns `MotionTracker` copies [wRef]
 * and [gamma] into `MotionTracker.W_REF` / `MotionTracker.GAMMA` whenever the lab's `KNEE` row
 * moves, because that is the direction that keeps this file pure. **That copy is load-bearing**:
 * [update] takes the mapped scalar and does not re-derive it, so a build that forgets the wire
 * ships Set A's curve while [target] and the `CLK` line's `knee=` both claim Set B, and A2 —
 * the test the design lives or dies on — is measuring the wrong thing without saying so.
 *
 * There is deliberately **no anti-wiggle patch** in here. A head-shake buys time because it is
 * motion; what stops it being an exploit is the shape of the curve itself (monotonic, saturating)
 * plus a `RELEASE` longer than a half-shake and a throw spread that grows with `m` — DESIGN.md
 * §1.4. Anything that special-cased a reversal would be a second, hidden clock.
 */
class Clock {

    companion object {
        // ---------------------------------------------------------------- the map (DESIGN.md §1.1)
        /** Angular speed below which the clock stays at its floor, rad/s: rest noise and breathing buy nothing. */
        const val DEAD_W = 0.08f
        /** Linear acceleration dead band, m/s². */
        const val DEAD_A = 0.25f
        /** Body acceleration that counts as fully moving, m/s². A brisk step peaks at 3–5. */
        const val A_REF = 2.2f
        /** Smoothing of the motion scalar, seconds. Fast in, slow out: motion is spent, not banked. */
        const val ATTACK = 0.04f
        /**
         * Longer than a half head-shake (~100 ms), which is what makes the anti-wiggle rule work:
         * a reversal never hands back a free frozen frame (DESIGN.md §1.4, test A4).
         */
        const val RELEASE = 0.16f

        /** Set A — the prototype's calibrated pair, kept only so the lab can A/B it. */
        const val W_REF_A = 1.2f
        const val GAMMA_A = 1.3f
        /** Set B — SHIPS. Measured on the owner's recorded fight, MOTION.md "The knee". */
        const val W_REF_B = 1.9f
        const val GAMMA_B = 1.8f

        // ---------------------------------------------------------------- the floor (DESIGN.md §1.2)
        const val FLOOR_EASY = 0.03f
        const val FLOOR_NORMAL = 0.05f
        const val FLOOR_HARD = 0.08f
        /** The lab's override ladder, 3 / 5 / 8 / 12 / 20 % — lab only; off the lab the floor follows difficulty. */
        val LAB_FLOORS = floatArrayOf(0.03f, 0.05f, 0.08f, 0.12f, 0.20f)
        /** [labFloor] set to this means "follow difficulty", which is what a shipped build does. */
        const val LAB_FLOOR_OFF = -1

        // ---------------------------------------------------------------- the quanta (DESIGN.md §1.3)
        // The honest answer to "give me a button that advances time": you can always make the
        // world move by ACTING, never by waiting. Each verb adds its pulse to `act`, which decays
        // on REAL time — so the cost is paid whether or not the player then stands still.
        const val PULSE_THROW = 1.0f
        const val TAU_THROW = 0.25f
        const val PULSE_DEFLECT = 0.5f
        const val TAU_DEFLECT = 0.20f
        const val PULSE_CATCH = 0.3f
        const val TAU_CATCH = 0.15f
        const val PULSE_RECALL = 0.5f
        const val TAU_RECALL = 0.20f
        /** The trigger-on-empty rule: a tap with an empty rack is still an act, and still costs. */
        const val PULSE_EMPTY = 0.2f
        const val TAU_EMPTY = 0.15f

        // ---------------------------------------------------------------- forced states
        /** The hop's default duration, seconds; the lab offers 0.28 / 0.35 / 0.45 / 0.60 (test B4). */
        const val HOP_T_DEFAULT = 0.35f
        val HOP_T_CHOICES = floatArrayOf(0.28f, 0.35f, 0.45f, 0.60f)
        /** The cell's slide between rounds, seconds: when the world moves, time moves. */
        const val DOCK_T = 2.2f

        // ---------------------------------------------------------------- telemetry
        /** The `CLK` line's period, seconds (10 Hz). */
        const val LOG_PERIOD = 0.1f
        /** The rail's latency marker: the rate crossing this flashes a 2 px tick at mid-rail. */
        const val HALF_MARK = 0.5f
        /** How long that flash lasts, seconds of REAL time — it is a HUD event, not a world one. */
        const val HALF_FLASH_T = 0.35f
        /** `STILL` appears beside the rail after this long under [STILL_RATE] (DESIGN.md §9). */
        const val STILL_T = 1.0f
        const val STILL_RATE = 0.10f

        /**
         * The frame clamp, seconds. `GLRenderer` already clamps to exactly this before it calls
         * us, so on the glasses this line never fires; it is here because [worldT] is the one
         * number in the game with no way back. A single unclamped frame — a resumed activity, a
         * GC pause, a debug harness driving the clock by hand — would hand every hostile disc a
         * teleport, and a single non-finite one would leave `worldT` NaN for the rest of the run,
         * silently killing PAR, the par bonus and every comparison that reads them (NaN fails
         * every comparison, so nothing would ever trip).
         */
        private const val MAX_DT = 0.05f
    }

    /** Which pair of knee constants the body is being charged at. B ships; A stays for the lab row. */
    enum class Knee { A, B }

    /** Everything that spends a quantum. One meaning per pad verb per state (DESIGN.md §10). */
    enum class Verb { THROW, DEFLECT, CATCH, RECALL, EMPTY }

    /**
     * The three states in which the clock is NOT the body's to spend. They are not exceptions to
     * the law so much as its other half: a hop and a docking cell move the world bodily, so the
     * world's own time runs; the menu is outside the fiction, so nothing runs at all.
     */
    enum class Forced { NONE, HOP, DOCKING, MENU }

    // ------------------------------------------------------------------ configuration
    /** Set B by measurement (MOTION.md). The lab's `KNEE` row is the only thing that should move this. */
    var knee = Knee.B
    val wRef: Float get() = if (knee == Knee.A) W_REF_A else W_REF_B
    val gamma: Float get() = if (knee == Knee.A) GAMMA_A else GAMMA_B

    /** 0 = EASY, 1 = NORMAL, 2 = HARD — the same indices `SettingsStore.difficulty` uses. */
    var difficulty = 1
    /** An index into [LAB_FLOORS], or [LAB_FLOOR_OFF] to follow [difficulty]. Lab builds only. */
    var labFloor = LAB_FLOOR_OFF
    /** How long a hop forces rate 1.0 — set from the lab's `HOP T` row. */
    var hopT = HOP_T_DEFAULT

    /** The floor the world creeps at when the player is perfectly still. */
    val floor: Float
        get() = if (labFloor in LAB_FLOORS.indices) LAB_FLOORS[labFloor]
        else when (difficulty) { 0 -> FLOOR_EASY; 2 -> FLOOR_HARD; else -> FLOOR_NORMAL }

    // ------------------------------------------------------------------ this frame
    /** How fast the world ran this frame, 0..1. The left rail IS this number. */
    var timeScale = 0f; private set
    /** `dt × timeScale`: the seconds every hostile thing may advance by. Nothing else may use it. */
    var wdt = 0f; private set
    /** The real seconds of the last frame, clamped by the renderer — captions and cues run on this. */
    var dt = 0f; private set
    /** The WORLD CLOCK on the plate: seconds the world has been allowed to have. PAR is measured on it. */
    var worldT = 0f; private set
    /** Real seconds since the round began — shown small and unscored, for the owner's time trials. */
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
     * The timed half of [forced] — [Forced.HOP] or [Forced.DOCKING]. It is kept apart from the
     * public field because the menu is a LATCH and these two are TIMERS: opening the settings
     * mid-hop must not cancel the hop, and the hop expiring must not close the menu.
     */
    private var timed = Forced.NONE
    private var forcedT = 0f
    /** True while the settings menu is up: rate 0, no quantum, and the Protocol is paused. */
    var menuOpen = false

    /** True for exactly the frame in which [timeScale] crossed [HALF_MARK] in either direction. */
    var crossedHalf = false; private set
    /** 1 → 0 over [HALF_FLASH_T] real seconds after that crossing: the rail tick's brightness. */
    var halfFlash = 0f; private set
    /**
     * How long the rate has been under [STILL_RATE]; past [STILL_T] the rail says `STILL`.
     *
     * It keeps counting well past that, because it is also the trigger every stillness beat in
     * STORY.md is keyed on — `l1_stomp` at 4 real s, `hero_take_time` at 15, `still_waiting` at
     * 20, the Level 2 mercy schedule at 3 / 6 / 9. Those are all REAL seconds, which is why this
     * accumulates [dt] and not [wdt]; and it is why the menu must neither inflate nor reset it
     * (see [update]) — a minute spent in the settings would otherwise make the machine accuse the
     * player of defiance the instant the panel closed.
     */
    var stillT = 0f; private set
    val still: Boolean get() = stillT >= STILL_T

    /** The τ the current [act] charge is decaying on — set by the most recent verb (see [pulse]). */
    private var actTau = TAU_THROW
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
     * Charges are capped at 1.0 rather than summed, so a rack emptied in three taps cannot bank a
     * second of world time to be spent standing still afterwards. A throw's 1.0 at τ 0.25 is
     * worth ≈ 0.24 s of world time above the floor (DESIGN.md §1.3's table is the integral of the
     * decay, not a separate number); the empty tap's 0.2 at τ 0.15 is worth ≈ 0.03 s, which is
     * enough for the player to feel that a wasted tap was still a real one.
     */
    fun pulse(verb: Verb) {
        // DESIGN.md §10: the double-tap into settings, and everything done in there, costs
        // nothing. The menu is the one place the game takes input without charging for it.
        if (menuOpen) return
        val (p, tau) = when (verb) {
            Verb.THROW -> PULSE_THROW to TAU_THROW
            Verb.DEFLECT -> PULSE_DEFLECT to TAU_DEFLECT
            Verb.CATCH -> PULSE_CATCH to TAU_CATCH
            Verb.RECALL -> PULSE_RECALL to TAU_RECALL
            Verb.EMPTY -> PULSE_EMPTY to TAU_EMPTY
        }
        act = (act + p).coerceAtMost(1f)
        actTau = tau
    }

    /** Rate 1.0 for the length of a hop: the biggest honest cost in the game, and the reason it is safe. */
    fun forceHop(seconds: Float = hopT) { timed = Forced.HOP; forcedT = seconds; syncForced() }

    /** Rate 1.0 while the cell slides to meet the next configuration: when the world moves, time moves. */
    fun forceDock(seconds: Float = DOCK_T) { timed = Forced.DOCKING; forcedT = seconds; syncForced() }

    /**
     * Drop any forced state early — a hop cut short by a death, a dock skipped by a restart.
     * It cannot drop [Forced.MENU]: the menu is closed by clearing [menuOpen], and a caller that
     * could cancel it from here would be able to leave the world running under an open panel.
     */
    fun clearForced() { timed = Forced.NONE; forcedT = 0f; syncForced() }

    /** A new round: the world clock, the par timer and the still counter all start again. */
    fun resetRound() {
        worldT = 0f; realT = 0f; act = 0f; m = 0f; motion = 0f; stillT = 0f
        crossedHalf = false; halfFlash = 0f; logT = 0f; clearForced()
        // Start the rail where a standing player starts, not at the 1.0 a docking cell just left
        // it at: otherwise the first frame of the round reads as a crossing of 0.5 and the
        // latency tick flashes at a player who has not moved.
        timeScale = floor; wdt = 0f
    }

    /** The menu latches over whichever timer is running; nothing else may write [forced]. */
    private fun syncForced() { forced = if (menuOpen) Forced.MENU else timed }

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

        // The forced timers run on REAL time. A hop is 0.35 s of the player's own life, and a hop
        // counted on world time could never end anyway — it is the thing holding the rate up, so
        // it would be counting its own output. They are held while the menu is open for the same
        // reason the world is: a hop interrupted by the settings panel resumes where it left off.
        if (!menuOpen && forcedT > 0f) {
            forcedT -= d
            if (forcedT <= 0f) { timed = Forced.NONE; forcedT = 0f }
        }
        syncForced()

        val prev = timeScale
        timeScale = when (forced) {
            Forced.MENU -> 0f                                // outside the fiction; nothing runs
            Forced.HOP, Forced.DOCKING -> 1f                 // the world moved you; it moves too
            Forced.NONE -> floor + (1f - floor) * m          // the law (DESIGN.md §1.1)
        }
        wdt = d * timeScale
        worldT += wdt

        // the latency marker: the tick the owner watches to say "the world stopped when I did"
        crossedHalf = (prev < HALF_MARK) != (timeScale < HALF_MARK)
        if (crossedHalf) halfFlash = 1f
        else if (halfFlash > 0f) halfFlash = (halfFlash - d / HALF_FLASH_T).coerceAtLeast(0f)

        if (!menuOpen) stillT = if (timeScale < STILL_RATE) stillT + d else 0f

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
     * `knee=` is BUILD_PLAN §1.1's requirement (the lab row must be visible in the log that A2 is
     * judged from) and `forced=` is how B4 tells a hop's 0.35 s from a body that simply moved.
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
    fun target(angSpeed: Float, accelMag: Float): Float {
        val mw = ((angSpeed - DEAD_W) / (wRef - DEAD_W)).coerceIn(0f, 1f)
        val ma = ((abs(accelMag) - DEAD_A) / (A_REF - DEAD_A)).coerceIn(0f, 1f)
        val t = max(mw, ma)
        return if (gamma == 1f) t else t.pow(gamma)
    }
}
