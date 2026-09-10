package com.x3knockout.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CLOCK'S FIGHT SURFACE, PROVED ON THE JVM (TEST.md §1: "ClockTest — the floor override;
 * PUNCH / STRIKE / HITSTOP / SLOW / COUNT timers run on real time and hold under MENU; clearForced
 * cannot drop MENU").
 *
 * DESIGN.md §2.9 splits every timer in the game onto exactly one of two lists, and §15.23 names
 * the way that split fails — one hostile timer left on real time silently breaks the promise the
 * whole game rests on. Neither failure shows up as a crash, so the audit lives here rather than in
 * a review round. The knee ruling of MOTION.md is pinned here too: a well-meaning change to
 * `W_REF` fails here instead of on someone's head.
 */
class ClockTest {

    private val DT = 1f / 60f

    private fun Clock.run(seconds: Float, motion: Float = 0f) {
        var t = 0f
        while (t < seconds - 1e-4f) { update(DT, motion); t += DT }
    }

    @Test
    fun theWorldRunsAtOneWhateverTheBodyDoes() {
        // THE LAW IS GONE (2026-09-10) and this is the test that keeps it gone. It used to assert
        // the opposite of every line below: that a still body bought a 0.03 world and a moving
        // one bought 1.0. If a motion term is ever reintroduced by accident, this goes red.
        val c = Clock(); c.difficulty = 1
        c.update(DT, 0f)
        assertEquals("a still player: full speed", 1f, c.timeScale, 1e-6f)
        c.update(DT, 1f)
        assertEquals("a moving player: the same full speed", 1f, c.timeScale, 1e-6f)
        c.floorOverride = 0.06f
        c.update(DT, 0f)
        assertEquals("and the boxer can no longer slow it either", 1f, c.timeScale, 1e-6f)
        for (d in 0..2) { c.difficulty = d; c.update(DT, 0f); assertEquals("difficulty $d", 1f, c.timeScale, 1e-6f) }
    }

    @Test
    fun theWorldStopsONLYForSomethingYouCanSee() {
        // What survives the law's removal, and why: each of these stops the world for a reason
        // that is on the glass. A pause, an impact frame, the referee counting, a man falling.
        val c = Clock(); c.difficulty = 1
        c.menuOpen = true; c.update(DT, 1f)
        assertEquals("the menu is outside the fiction", 0f, c.timeScale, 1e-6f)
        c.menuOpen = false
        c.forceHitstop(200); c.update(DT, 1f)
        assertEquals("the impact frame", 0f, c.timeScale, 1e-6f)
        c.clearForced()
        c.forceCount(); c.update(DT, 1f)
        assertEquals("the referee is outside the fight", 0f, c.timeScale, 1e-6f)
        c.clearForced()
        c.forceSlow(0.1f, 2f); c.update(DT, 0f)
        assertEquals("the fall, watched", 0.1f, c.timeScale, 1e-6f)
        c.clearForced(); c.update(DT, 0f)
        assertEquals("and then straight back to full speed", 1f, c.timeScale, 1e-6f)
    }

    @Test
    fun aPunchStillRunsItsWindowOnRealTime() {
        // The window itself outlived the law it was invented for: it is what stops a mashed tap
        // from being a second punch, and `Fight` still reads `forcedLeft` for the mash tax.
        val c = Clock(); c.difficulty = 1
        c.forcePunch(Clock.PUNCH_JAB_T, Clock.Verb.JAB)
        c.update(DT, 0f)
        assertEquals(Clock.Forced.PUNCH, c.forced)
        assertEquals(1f, c.timeScale, 1e-6f)
        c.run(Clock.PUNCH_JAB_T)
        assertEquals("the window closed on real time", Clock.Forced.NONE, c.forced)
        assertEquals("...and the world was never anywhere else", 1f, c.timeScale, 1e-6f)
    }

    @Test
    fun aLandedPunchIsCutShort() {
        val c = Clock(); c.difficulty = 1
        c.forcePunch(Clock.PUNCH_JAB_T, Clock.Verb.JAB)
        c.run(0.10f)
        c.cutForced(0f)
        c.update(DT, 0f)
        assertEquals("cut to the active frames: landing is cheaper than missing", Clock.Forced.NONE, c.forced)
    }

    @Test
    fun aWhiffExtendsTheWindow() {
        val c = Clock(); c.difficulty = 1
        c.forcePunch(Clock.PUNCH_JAB_T)
        c.extendForced(Clock.WHIFF_EXTEND_T)
        assertEquals(Clock.PUNCH_JAB_T + Clock.WHIFF_EXTEND_T, c.forcedLeft, 1e-6f)
    }

    @Test
    fun theHitstopStopsBothClocksAndHoldsTheStrike() {
        val c = Clock(); c.difficulty = 1; c.floorOverride = 0.35f
        c.forceStrike(Clock.STRIKE_PECK_T)
        c.run(0.10f)
        val left = c.forcedLeft
        c.forceHitstop(Clock.HITSTOP_JAB_MS)
        val world0 = c.worldT
        // four frames (66.7 ms) sit wholly inside the 70 ms stop; the fifth frame ends it
        repeat(4) {
            c.update(DT, 1f)
            assertEquals("the impact frame: rate 0 whatever the body does", 0f, c.wdt, 0f)
            assertEquals(Clock.Forced.HITSTOP, c.forced)
        }
        assertEquals("no world time passes inside a hit-stop", world0, c.worldT, 1e-6f)
        assertEquals("the strike under it is HELD, not eaten", left, c.forcedLeft, 1e-3f)
        c.update(DT, 0f)
        assertEquals("...and resumes", Clock.Forced.STRIKE, c.forced)
        assertEquals(1f, c.timeScale, 1e-6f)
    }

    @Test
    fun theLongerHitstopWinsAndStopsAreNeverSummed() {
        val c = Clock()
        c.forceHitstop(70); c.forceHitstop(200); c.forceHitstop(70)
        assertEquals(0.2f, c.hitstopLeft, 1e-6f)
    }

    @Test
    fun slowAndCountRunOnRealTime() {
        val c = Clock(); c.difficulty = 1; c.floorOverride = 0.35f
        c.forceSlow(Clock.SLOW_KNOCKDOWN_RATE, Clock.SLOW_KNOCKDOWN_T)
        c.update(DT, 0f)
        assertEquals("the fall, at a quarter speed", 0.22f, c.timeScale, 1e-6f)
        c.run(Clock.SLOW_KNOCKDOWN_T)
        assertEquals(Clock.Forced.NONE, c.forced)
        c.forceCount()
        c.update(DT, 1f)
        assertEquals("the referee is outside the bubble: rate 0", 0f, c.timeScale, 0f)
        val still0 = c.stillT
        c.run(2f)
        assertEquals("stillness is not counted under a forced state", still0, c.stillT, 0f)
        c.clearForced()
        c.update(DT, 0f)
        assertEquals(Clock.Forced.NONE, c.forced)
    }

    @Test
    fun theMenuLatchesOverEveryTimerAndCannotBeDroppedFromInside() {
        val c = Clock(); c.difficulty = 1
        c.forceStrike(Clock.STRIKE_HOOK_T)
        c.menuOpen = true
        c.run(2f, motion = 1f)
        assertEquals(Clock.Forced.MENU, c.forced)
        assertEquals("outside the fiction: no world time", 0f, c.worldT, 0f)
        assertEquals("the strike is held under the menu", Clock.STRIKE_HOOK_T, c.forcedLeft, 1e-3f)
        c.clearForced()
        c.update(DT, 0f)
        assertEquals("clearForced cannot drop MENU", Clock.Forced.MENU, c.forced)
        c.menuOpen = false
        c.update(DT, 0f)
        assertEquals("the timer was cleared underneath, so nothing resumes", Clock.Forced.NONE, c.forced)
    }

    @Test
    fun stepAndCornerForceRateOne() {
        val c = Clock(); c.difficulty = 1
        c.forceStep(); c.update(DT, 0f)
        assertEquals(Clock.Forced.STEP, c.forced); assertEquals(1f, c.timeScale, 1e-6f)
        c.run(Clock.STEP_T_DEFAULT)
        c.forceCorner(); c.update(DT, 0f)
        assertEquals(Clock.Forced.CORNER, c.forced); assertEquals(1f, c.timeScale, 1e-6f)
    }

    @Test
    fun theKneeShipsSetB() {
        val c = Clock()
        val scan = Math.toRadians(42.6).toFloat()
        val snap = Math.toRadians(133.8).toFloat()
        c.knee = Clock.Knee.B
        val b = c.target(scan, 0f)
        assertEquals("a snap saturates under B", 1f, c.target(snap, 0f), 1e-6f)
        c.knee = Clock.Knee.A
        val a = c.target(scan, 0f)
        assertTrue("an ordinary scan costs less under B ($b) than under A ($a)", b < a * 0.5f)
        assertEquals("B is the default", Clock.Knee.B, Clock().knee)
    }

    @Test
    fun lookingIsFreeAndDodgingIsNot() {
        // LAW.md 1.3, and the prerequisite for footwork: a man who circles MAKES the player turn
        // their head, and a clock charged on the raw gyro magnitude then bills them for it.
        val c = Clock()
        val scan = Math.toRadians(42.6).toFloat()          // MOTION.md's measured p75, on the owner
        val yaw = Clock.omegaEff(0f, scan, 0f)
        val slip = Clock.omegaEff(0f, 0f, scan)
        val duck = Clock.omegaEff(scan, 0f, 0f)
        assertTrue("an ordinary look is under the dead band: it is free", yaw < Clock.DEAD_W)
        assertEquals("the same head speed spent as a slip costs the whole of it", scan, slip, 1e-6f)
        assertEquals("...and as a duck likewise", scan, duck, 1e-6f)
        assertEquals("free means free", 0f, c.target(yaw, 0f), 1e-6f)
        assertTrue("a slip of the same speed is not free", c.target(slip, 0f) > 0.1f)
        val whip = Math.toRadians(300.0).toFloat()
        assertTrue("but attention cannot be teleported for nothing", c.target(Clock.omegaEff(0f, whip, 0f), 0f) > 0.1f)
    }

    @Test
    fun resetRoundClearsTheOverrideAndTheTimers() {
        val c = Clock(); c.difficulty = 1
        c.floorOverride = 0.6f; c.forceCount(); c.forceHitstop(200)
        c.resetRound()
        c.update(DT, 0f)
        assertEquals(Clock.Forced.NONE, c.forced)
        assertEquals(1f, c.timeScale, 1e-6f)
    }
}
