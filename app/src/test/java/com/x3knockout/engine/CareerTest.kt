package com.x3knockout.engine

import com.x3knockout.engine.Desk.DT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CAREER — the ladder as a state machine (VOICE.md §6.6, the owner's ruling: *"the next boxer
 * should automatically come next"*).
 *
 * Everything here is a thing that used to be wrong and that no screenshot would have caught:
 * a knockout dropped the player on the attract screen and demanded another coin; the promotion
 * fired inside `knockout()`, so the next man's NAME and the next man's SPRITE were on the body of
 * the man still lying on the canvas; `store.champion` was set by any knockout at all, so one win
 * over a club fighter renamed the player CHAMPION for the life of the install; and the five
 * `intro_<fighter>` clips the script has always rendered were never asked for by anybody, so the
 * announcer never once said who the player was about to fight.
 *
 * The rig is [Desk], the same one `TwoClocksTest` drives, so these are assertions about the real
 * `Fight` and not about a model of it.
 */
class CareerTest {

    /** Put him on the canvas until the fight is over: body blows, which is the road that lands. */
    private fun knockOut(r: Desk.Rig): Boolean {
        var guard = 0
        while (r.fight.state != State.KO && guard++ < 60 * 60 * 3) {
            if (r.fight.state == State.FIGHT) {
                r.pitchG = -0.55f                      // look down: the punch goes to the body
                r.fight.punch(if (guard % 2 == 0) Hand.LEFT else Hand.RIGHT)
            }
            r.run(0.45f)
        }
        return r.fight.state == State.KO
    }

    @Test
    fun aKnockoutRollsIntoTheNextManWithoutACoin() {
        val r = Desk.Rig()
        r.toBout(0, hp = 30)
        assertEquals("the card opens on the Rooster", "rooster", r.fight.fighter.id)
        assertTrue("the harness can finish him", knockOut(r))

        // THE KO CARD IS STILL HIS. The promotion used to happen inside knockout().
        assertEquals("the man on the canvas is still the Rooster", "rooster", r.fight.fighter.id)
        assertEquals("and the ladder has not moved yet", 0, r.fight.boutIndex)
        assertTrue("a club fighter is not the champion", !r.store.champion)

        // ... then the rise card arrives BY ITSELF, with no tap.
        assertTrue("the tally hands over to the rise card", r.until(Fight.KO_TALLY_T + 1f) { r.fight.state == State.RISE })
        assertEquals("his ranking is the one you take", Fight.rankWord(4), r.fight.riseHead)
        assertEquals("and the next man is already named", "THE SARDINE", r.fight.riseNext)
        assertTrue("the trainer has a line for this rung", r.host.said.contains("climb_1"))
        assertTrue("the announcer says the number", r.host.said.contains("rank_4"))

        // ... and hands over to the next ceremony, again with no tap.
        assertTrue("the next bout begins by itself", r.until(Fight.RISE_MAX_T + 1f) { r.fight.state == State.INTRO })
        assertEquals("the promotion lands on the way out of the card", 1, r.fight.boutIndex)
        assertEquals("the next man is in the ring", "sardine", r.fight.fighter.id)
        assertNotEquals("and it is not the attract screen", State.TITLE, r.fight.state)
    }

    @Test
    fun theAnnouncerNamesBothMen() {
        val r = Desk.Rig()
        r.toCeremony(2)                                  // the Anvil
        assertTrue("the player is announced", r.host.said.contains(Lines.INTRO_YOU))
        assertTrue("so is the man he is fighting", r.host.said.contains("intro_anvil"))
        assertTrue("the ceremony still opens the way it did", r.host.said.contains(Lines.INTRO_1))
        assertTrue("a contender's bout is not a title fight", !r.host.said.contains(Lines.TITLE_SHOT))
    }

    @Test
    fun theLastBoutAnnouncesItselfAsATitleFight() {
        val r = Desk.Rig()
        r.toCeremony(4)                                  // the Metronome
        assertEquals("metronome", r.fight.fighter.id)
        assertEquals("the champion is rank zero", 0, r.fight.fighter.rank)
        assertTrue("and the card says so before a punch", r.host.said.contains(Lines.TITLE_SHOT))
        assertTrue(r.host.said.contains("intro_metronome"))
    }

    @Test
    fun theBeltIsTheLastManAndTheCardStops() {
        val r = Desk.Rig()
        r.toBout(4, hp = 30)
        assertTrue("the harness can finish the champion", knockOut(r))
        assertTrue("the belt", r.store.champion)
        assertTrue(r.until(Fight.KO_TALLY_T + 1f) { r.fight.state == State.RISE })
        assertEquals("CHAMPION OF THE WORLD", r.fight.riseHead)
        assertEquals("there is nobody next", "", r.fight.riseNext)
        assertTrue("the ending goes home", r.until(Fight.RISE_MAX_T + 1f) { r.fight.state == State.TITLE })
        assertEquals("and the ladder stays where it is", 4, r.fight.boutIndex)
    }

    /** Stand there and take it: the desk's own way of losing a fight. */
    private fun loseIt(r: Desk.Rig): Boolean {
        var guard = 0
        while (r.fight.state != State.GAME_OVER && guard++ < 60 * 60 * 6) r.run(0.5f)
        return r.fight.state == State.GAME_OVER
    }

    @Test
    fun aLossPutsYouBackAtTheBottomOfTheCard() {
        // The owner's ruling (2026-09-10): "whenever player loses, they restart from beginning of
        // game." The arcade rule was a coin — a continue was a rematch with the same man and the
        // ladder never moved — and it is the opposite of a climb you can be knocked off.
        val r = Desk.Rig()
        r.store.recordsEnabled = true
        r.store.boutReached = 3                              // most of the way up: he has beaten three
        r.fight.boot()                                       // …which is where boot() puts him
        r.fight.debugStart(1, 0f, 0, null, null)
        r.runToFight()
        assertEquals("silk", r.fight.fighter.id)
        assertTrue("standing still loses a fight now", loseIt(r))
        assertEquals("the ladder is back at the bottom", 0, r.fight.boutIndex)
        assertEquals("...and so is the record of it", 0, r.store.boutReached)
        assertEquals("the career total is spent", 0, r.fight.careerScore)
        assertTrue("the trainer says the one thing he has for the floor", r.host.said.contains(Lines.NOT_BEATEN))
        assertEquals("no continue: the only way out is the start screen", 0f, r.fight.continueLeft, 0f)
    }

    @Test
    fun theCornerSitsYouDownAndTheBellStandsYouUp() {
        // The owner: "there be a short between match rounds where the players are shown sitting
        // and the coach interacting with them." The whole staging hangs off one eased number —
        // the renderer drops the EYE by it, walks the other man to his stool by it, and fades
        // the coach in with it — so this is the test that keeps the scene from silently going
        // missing when somebody touches the round-end.
        // the drill, so the round can actually reach its bell: a still player takes no damage
        // in one, and a still player is the only kind a desk harness has
        val r = Desk.Rig()
        r.toFight()
        assertEquals("on your feet during the round", 0f, r.fight.seatK, 1e-4f)
        assertTrue("the round ends", r.until(Fight.ROUND_WORLD_S + 4f) { r.fight.state == State.ROUND_END })
        assertTrue("you sit down", r.until(Fight.SEAT_T + 0.5f) { r.fight.seatK > 0.98f })
        assertTrue("the trainer has something to say", r.host.said.isNotEmpty())
        assertTrue("the bell sends you back out", r.until(20f) { r.fight.state == State.ROUND_CARD || r.fight.state == State.FIGHT })
        assertTrue("...and you stand up again", r.until(Fight.SEAT_T + 0.5f) { r.fight.seatK < 0.02f })
    }

    @Test
    fun theCareerTotalSurvivesTheBoutTheScoreDoesNot() {
        val r = Desk.Rig()
        r.toBout(0, hp = 30)
        assertTrue(knockOut(r))
        val first = r.fight.score
        assertTrue("a knockout scores", first > 0)
        assertEquals("the career has it", first, r.fight.careerScore)
        assertTrue(r.until(Fight.KO_TALLY_T + Fight.RISE_MAX_T + 2f) { r.fight.state == State.INTRO })
        assertEquals("the next bout's scoreboard is zeroed", 0, r.fight.score)
        assertEquals("the career's is not", first, r.fight.careerScore)
    }
}
