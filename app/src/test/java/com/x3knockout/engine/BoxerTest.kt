package com.x3knockout.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BOXER ON THE JVM — his half of the two-clock audit (DESIGN.md §2.9) and the rules that
 * decide a fight without ever reaching a screen: the hang burning on real time under a frozen
 * world, the collider test's CLEAN / PERFECT / fatal verdicts, the guard and the openings, the
 * stun, the stagger and the special that ends it, the knockdown ladder and the rise, and the
 * pattern interpreter following a branch of BOXER.md §7's table.
 *
 * Every test drives [Boxer.update] with a hand-made [Body] at 60 Hz and reads the verdicts back
 * through a recording [Boxer.Listener], exactly as `Fight` does. The strips are absent, as they
 * are on a build before the artist delivers: the timers are the truth, and the tests prove it.
 */
class BoxerTest {

    private val DT = 1f / 60f

    private class Rec : Boxer.Listener {
        val phrases = ArrayList<String>()
        val tells = ArrayList<Boxer.Attack>()
        val feints = ArrayList<Boxer.Feint>()
        val strikes = ArrayList<Triple<Boxer.Attack, Answer, StrikeResult>>()
        val dmgs = ArrayList<Int>()
        val guards = ArrayList<String>()
        val says = ArrayList<String>()
        var knockdown: Triple<Int, Int, Boolean>? = null
        var staggerOpens = 0
        var staggerCloses = 0
        var stunOn = 0
        var stunOff = 0
        var strikeStarts = 0
        var wrongSide = 0
        var awards = ArrayList<String>()
        override fun onPhrase(name: String) { phrases.add(name) }
        override fun onTell(attack: Boxer.Attack, feint: Boxer.Feint?, tellT: Float) { if (feint == null) tells.add(attack) else feints.add(feint) }
        override fun onStrikeStart(attack: Boxer.Attack, strikeT: Float) { strikeStarts++ }
        override fun onStrike(attack: Boxer.Attack, answer: Answer, result: StrikeResult, dmg: Int) { strikes.add(Triple(attack, answer, result)); dmgs.add(dmg) }
        override fun onRecover(attack: Boxer.Attack) {}
        override fun onGuard(open: Boolean, by: String) { guards.add((if (open) "open:" else "close:") + by) }
        override fun onStagger(open: Boolean, seconds: Float) { if (open && seconds == 0f) staggerOpens++ else if (!open) staggerCloses++ }
        override fun onStun(on: Boolean) { if (on) stunOn++ else stunOff++ }
        override fun onHitReaction(kind: Boxer.HitKind) {}
        override fun onKnockdown(n: Int, riseAt: Int, ko: Boolean) { knockdown = Triple(n, riseAt, ko) }
        override fun onRise() {}
        override fun onWrongSide() { wrongSide++ }
        override fun onAward(word: String, points: Int) { awards.add(word) }
        override fun onStall(round: Int) {}
        override fun onSay(id: String, urgent: Boolean) { says.add(id) }
        override fun onSfx(id: Int, pitch: Float, vol: Float) {}
    }

    private fun boxer(rec: Rec, difficulty: Int = 1, round: Int = 1, drill: Boxer.Drill = Boxer.Drill.OFF): Boxer {
        val b = Boxer()
        b.listener = rec
        b.newFight(seed = 7, difficulty = difficulty)
        b.newRound(round)
        b.drill = drill
        return b
    }

    private fun standing(): Body = Body().also { it.headX = 0f; it.headY = Fight.EYE_H; it.moving = 1f }

    /** Lean the body: the eye and the collider move together, as MOTION.md measured. */
    private fun Body.lean(x: Float) { leanX = x; headX = bodyX + leanX }
    private fun Body.duck(amt: Float) { duckAmt = amt; headY = Fight.EYE_H - Fight.DUCK_DROP * amt }

    private fun run(b: Boxer, body: Body, seconds: Float, wdt: Float = DT, dt: Float = DT) {
        var t = 0f
        while (t < seconds - 1e-4f) { b.update(wdt, dt, body); t += dt }
    }

    /** Run at rate 1 until [pred] holds; fail loudly instead of spinning if it never does. */
    private fun until(b: Boxer, body: Body, max: Float = 20f, pred: () -> Boolean) {
        var t = 0f
        while (!pred()) { b.update(DT, DT, body); t += DT; assertTrue("condition reached within $max s", t < max) }
    }

    // ------------------------------------------------------------------ the tell, end to end

    @Test
    fun theTellPlaysThroughAndTheJabLands() {
        // This test used to be `aFrozenWorldHoldsTheTellWhileTheHangAndTheFuseBurn`, and it was
        // the clearest statement of the old law there was: a frozen world left the tell exactly
        // where it stood while the hang and the fuse burned on real time underneath it. The law
        // is gone (2026-09-10) and so are both timers; what is left to assert is that the tell
        // simply plays, at one speed, and arrives.
        val rec = Rec(); val b = boxer(rec, drill = Boxer.Drill.PECK_L); val body = standing()
        until(b, body) { b.phase == Boxer.Phase.TELL }
        assertEquals(Boxer.Attack.PECK_L, b.attack)
        assertTrue("the arc exists from the first frame", b.aimSet)
        assertEquals("the read has not started to run yet", 0f, b.tellFrac, 0.05f)
        assertEquals("the quarter-second grace is in the window the player gets",
            Boxer.Attack.PECK_L.tellT * Boxer.TELL_MUL[0] * Fighter.ROOSTER.tellMul * Boxer.TELL_MUL_DIFF[1] + Boxer.TELL_GRACE,
            b.tellDur, 1e-4f)
        body.moving = 0f
        run(b, body, b.tellDur * 0.5f)
        assertTrue("a still player no longer stops it", b.tellFrac > 0.3f)
        until(b, body) { rec.strikes.isNotEmpty() }
        assertEquals(1, rec.strikeStarts)
        assertEquals("centred and level: the jab lands", StrikeResult.HIT, rec.strikes[0].third)
        assertEquals("...for no damage in a drill", 0, rec.dmgs[0])
        assertEquals("he is open in his recover", Boxer.Phase.RECOVER, b.phase)
    }

    // ------------------------------------------------------------------ the collider

    @Test
    fun aSlipOutOfTheReadIsCleanAndASlipInsideTheStrikeIsPerfect() {
        val rec = Rec(); val b = boxer(rec, drill = Boxer.Drill.PECK_L); val body = standing()
        until(b, body) { b.phase == Boxer.Phase.TELL }
        assertEquals("the bead sits on the face", 0f, b.aimX, 1e-6f)
        body.lean(0.55f)                                   // the read: off the line before the strike begins
        until(b, body) { rec.strikes.size == 1 }
        assertEquals(Answer.SLIP_R, rec.strikes[0].second)
        assertEquals("already off the line when the strike began", StrikeResult.CLEAN, rec.strikes[0].third)
        assertFalse(b.onLineAtStrike)
        body.lean(0f)
        until(b, body) { b.phase == Boxer.Phase.STRIKE }    // stay on the line through the whole tell
        assertTrue("the aim was inside the capsule on the first strike frame", b.onLineAtStrike)
        body.lean(0.55f)                                   // the reflex: off it at contact
        until(b, body) { rec.strikes.size == 2 }
        assertEquals(StrikeResult.PERFECT, rec.strikes[1].third)
        assertEquals("a peck's PERFECT opens the recover, not a stagger", Boxer.Phase.RECOVER, b.phase)
        // a half slip is a glance
        body.lean(0f)
        until(b, body) { b.phase == Boxer.Phase.TELL }
        body.lean(0.28f)
        until(b, body) { rec.strikes.size == 3 }
        assertEquals(StrikeResult.GLANCE, rec.strikes[2].third)
    }

    @Test
    fun theDuckAnswersTheHighHookAndIsFatalIntoTheLowOne() {
        val rec = Rec(); val b = boxer(rec, drill = Boxer.Drill.WING_R); val body = standing()
        until(b, body) { b.phase == Boxer.Phase.STRIKE }
        body.duck(0.6f)                                     // drop inside the strike: PERFECT, and he over-rotates
        until(b, body) { rec.strikes.size == 1 }
        assertEquals(Answer.DUCK, rec.strikes[0].second)
        assertEquals(StrikeResult.PERFECT, rec.strikes[0].third)
        assertEquals("a PERFECT on a wing staggers him", Boxer.Phase.STAGGER, b.phase)
        assertEquals(1, rec.staggerOpens)
        assertTrue(b.warbling); assertTrue(b.spirals)
        run(b, body, Boxer.STAGGER_T[0] + Boxer.STAGGER_SHAKE_T + 0.1f)
        assertEquals("he straightens and idles", Boxer.Phase.IDLE, b.phase)
        assertEquals(1, rec.staggerCloses)

        // the low one: a full duck drops the head INTO it — the worst mistake in the set
        val rec2 = Rec(); val b2 = boxer(rec2, drill = Boxer.Drill.WING_L); val body2 = standing()
        until(b2, body2) { b2.phase == Boxer.Phase.TELL }
        body2.duck(1f)
        until(b2, body2) { rec2.strikes.size == 1 }
        assertEquals(Answer.DUCK, rec2.strikes[0].second)
        assertEquals(StrikeResult.HIT, rec2.strikes[0].third)
        // ...and the guard is the guard-counter: blocked, he is open 0.6 s
        body2.duck(0f); body2.guardUp = true
        until(b2, body2) { rec2.strikes.size == 2 }
        assertEquals(Answer.GUARD, rec2.strikes[1].second)
        assertEquals(StrikeResult.BLOCK, rec2.strikes[1].third)
        assertTrue("the bounced glove opens him", rec2.guards.any { it == "open:COUNTER" || it == "open:RECOVER" })
    }

    @Test
    fun theSunriseIsUnblockableCatchesADuckAndClearsOnAStep() {
        val rec = Rec(); val b = boxer(rec, drill = Boxer.Drill.SUNRISE); val body = standing()
        until(b, body) { b.phase == Boxer.Phase.TELL }
        assertEquals(Boxer.Pupils.BOTH, b.pupils); assertEquals(Boxer.Crest.WHITE, b.crest); assertEquals(Boxer.Mouth.CROW, b.mouth)
        body.guardUp = true; body.duck(1f)
        until(b, body) { rec.strikes.size == 1 }
        assertEquals("the column catches a ducked head under a guard", StrikeResult.HIT, rec.strikes[0].third)
        body.guardUp = false; body.duck(0f)
        until(b, body) { b.phase == Boxer.Phase.TELL }
        body.stepping = true
        until(b, body) { rec.strikes.size == 2 }
        assertEquals(Answer.STEP, rec.strikes[1].second)
        assertEquals("a step clears it, no PERFECT", StrikeResult.CLEAN, rec.strikes[1].third)
    }

    // ------------------------------------------------------------------ the guard, the stun, the stagger, the ladder

    @Test
    fun headPunchesWhiffOnTheClosedGuardAndBodyBlowsOpenIt() {
        // idle with his guard up and no pattern running: the attract's idle, which punch() treats like any idle
        val rec = Rec(); val b = boxer(rec); val body = standing()
        b.idle()
        b.update(DT, DT, body)
        assertEquals(Boxer.Phase.IDLE, b.phase)
        assertEquals("closed guard: a whiff", PunchResult.GUARD, b.punch(Hand.LEFT, Level.HEAD, 6, false, false).result)
        assertEquals(120, b.hp)
        assertTrue("that_all, once", rec.says.contains(Lines.THAT_ALL))
        val o = b.punch(Hand.LEFT, Level.BODY, 8, false, false)
        assertEquals(PunchResult.LAND, o.result); assertTrue(o.opened); assertEquals(112, b.hp)
        b.update(DT, DT, body)
        assertTrue("the window is open", b.guardOpen); assertEquals("open:BODY", rec.guards.last())
        assertEquals("head punches land through the window", PunchResult.LAND, b.punch(Hand.RIGHT, Level.HEAD, 8, false, false).result)
        assertEquals(104, b.hp)
        val o2 = b.punch(Hand.LEFT, Level.BODY, 8, false, false)
        assertTrue("a second body blow inside the window staggers", o2.staggered)
        assertEquals(Boxer.Phase.STAGGER, b.phase)
        assertEquals("damage in stagger is doubled", 4, b.punch(Hand.RIGHT, Level.HEAD, 2, false, false).dmg)
        assertEquals(PunchResult.STAGGER, b.punch(Hand.LEFT, Level.HEAD, 2, false, false).result)
        assertEquals("still above the 80 rung: the ladder is not what drops him here", 88, b.hp)
        val ko = b.punch(Hand.RIGHT, Level.HEAD, 35, false, true)
        assertTrue("the Wake-Up Call in a stagger puts him to sleep", ko.knockdown && ko.ko)
        assertEquals(PunchResult.KNOCKDOWN, ko.result)
        assertEquals(Triple(1, 0, true), rec.knockdown)
        assertTrue(b.down)
        assertEquals("the special's KO fall", Boxer.Phase.KNOCKDOWN, b.phase)
        run(b, body, Boxer.KO_FALL_T + 0.1f, wdt = 0f)
        assertEquals("...to the flat pose, on the referee's clock", Boxer.Phase.KO, b.phase)
        assertEquals("nothing to hit on the canvas", PunchResult.AIR, b.punch(Hand.LEFT, Level.HEAD, 6, false, false).result)
    }

    @Test
    fun aRightOverTheIncomingLeftPeckStunsHimAndABodyBlowWakesHim() {
        val rec = Rec(); val b = boxer(rec, drill = Boxer.Drill.PECK_L); val body = standing()
        until(b, body) { b.phase == Boxer.Phase.TELL }
        val o = b.punch(Hand.RIGHT, Level.HEAD, 8, false, false)
        assertTrue(o.stunned); assertTrue(o.interrupted)
        assertEquals(Boxer.Phase.STUN, b.phase); assertEquals(1, rec.stunOn)
        assertEquals("the tell is gone", null, b.attack); assertFalse(b.aimSet)
        run(b, body, 0.3f)
        b.punch(Hand.LEFT, Level.HEAD, 6, false, false)
        assertTrue("a hit inside resets the stun", b.stunLeft > 0.5f)
        b.punch(Hand.LEFT, Level.BODY, 8, false, false)
        assertEquals("a body blow wakes him", Boxer.Phase.IDLE, b.phase); assertEquals(1, rec.stunOff)
        // an early hit during any other tell interrupts without stunning
        until(b, body) { b.phase == Boxer.Phase.TELL }
        val o2 = b.punch(Hand.LEFT, Level.HEAD, 6, false, false)
        assertTrue(o2.interrupted); assertFalse(o2.stunned)
        assertEquals(Boxer.Phase.HIT, b.phase)
        run(b, body, Boxer.HIT_T + 0.05f)
        assertEquals(Boxer.Phase.IDLE, b.phase)
    }

    @Test
    fun theLadderDropsHimAtEightyAndHeRisesAtFour() {
        val rec = Rec(); val b = boxer(rec); val body = standing()
        b.idle()                                                        // his guard up, nothing thrown (a drill would take no damage)
        b.update(DT, DT, body)
        b.punch(Hand.LEFT, Level.BODY, 10, false, false); b.update(DT, DT, body)   // 110, the window opens
        var n = 0
        while (rec.knockdown == null && n++ < 10) { b.punch(Hand.RIGHT, Level.HEAD, 8, false, false); b.update(0.001f, DT, body) }
        assertEquals(78, b.hp)
        assertEquals("KD1 at the 80 rung; he rises at 4", Triple(1, 4, false), rec.knockdown)
        assertEquals(1, b.ladderNext); assertEquals(1, b.knockdownsFight); assertEquals(4, b.crestSpikes)
        assertEquals(Boxer.Phase.KNOCKDOWN, b.phase)
        run(b, body, Boxer.FALL_T + 0.05f, wdt = 0f)
        assertEquals("on the canvas", Boxer.Phase.DOWN, b.phase)
        run(b, body, 4f - Boxer.GETUP_LEAD - 0.1f, wdt = 0f)
        assertEquals("still down just before the lead", Boxer.Phase.DOWN, b.phase)
        run(b, body, 0.2f, wdt = 0f)
        assertEquals("pushing up before the four, timed from the down pose where the referee's count begins", Boxer.Phase.GETUP, b.phase)
        b.idle()                                                        // the referee reached his number
        assertEquals(Boxer.Phase.IDLE, b.phase)
        assertEquals("HP at least a quarter: he had more", 78, b.hp)
        assertTrue("only pecks for two world seconds", b.pecksOnlyLeft > 1.9f)
        assertEquals("the next rung is 40", 1, b.ladderNext)
        // the bell of the same round keeps his HP; the pattern then throws again
        b.newRound(1)
        assertEquals(78, b.hp)
        until(b, body) { b.phase == Boxer.Phase.TELL }
    }

    // ------------------------------------------------------------------ the pattern

    @Test
    fun roundOneOpensWithAAndADuckedPeckBringsTheLowHook() {
        val rec = Rec(); val b = boxer(rec); val body = standing()
        body.duck(1f)                                                  // a beginner who ducks everything
        until(b, body, 40f) { rec.phrases.size >= 2 }
        assertEquals("A opens, and the branch on a ducked peck names C", listOf("A", "C"), rec.phrases)
        assertEquals("four pecks", 4, rec.strikes.size)
        assertTrue("a jab goes over a ducked head: safe", rec.strikes.all { it.first.isPeck && it.second == Answer.DUCK && it.third == StrikeResult.CLEAN })
        until(b, body, 40f) { rec.strikes.size == 5 }
        assertEquals("C's low hook, and the duck drops the head into it", Boxer.Attack.WING_L, rec.strikes[4].first)
        assertEquals(StrikeResult.HIT, rec.strikes[4].third)
        // DERIVED, NOT TYPED. This used to assert a literal 18 and went red the moment the card's
        // hardness was turned up — which is a test failing for being out of date rather than for
        // finding anything. What it is actually here to pin is that ducking INTO the body hook
        // takes the FATAL number rather than the ordinary one, so it says that.
        val fatal = Math.round(Boxer.Attack.WING_L.dmgDuckInto * Boxer.DMG_MUL_DIFF[1] * Fighter.ROOSTER.dmgMul)
        assertTrue("the fatal number, not the ordinary one",
            Boxer.Attack.WING_L.dmgDuckInto > Boxer.Attack.WING_L.dmg)
        assertEquals("ducking into the body hook takes the fatal number", fatal, rec.dmgs[4])
        assertTrue("wake up", rec.says.contains(Lines.WAKE_UP))
    }

    @Test
    fun theRotationIsSeededAndTheDrillThrowsOnlyItsAttack() {
        val a = Rec(); val ba = boxer(a); val bodyA = standing()
        val c = Rec(); val bc = boxer(c); val bodyC = standing()
        until(ba, bodyA, 90f) { a.phrases.size >= 5 }
        until(bc, bodyC, 90f) { c.phrases.size >= 5 }
        assertEquals("the same seed gives the same order", a.phrases, c.phrases)
        assertEquals("A opens", "A", a.phrases[0])
        assertTrue("the rotation is B C D in some order", a.phrases.drop(1).take(3).toSet() == setOf("B", "C", "D"))
        val rec = Rec(); val b = boxer(rec, drill = Boxer.Drill.WING_R); val body = standing()
        until(b, body, 30f) { rec.tells.size >= 3 }
        assertTrue(rec.tells.all { it == Boxer.Attack.WING_R })
        assertTrue("no phrase in a drill", rec.phrases.isEmpty())
    }

    @Test
    fun everyTellCarriesTheQuarterSecondGrace() {
        // The owner's ruling: a quarter second more to answer, after the glove lights up — and it
        // lights on the tell's FIRST frame. Added after every multiplier, so it is a quarter
        // second for every man, every round and every difficulty rather than 0.16 s on HARD.
        for (d in 0..2) {
            val rec = Rec(); val b = boxer(rec, difficulty = d, drill = Boxer.Drill.PECK_L)
            val body = standing()
            until(b, body) { b.phase == Boxer.Phase.TELL }
            val authored = Boxer.Attack.PECK_L.tellT * Boxer.TELL_MUL[0] * Fighter.ROOSTER.tellMul * Boxer.TELL_MUL_DIFF[d]
            assertEquals("difficulty $d: the grace is on top of the authored tell",
                authored + Boxer.TELL_GRACE, b.tellDur, 1e-4f)
        }
    }

    @Test
    fun theTitleNeverThrowsAndTheDrillGoesQuietThere() {
        val rec = Rec(); val b = boxer(rec, drill = Boxer.Drill.ALL); val body = standing()
        b.idle()                                                        // the attract: not the rise, he is standing
        run(b, body, 10f)
        assertTrue(rec.tells.isEmpty()); assertTrue(rec.phrases.isEmpty())
        assertEquals(Boxer.Phase.IDLE, b.phase)
        assertEquals("one floor, in the attract too", 0.06f, b.floorNow(0.06f), 1e-6f)
        b.taunt(); assertEquals(Boxer.Phase.TAUNT, b.phase); assertTrue(b.tongue)
        b.win(); assertEquals("standing, the card is his win", Boxer.Phase.WIN, b.phase)
        assertNotNull(b.verifyLine(body, 0.35f))
    }
}
