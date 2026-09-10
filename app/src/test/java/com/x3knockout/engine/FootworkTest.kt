package com.x3knockout.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.tan

/**
 * HIS FEET (DESIGN.md §6.2) — the owner: *"make sure opposing boxers have footwork (or lateral
 * movement), and the tactical style built around it is known as out-fighting or stick-and-move as
 * well as flamboyent footwork based on their personality."*
 *
 * Three properties are worth more than any number in here, and they are the three that would ruin
 * the game silently if they broke:
 *
 *  1. **A still player sees a still man.** His gait is on `wdt`, so at the floor his feet crawl at
 *     3 % like everything else that can hurt. A boxer who walked on real time would be the one
 *     hostile thing in the game outside the bubble.
 *  2. **He never travels while there is something to read.** The lateral component freezes on the
 *     tell and stays frozen through the strike. A target that slides while you are reading it is
 *     not a target, it is a lottery.
 *  3. **He cannot walk off the plate.** The arc limit is derived from the HUD's own right rail,
 *     with his silhouette's half-angle subtracted, so his SHOULDER stays inboard — and it is
 *     faded rather than clamped, because a clamp against a leaning player bites every frame.
 */
class FootworkTest {

    private companion object { const val DT = 1f / 60f }

    private fun boxer(who: Fighter, round: Int = 1): Boxer {
        val b = Boxer()
        b.fighter = who
        b.newFight(seed = 7, difficulty = 1)
        b.newRound(round)
        b.drill = Boxer.Drill.OFF
        return b
    }
    private fun body() = Body().also { it.headX = 0f; it.headY = Fight.EYE_H; it.moving = 1f }

    @Test
    fun everyManOnTheCardStartsExactlyWhereTheShippedFightPutHim() {
        for (f in Fighter.CARD) {
            val b = boxer(f)
            assertEquals("${f.id} opens on the mark", Boxer.X, b.footX, 1e-5f)
            assertEquals("${f.id} opens at rest range", Boxer.Z, b.footZ, 1e-5f)
        }
    }

    @Test
    fun aStillWorldIsAStillMan() {
        val b = boxer(Fighter.at(1))              // the Sardine: the fastest feet on the card
        val bd = body()
        var t = 0f
        while (t < 3f) { b.update(0f, DT, bd); t += DT }   // wdt 0: the player has not moved
        assertEquals("his feet are on world time like everything else that can hurt",
            Boxer.X, b.footX, 1e-4f)
    }

    @Test
    fun theGaitIsRealAndItIsDifferentForEveryMan() {
        val travel = HashMap<String, Float>()
        for (f in Fighter.CARD) {
            val b = boxer(f); val bd = body()
            var t = 0f; var lo = 0f; var hi = 0f
            // no drill and no player: he throws, so sample only while he is uncommitted
            while (t < 8f) {
                b.update(DT, DT, bd); t += DT
                if (b.phase == Boxer.Phase.IDLE) { lo = minOf(lo, b.footX); hi = maxOf(hi, b.footX) }
            }
            travel[f.id] = hi - lo
        }
        assertTrue("Silk circles: the widest arc on the card", travel["silk"]!! > travel["rooster"]!!)
        assertTrue("the Anvil does not: 9 cm is weight, not movement", travel["anvil"]!! < 0.25f)
        assertTrue("...and everyone actually moves", travel["sardine"]!! > 0.1f)
    }

    @Test
    fun heNeverTravelsWhileThereIsSomethingToRead() {
        val b = boxer(Fighter.at(3))              // Silk: the widest arc, so the worst case
        val bd = body()
        var guard = 0
        while (b.phase != Boxer.Phase.TELL && guard++ < 4000) b.update(DT, DT, bd)
        assertEquals("he throws", Boxer.Phase.TELL, b.phase)
        val at = b.lat
        var t = 0f
        while (t < 0.25f && (b.phase == Boxer.Phase.TELL || b.phase == Boxer.Phase.STRIKE)) {
            b.update(DT, DT, bd); t += DT
        }
        assertEquals("the sideways component is frozen for the whole read", at, b.lat, 1e-4f)
    }

    @Test
    fun heStepsInToThrowAndBackOutToRecover() {
        val b = boxer(Fighter.at(3)); val bd = body()
        var guard = 0
        while (b.phase != Boxer.Phase.TELL && guard++ < 4000) b.update(DT, DT, bd)
        val atTell = b.range
        while (b.phase == Boxer.Phase.TELL && guard++ < 4000) b.update(DT, DT, bd)
        assertTrue("the lead foot lands with the glove: he is closer than he was", b.range < atTell)
        while (b.phase != Boxer.Phase.RECOVER && guard++ < 4000) b.update(DT, DT, bd)
        var t = 0f
        while (t < 0.6f) { b.update(DT, DT, bd); t += DT }
        assertTrue("...and the out-fighter leaves again", b.range > atTell - 0.01f)
    }

    @Test
    fun nobodyCanWalkOffThePlate() {
        // the derived limit: the HUD's right rail, less his own silhouette's half-angle
        for (f in Fighter.CARD) {
            val b = boxer(f); val bd = body()
            bd.headX = -0.55f; bd.bodyX = -0.55f       // the player has stepped the other way
            var t = 0f; var worst = 0f
            while (t < 12f) {
                b.update(DT, DT, bd); t += DT
                val zf = -b.footZ
                val edge = Boxer.EDGE_RAD - asin((Boxer.HALF_W / max(b.range, 1.5f)).coerceIn(-1f, 1f))
                val room = tan(edge) * zf - abs(b.footX - bd.headX)
                worst = minOf(worst, room)
            }
            assertTrue("${f.id} stays inboard of the rail (worst room $worst)", worst > -0.02f)
        }
    }

    @Test
    fun theInvariantOnTheCardsOwnRows() {
        // a man is allowed a wide arc OR a fast one, never both: footArc*footHz is his travel
        for (f in Fighter.CARD) {
            val ok = f.footArc <= 0.30f || f.footArc * f.footHz <= 0.20f
            assertTrue("${f.id}: arc ${f.footArc} x ${f.footHz} Hz is too much ring", ok)
        }
    }

    @Test
    fun theAnvilCutsTheRingAndABodyBlowBuysItBack() {
        val b = boxer(Fighter.at(2)); val bd = body()
        assertEquals("anvil", b.fighter.id)
        var guard = 0
        // dodge everything he throws: every evasion costs 5.5 cm of ring
        bd.leanX = -0.5f; bd.headX = -0.5f
        while (guard++ < 60 * 40) b.update(DT, DT, bd)
        val walked = b.range
        assertTrue("he has walked in on a player who keeps slipping ($walked)", walked < Boxer.REST_RANGE - 0.05f)
        assertTrue("...but never past the floor", walked >= Boxer.RATCHET_MIN - 0.01f)
    }

    @Test
    fun tooFarOutIsShortForBothOfYou() {
        val b = boxer(Fighter.at(3)); val bd = body()
        var t = 0f
        while (t < 0.5f) { b.update(DT, DT, bd); t += DT }
        val near = b.punch(Hand.LEFT, Level.HEAD, 8, counter = false, special = false, body = bd)
        assertNotEquals("at the shipped distance nothing is short", PunchResult.SHORT, near.result)
        // put the player where the man is not
        bd.headX = 3.0f
        assertTrue("the separation is real", b.separation(bd) > Boxer.REST_RANGE + Boxer.YOUR_REACH)
        val far = b.punch(Hand.LEFT, Level.HEAD, 8, counter = false, special = false, body = bd)
        assertEquals("a punch thrown from across the ring is SHORT", PunchResult.SHORT, far.result)
        // …and the situation is reachable in a real fight, which is the point of the whole thing:
        // Silk's recover-out exceeds your slack, so for a beat after every exchange you are short.
        val silk = Fighter.at(3)
        val out = Boxer.REST_RANGE + silk.footClose * Boxer.FOOT_OUT_K
        assertTrue("the out-fighter leaves your reach (${out} vs ${Boxer.REST_RANGE + Boxer.YOUR_REACH})",
            out > Boxer.REST_RANGE + Boxer.YOUR_REACH)
    }
}
