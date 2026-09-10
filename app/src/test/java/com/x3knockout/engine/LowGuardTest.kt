package com.x3knockout.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE LOW GUARD — his ribs, and how they are earned (`Boxer.BODY_OPEN`, DESIGN.md §4.3.2).
 *
 * The defect these tests exist to keep out: `punch()` tested `level == Level.HEAD && !open` and
 * there was no `Level.BODY` branch anywhere in the guard logic, so the torso was open in every
 * state in the game. Nine right hands to the ribs TKO'd the Rooster from the opening bell without
 * once reading a tell, and a BODY-level special staggered him from a cold stance for full damage
 * in flat contradiction of DESIGN.md §4.5.
 *
 * The invariant the whole design rests on is one sentence — **a cold neutral offers the ribs
 * nothing** — and [aColdNeutralOffersTheRibsNothing] is that sentence as an assertion rather than
 * a comment, for every man on the card.
 */
class LowGuardTest {

    private companion object { const val DT = 1f / 60f }

    private class Rec : Boxer.Listener {
        val lows = ArrayList<String>()
        var staggerOpens = 0
        override fun onPhrase(name: String) {}
        override fun onTell(attack: Boxer.Attack, feint: Boxer.Feint?, tellT: Float) {}
        override fun onStrikeStart(attack: Boxer.Attack, strikeT: Float) {}
        override fun onStrike(attack: Boxer.Attack, answer: Answer, result: StrikeResult, dmg: Int) {}
        override fun onRecover(attack: Boxer.Attack) {}
        override fun onGuard(open: Boolean, by: String) {}
        override fun onLowGuard(open: Boolean, by: String) { lows.add((if (open) "out:" else "in:") + by) }
        override fun onStagger(open: Boolean, seconds: Float) { if (open && seconds == 0f) staggerOpens++ }
        override fun onStun(on: Boolean) {}
        override fun onHitReaction(kind: Boxer.HitKind) {}
        override fun onKnockdown(n: Int, riseAt: Int, ko: Boolean) {}
        override fun onRise() {}
        override fun onWrongSide() {}
        override fun onAward(word: String, points: Int) {}
        override fun onStall(round: Int) {}
        override fun onSay(id: String, urgent: Boolean) {}
        override fun onSfx(id: Int, pitch: Float, vol: Float) {}
    }

    /** A boxer standing in his own idle with a closed guard — the state the whole model is about. */
    private fun boxer(rec: Rec = Rec(), who: Fighter = Fighter.ROOSTER, round: Int = 1): Boxer {
        val b = Boxer()
        b.listener = rec
        b.fighter = who
        b.newFight(seed = 7, difficulty = 1)
        b.newRound(round)
        b.drill = Boxer.Drill.PECK_L      // he never throws: the test owns the tempo
        return b
    }

    private fun body() = Body().also { it.headX = 0f; it.headY = Fight.EYE_H; it.moving = 1f }
    private fun run(b: Boxer, seconds: Float) {
        val bd = body(); var t = 0f
        while (t < seconds - 1e-4f) { b.update(DT, DT, bd); t += DT }
    }
    private fun jab(b: Boxer, level: Level) = b.punch(Hand.LEFT, level, 8, counter = false, special = false)

    @Test
    fun aColdNeutralOffersTheRibsNothing() {
        // THE DEFECT THIS SUITE EXISTS FOR, second pass. The first fix left `IDLE -> tuckLeft
        // <= 0f`, so the ribs were open by DEFAULT: the first body blow of every exchange landed
        // free, and since a head punch reopened them, BODY-HEAD-BODY-HEAD ran forever at one
        // free body blow every second punch. The owner: "the torso defense still needs to
        // improve." They are shut now, exactly as his chin is.
        for (f in Fighter.CARD) {
            val b = boxer(who = f)
            run(b, 0.2f)
            assertEquals("${f.id}: his ribs are covered in a cold neutral",
                PunchResult.GUARD, jab(b, Level.BODY).result)
            assertTrue("${f.id}: and the picture agrees", !b.lowOpen)
        }
    }

    @Test
    fun punchHighAndHeCoversHigh() {
        // The fast lever, and the oldest combination in boxing.
        val rec = Rec(); val b = boxer(rec)
        run(b, 0.2f)
        assertEquals("the Rooster wants one", 1, b.fighter.lowBlows)
        assertEquals("upstairs is refused too, in a cold neutral", PunchResult.GUARD, jab(b, Level.HEAD).result)
        run(b, 0.05f)
        assertTrue("...but it took his elbows off his ribs", b.lowOpen)
        assertEquals("and now downstairs lands", PunchResult.LAND, jab(b, Level.BODY).result)
        assertTrue("...which shuts them behind it", b.bodyOpenLeft <= 0f)
        assertTrue("...and opens his chin, which is what a body blow is for", b.guardOpenLeft > 0f)
    }

    @Test
    fun theAnvilWantsTwoUpstairsBeforeHisRibsAreThere() {
        val b = boxer(who = Fighter.at(2))
        assertEquals("anvil", b.fighter.id)
        assertEquals(2, b.fighter.lowBlows)
        run(b, 0.2f); jab(b, Level.HEAD); run(b, 0.05f)
        assertTrue("one is not enough on him", !b.lowOpen)
        jab(b, Level.HEAD); run(b, 0.05f)
        assertTrue("two is", b.lowOpen)
    }

    @Test
    fun diggingForcesThemApartWithNoOpeningAtAll() {
        // The slow lever: the answer for the man whose chin the player cannot reach.
        val rec = Rec(); val b = boxer(rec)
        run(b, 0.2f)
        val n = b.fighter.digs
        assertTrue("the Rooster's ribs cost three", n == 3)
        for (i in 1 until n) {
            val o = jab(b, Level.BODY)
            assertEquals("dig $i is refused", PunchResult.GUARD, o.result)
            assertTrue("...and banked", o.dug)
            assertTrue("...and the player can see it landing", b.bodyWork > 0f)
            run(b, 0.05f)
        }
        assertEquals("the last dig is refused too", PunchResult.GUARD, jab(b, Level.BODY).result)
        run(b, 0.05f)
        assertTrue("...but it forced them apart", b.lowOpen)
        assertEquals("and now the ribs are there", PunchResult.LAND, jab(b, Level.BODY).result)
    }

    @Test
    fun noManCanBeBodiedTwiceInARow() {
        for (f in Fighter.CARD) {
            val b = boxer(who = f)
            run(b, 0.2f)
            repeat(f.lowBlows) { jab(b, Level.HEAD); run(b, 0.05f) }
            assertEquals("${f.id}: the earned body blow lands", PunchResult.LAND, jab(b, Level.BODY).result)
            assertTrue("${f.id}: the ribs shut behind it", b.bodyOpenLeft <= 0f)
            run(b, 0.05f)
            assertEquals("${f.id}: the second one is not free", PunchResult.GUARD, jab(b, Level.BODY).result)
        }
    }

    @Test
    fun bodyHeadBodyIsStillTheStaggerAndItIsHalfARead() {
        val b = boxer()
        run(b, 0.2f)
        jab(b, Level.HEAD); run(b, 0.05f)                 // make him cover
        assertEquals(PunchResult.LAND, jab(b, Level.BODY).result)
        run(b, 0.05f)
        jab(b, Level.HEAD); run(b, 0.05f)                 // and again
        val o = jab(b, Level.BODY)
        assertTrue("the combination folds him", o.staggered)
        assertEquals("a stagger earned by work is half a stagger earned by a read",
            Boxer.STAGGER_CAP[0] * Boxer.BODY_STAGGER_FRAC, b.staggerCapNow, 1e-5f)
    }

    @Test
    fun aBodySpecialIntoAClosedGuardOnlyOpensIt() {
        val b = boxer()
        run(b, 0.2f)
        val hp = b.hp
        val o = b.punch(Hand.RIGHT, Level.BODY, 35, counter = false, special = true)
        assertEquals("DESIGN.md 4.5: into a closed guard it only opens it", PunchResult.GUARD, o.result)
        assertEquals("no damage", hp, b.hp)
        assertTrue("it opened him", o.opened)
        run(b, 0.05f)
        assertTrue("...and it took his elbows with it", b.lowOpen)
    }

    @Test
    fun aBodyBlowInsideATellBanksNothing() {
        val rec = Rec(); val b = boxer(rec)
        var guard = 0
        while (b.phase != Boxer.Phase.TELL && guard++ < 1200) b.update(DT, DT, body())
        assertEquals("the drill reaches a tell", Boxer.Phase.TELL, b.phase)
        val o = jab(b, Level.BODY)
        assertEquals("his hands are busy: it lands", PunchResult.LAND, o.result)
        assertTrue("...but it banks no window", b.guardOpenLeft <= 0f)
        assertTrue("...and no opening downstairs", b.bodyOpenLeft <= 0f)
    }
}
