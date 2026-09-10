package com.x3knockout.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE LOW GUARD — the missing half of the guard (`Boxer.TUCK_BODY`, DESIGN.md §4.3).
 *
 * The defect these tests exist to keep out: `punch()` tested `level == Level.HEAD && !open` and
 * there was no `Level.BODY` branch anywhere in the guard logic, so the torso was open in every
 * state in the game. Nine right hands to the ribs TKO'd the Rooster from the opening bell without
 * once reading a tell, and a BODY-level special staggered him from a cold stance for full damage
 * in flat contradiction of DESIGN.md §4.5.
 *
 * The invariant the whole design rests on is one line — **his elbows are in for exactly as long
 * as his gloves are out** — and [theElbowsAreInForAsLongAsTheGlovesAreOut] is that sentence as an
 * assertion rather than a comment.
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
    fun theElbowsAreInForAsLongAsTheGlovesAreOut() {
        assertArrayEquals("THE invariant, and it is a test and not a comment",
            Boxer.GUARD_OPEN_BODY, Boxer.TUCK_BODY, 0f)
    }

    @Test
    fun noManOnTheCardCanBeBodiedTwiceInOneWindow() {
        // For every fighter and every round: the tuck the same blow writes is never shorter than
        // the window it opens, so a second body blow can never arrive inside the first one's gift.
        for (f in Fighter.CARD) {
            for (r in 0 until 3) {
                val tuck = (Boxer.TUCK_BODY[r] * f.openMul * f.lowMul).coerceAtLeast(Boxer.TUCK_MIN)
                val window = Boxer.GUARD_OPEN_BODY[r] * f.openMul
                assertTrue("${f.id} R${r + 1}: tuck $tuck must reach TUCK_MIN", tuck >= Boxer.TUCK_MIN)
                if (f.lowMul >= 1f) {
                    assertTrue("${f.id} R${r + 1}: tuck $tuck must cover the window $window", tuck >= window - 1e-6f)
                }
            }
        }
    }

    @Test
    fun theSecondBodyBlowInAnIdleIsRefusedByTheElbows() {
        val rec = Rec(); val b = boxer(rec)
        run(b, 0.2f)
        assertEquals("the ribs are open on a cold stance", PunchResult.LAND, jab(b, Level.BODY).result)
        assertTrue("...and the elbows come in", b.tuckLeft > 0f)
        run(b, 0.1f)
        assertTrue("...which the picture shows a frame later", !b.lowOpen)
        assertEquals("the second one is refused", PunchResult.GUARD, jab(b, Level.BODY).result)
        assertTrue("the refusal is a hold, not a hole", b.tuckLeft > 0f)
        assertTrue("the picture said so", rec.lows.any { it.startsWith("in:") })
    }

    @Test
    fun bodyHeadBodyIsTheStaggerAndItIsHalfARead() {
        val b = boxer()
        run(b, 0.2f)
        assertEquals(PunchResult.LAND, jab(b, Level.BODY).result)
        run(b, 0.05f)
        // the head punch is what brings his elbows out — landed or blocked, it is the same motion
        jab(b, Level.HEAD)
        assertTrue("his elbows are out again", b.tuckLeft <= 0f)
        run(b, 0.05f)
        val o = jab(b, Level.BODY)
        assertTrue("the third punch of the combination folds him", o.staggered)
        assertEquals("a stagger earned by work is half a stagger earned by a read",
            Boxer.STAGGER_CAP[0] * Boxer.BODY_STAGGER_FRAC, b.staggerCapNow, 1e-5f)
    }

    @Test
    fun aBodySpecialIntoAClosedGuardOnlyOpensIt() {
        val b = boxer()
        run(b, 0.2f)
        jab(b, Level.BODY)                    // his elbows come in
        run(b, 0.05f)
        val hp = b.hp
        val o = b.punch(Hand.RIGHT, Level.BODY, 35, counter = false, special = true)
        assertEquals("DESIGN.md 4.5: into a closed guard it only opens it", PunchResult.GUARD, o.result)
        assertEquals("no damage", hp, b.hp)
        assertTrue("it opened him", o.opened)
        assertTrue("...and it took his elbows with it", b.tuckLeft <= 0f)
    }

    @Test
    fun theAnvilWantsTwoHeadPunchesToOpenHisRibs() {
        val b = boxer(who = Fighter.at(2))
        assertEquals("anvil", b.fighter.id)
        run(b, 0.2f)
        assertEquals(PunchResult.LAND, jab(b, Level.BODY).result)
        run(b, 0.05f); jab(b, Level.HEAD)
        assertTrue("one is not enough on him", b.tuckLeft > 0f)
        run(b, 0.05f); jab(b, Level.HEAD)
        assertTrue("two is", b.tuckLeft <= 0f)
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
        assertTrue("...and no tuck", b.tuckLeft <= 0f)
    }
}
