package com.x3knockout.engine

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.x3knockout.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TWO CLOCKS, PROVED ON THE REAL FIGHT (DESIGN.md §2.9, TEST.md §1: "`TwoClocksTest` —
 * DESIGN.md §2.9's list on the real engine").
 *
 * Every timer in the game is on exactly one of two lists, and §15.23 names how the split fails:
 * one hostile timer on real time silently breaks the promise the whole game rests on (his tell
 * completing while the player stands still); one caption on world time freezes the corner.
 * Neither failure is a crash, so the audit is here, on `Fight` itself — the same `Clock`, the
 * same `Boxer`, the same `update` the glasses run — driven by [Rig] with a fed body scalar and a
 * frozen floor (`--ef floor 0.0`, the desk harness's own switch), which is what makes "the world
 * did not move" a number instead of a feeling.
 *
 * THE FRAMEWORK IS KEPT OUT, NOT MOCKED: `SettingsStore` wants a `Context` for two preference
 * files, and the stub `android.jar` under `isReturnDefaultValues` lets a `ContextWrapper(null)`
 * be constructed and hand back [FakePrefs] from the one method that matters. Nothing else in
 * `Fight` touches Android but `Log`, which the stub swallows.
 *
 * The one test that needs the boxer to ACT ([theHangBurnsWhileTheTellHolds]) arms itself the day
 * his interpreter throws a drilled peck; until then it returns rather than fakes, the same idiom
 * `StripSetTest` uses for the strip that is not authored yet.
 */
class TwoClocksTest {

    companion object { private const val DT = Desk.DT }

    /** The harness lives in [Desk] now: one engine rig, two suites. */
    private fun Rig() = Desk.Rig()

    @Test
    fun aPunchForcesTheWorldAndAWhiffIsTaxed() {
        val r = Rig(); r.toFight()
        val c = r.fight.clock
        r.run(0.2f)
        assertEquals("a frozen floor and a still body: the world does not move", 0f, c.wdt, 0f)
        r.fight.punch(Hand.LEFT)
        r.frame()
        assertEquals("the punch is a forced window", Clock.Forced.PUNCH, c.forced)
        assertEquals("...at rate 1.0 however still the body", 1f, c.timeScale, 1e-6f)
        r.run(Fight.JAB_LAND_T + DT)
        assertEquals("a head punch on his closed idle guard is a whiff: a heart", Fight.HEARTS - 1, r.fight.hearts)
        assertEquals("the mash tax: the window was extended by the whiff", Fight.JAB_LAND_T + Clock.WHIFF_EXTEND_T, c.forcedLeft + Fight.JAB_LAND_T - (Clock.PUNCH_JAB_T - Fight.JAB_LAND_T), 0.04f)
        assertEquals(Clock.Forced.PUNCH, c.forced)
    }

    @Test
    fun heartsRefillOnWorldTimeOnly() {
        val r = Rig(); r.toFight()
        r.fight.punch(Hand.LEFT)
        r.run(Fight.JAB_LAND_T + DT)
        assertEquals(Fight.HEARTS - 1, r.fight.hearts)
        // the punch's window, its tax and its tail buy well under a second of world time; then nothing
        r.run(5f)
        assertEquals("five real seconds standing still: a still player does not heal", Fight.HEARTS - 1, r.fight.hearts)
        assertTrue("...because the world barely moved", r.fight.clock.worldT < Fight.HEART_REFILL_T)
        r.motion = 1f
        r.run(1.2f)
        assertEquals("a second of world time: the heart is back", Fight.HEARTS, r.fight.hearts)
    }

    @Test
    fun theCounterWindowAndTheMeterPourAreRealTime() {
        val r = Rig(); r.toFight()
        val f = r.fight
        val world0 = f.clock.worldT
        f.onStrike(Boxer.Attack.PECK_L, Answer.SLIP_R, StrikeResult.PERFECT, 0)
        assertTrue("PERFECT opens the counter window", f.counterWindow > 0f)
        assertEquals("PERFECT: +3 on the meter's target", Fight.METER_PERFECT, f.meter)
        val window0 = f.counterWindow
        r.run(0.3f)
        assertEquals("the world is still frozen", world0, f.clock.worldT, 0f)
        assertEquals("the window ran on REAL time", window0 - 0.3f, f.counterWindow, 0.03f)
        assertTrue("the fill pours on REAL time (1 point per 133 ms)", f.meterShown > 1.8f && f.meterShown < 2.6f)
        r.run(1f)
        assertEquals("...and closes", 0f, f.counterWindow, 0f)
        assertEquals(Fight.METER_PERFECT.toFloat(), f.meterShown, 1e-4f)
    }

    @Test
    fun theRoundClockIsWorldTime() {
        val r = Rig(); r.toFight()
        r.run(2f)
        assertEquals("two real seconds still: the round clock has not moved", Fight.ROUND_WORLD_S, r.fight.roundClock, 1e-4f)
        r.motion = 1f
        r.run(1.5f)
        assertEquals("the round clock is 60 − worldT", Fight.ROUND_WORLD_S - r.fight.clock.worldT, r.fight.roundClock, 1e-4f)
        assertTrue("...and a moving player spent it", r.fight.roundClock < Fight.ROUND_WORLD_S - 1f)
    }

    @Test
    fun theCountRunsOnTheRealClockWithTheWorldFrozen() {
        val r = Rig(); r.toFight()
        val f = r.fight
        f.onKnockdown(1, 4, false)
        assertEquals(State.KNOCKDOWN_COUNT, f.state)
        assertEquals("the fall first: SLOW", Clock.Forced.SLOW, f.clock.forced)
        assertEquals("a knockdown refills the hearts and resets the meter", Fight.HEARTS, f.hearts)
        r.motion = 1f
        r.run(Clock.SLOW_KNOCKDOWN_T + 3 * DT)
        assertEquals("then the referee: COUNT", Clock.Forced.COUNT, f.clock.forced)
        val world0 = f.clock.worldT
        r.run(3.5f)
        assertEquals("the world is frozen under the count however hard the body moves", world0, f.clock.worldT, 0f)
        assertTrue("the numerals land on the real clock", f.countN in 3..4)
        assertTrue("...and are spoken", r.host.said.contains(Lines.ref(3)))
        r.run(1f)
        assertEquals("he rises at four: the fight resumes", State.FIGHT, f.state)
        assertEquals(Clock.Forced.NONE, f.clock.forced)
        assertTrue("the count's music came and went", r.host.tracks.contains(com.x3knockout.audio.Music.COUNT))
    }

    @Test
    fun aLandedPunchIsCutAndStopsBothClocks() {
        val r = Rig(); r.toFight()
        val f = r.fight
        r.pitchG = -0.5f   // a full nod below the rest pitch taken at the bell: AIM LOW, a body blow
        r.run(0.2f)
        assertTrue("the duck arms the body blow", f.body.aimLow)
        f.punch(Hand.LEFT)
        r.run(Fight.JAB_LAND_T + DT)
        assertEquals("a body blow lands through the closed guard", 1, f.hits)
        assertEquals("landing costs no heart", Fight.HEARTS, f.hearts)
        assertEquals("the impact frame: both clocks stop", Clock.Forced.HITSTOP, f.clock.forced)
        assertEquals("the first hit of a sequence: +2", Fight.METER_FIRST, f.meter)
        r.run(Clock.HITSTOP_JAB_MS / 1000f + 2 * DT)
        assertTrue("landing is cheaper than missing: the window was cut, not extended", f.clock.timedState != Clock.Forced.PUNCH)
    }

    @Test
    fun theSpecialIsUpgradedInPlaceAndSpendsTheMeter() {
        val r = Rig(); r.toFight()
        val f = r.fight
        repeat(9) { f.onStrike(Boxer.Attack.PECK_L, Answer.SLIP_R, StrikeResult.PERFECT, 0) }
        assertTrue("nine perfects light the meter", f.meterLit)
        r.run(1.2f)   // the last perfect's counter window closes: the next punch is an ordinary jab
        assertEquals(0f, f.counterWindow, 0f)
        f.punch(Hand.LEFT)
        r.run(2 * DT)
        assertFalse(f.punch!!.special)
        f.punch(Hand.RIGHT, pairMs = 60L)
        val p = f.punch!!
        assertTrue("both pads inside 120 ms with the meter lit: the jab in flight became the special", p.special)
        assertEquals("...with the special's contact frame", Fight.SPECIAL_LAND_T, p.landT, 1e-6f)
        assertEquals("...and the special's forced window", Clock.Forced.PUNCH, f.clock.forced)
        assertTrue(f.clock.forcedLeft > Clock.PUNCH_JAB_T)
        r.run(Fight.SPECIAL_LAND_T + DT)
        assertTrue("the special resolved", p.resolved)
        assertEquals("the meter is spent to the seed whatever it met", Fight.METER_SEED, f.meter)
        assertEquals("the special never costs a heart", Fight.HEARTS, f.hearts)
    }

    @Test
    fun theIntroWaitsForItsFloorAndTheBellFollowsTheCard() {
        val r = Rig()
        val f = r.fight
        f.boot()
        assertEquals(State.TITLE, f.state)
        f.tap()
        assertEquals("the coin", State.INTRO, f.state)
        // the bus with no clips fires every line's end at once
        f.onVoiceLineEnd(Lines.INTRO_1); f.onVoiceLineEnd(Lines.INTRO_2); f.onVoiceLineEnd(Lines.INTRO_3)
        r.run(1f)
        assertEquals("the intro holds its floor even when the bus is instant", State.INTRO, f.state)
        r.run(Fight.INTRO_MIN_T)
        assertEquals("...then the card", State.ROUND_CARD, f.state)
        var guard = 0
        while (f.state == State.ROUND_CARD && guard++ < 600) r.frame()
        assertEquals("...then the bell", State.FIGHT, f.state)
        assertTrue("the card lasts its 1.2 s", guard in 1..(Fight.CARD_T * 60f + 2f).toInt())
        assertTrue("the referee said fight", r.host.said.contains(Lines.FIGHT))
        assertEquals("round 1 on a fresh clock (one frame at the idle floor at most)", Fight.ROUND_WORLD_S, f.roundClock, 0.02f)
    }

    @Test
    fun theHangBurnsWhileTheTellHolds() {
        val r = Rig(); r.toFight()
        val b = r.fight.boxer
        r.motion = 1f
        var guard = 0
        while (b.phase != Boxer.Phase.TELL && guard++ < 60 * 8) r.frame()
        if (b.phase != Boxer.Phase.TELL) return   // no interpreter yet: the day he throws a drilled peck this arms itself
        r.motion = 0f
        r.frame()
        val hang0 = b.hangLeft; val frac0 = b.tellFrac; val world0 = r.fight.clock.worldT
        assertTrue("the hang is live at the start of a tell", hang0 > 0f)
        r.run(0.3f)
        assertEquals("the tell HOLDS while the player is still", frac0, b.tellFrac, 1e-5f)
        assertEquals("...because the world did not move", world0, r.fight.clock.worldT, 1e-6f)
        assertTrue("...but the hang BURNS on real time", b.hangLeft < hang0 - 0.2f)
    }
}
