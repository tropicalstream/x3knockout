package com.x3knockout.engine

import com.x3knockout.engine.Boxer.Attack
import com.x3knockout.engine.Boxer.Phrase
import com.x3knockout.engine.Boxer.Read
import com.x3knockout.engine.Boxer.Step
import com.x3knockout.engine.Hand

/**
 * THE CARD — five opponents, and each one is a different QUESTION.
 *
 * A ladder of five boxers who are merely faster than each other is one boxer with a difficulty
 * slider, and the arcade this game descends from never did that: its opponents escalate by asking
 * something new, so beating one leaves you able to do something you could not do before. That is
 * the rule here, and it is why the gimmicks below are not decoration:
 *
 *  1. THE ROOSTER   — the GRAMMAR. Colour says everything: a pupil is a jab and which hand, slits
 *                     are a hook, gold high or drooped is which hook, white is the uppercut. He is
 *                     slow and loud on purpose. You learn to read.
 *  2. THE SARDINE   — SPEED. The same grammar at three quarters the length, in chains. Each shot
 *                     is cheap; the flurry is not. You learn that the clock is a resource — every
 *                     punch you throw runs his flurry along with it — and that reading has to
 *                     become quick rather than careful.
 *  3. THE ANVIL     — PATIENCE. Enormous, slow, telegraphed a mile off, and he hits like a truck.
 *                     He looks like the easiest fight on the card until you notice the rule: punch
 *                     while his guard is up and he COUNTERS, free, for real damage. He is the boxer
 *                     who teaches you not to throw. That lands directly on the law — your punch
 *                     spends time, so a greedy punch now costs you the clock AND your chin.
 *  4. SILK          — the QUIET TELL. He has no colour at all: no pupil flash, no crest, nothing
 *                     lights up. Every tell is POSTURE — a shoulder, a planted foot, a dropped
 *                     glove. Everything you learned to read as light you must now read as shape.
 *  5. THE METRONOME — the CHAMPION, and he fights the law itself. HIS CLOCK IS YOUR MOTION,
 *                     amplified: stand still and he crawls; move and he is on you faster than
 *                     anyone on the card. Every other opponent taught you to move at the right
 *                     moment. He asks you to earn every one of those moments, because his speed is
 *                     literally your own. The final exam for a game whose subject is time.
 *
 * WHAT A FIGHTER DOES NOT GET TO CHANGE. The five attacks ([Attack]) are the whole card's shared
 * vocabulary — jab left, jab right, hook high, hook low, uppercut — exactly as the arcade reused a
 * handful of animations across a roster. A fighter re-times them, re-orders them, re-prices them
 * and re-tells them; it never invents a sixth, because a sixth would need a sixth pose strip and,
 * worse, a sixth thing for the player to learn from scratch instead of a new use for something
 * they already know.
 */
class Fighter(
    val id: String,
    /** The name on the scoreboard, and the announcer's. */
    val name: String,
    /** The one-line billing on the bout card. */
    val billing: String,
    /** The pose-strip asset (`assets/models/<asset>.x3s`); the whole card shares one rig. */
    val asset: String,
    /** His HP by difficulty. The Rooster's 100/120/140 is the unit everything else is read against. */
    val hp: IntArray,
    /** Global timing, against the authored [Attack] numbers. Below 1 is faster and therefore harder. */
    tellMul: Float = 1f,
    val strikeMul: Float = 1f,
    recoverMul: Float = 1f,
    /** What his punches cost you. */
    dmgMul: Float = 1f,
    /**
     * How long the world stays deep after his tell begins — the READ, in the player's real seconds,
     * as a multiple of the difficulty's `HANG_T`. This is the single kindest or cruellest number a
     * fighter owns: it is literally how long you are given to think.
     */
    val hangMul: Float = 1f,
    /** How long his guard stays open when you earn it. Below 1 means the openings are stingier. */
    val openMul: Float = 1f,
    /** The round his feints begin (1 = from the first bell). */
    val feintsFromRound: Int = 2,
    /** His gimmick — see [Gimmick]. */
    val gimmick: Gimmick = Gimmick.NONE,
    /** The gimmick's one number, meaning whatever that gimmick says it means. */
    val gimmickK: Float = 0f,
    /** His pattern per round; a fighter with one list uses it for all three. */
    val patterns: List<List<Phrase>>,
    /** Drawing: the figure's primary hue, and whether the renderer may flash colour at all. */
    val primary: FloatArray,
    val trunks: FloatArray,
    val glove: FloatArray,
    /** SILK's rule: no pupil, no crest, no glove flash — the tell is posture only. */
    val colourTells: Boolean = true,

    // ------------------------------------------------------------------ HOW HE STANDS THERE
    /**
     * THE IDLE IS THE CHARACTER, and on this card it does more work than the silhouette does.
     *
     * The arcade's opponents are recognisable across a room before either of them throws anything,
     * and it is not their outlines that do it — it is how they WAIT. One bounces, one sways like he
     * is bored, one barely moves at all. The pose strips are shared here, so this is the channel
     * that has to carry it: four numbers on top of the same 196 frames.
     *
     *   [bobHz] / [bobAmp]   the vertical bounce: fast and shallow reads nervous, slow and deep
     *                        reads heavy, and near-zero reads like a man who has done this before
     *   [swayHz] / [swayAmp] the roll: a lazy weight-shift, a metronome tick, or nothing
     *
     * All four are multipliers on the Rooster's own idle, so his row is 1.0 across and his fight is
     * untouched — the same discipline the silhouettes are held to.
     */
    val bobHz: Float = 1f,
    val bobAmp: Float = 1f,
    val swayHz: Float = 1f,
    val swayAmp: Float = 1f,
    /**
     * How much of a landed punch he shows. A showboat rocks; a wardrobe barely notices; the
     * champion refuses to give you the satisfaction. Scales the squash, the wobble and the snap.
     */
    val reactMul: Float = 1f,
    /** Standing height on the canvas, so the card is not five men of identical stature. */
    val stature: Float = 1f,
    /**
     * HIS THREE ROUNDS HAVE HIS OWN NAMES. The round card is the one moment the game gets to say
     * something about a man before he hits you, and "THE STRUT" over the Anvil is a card announcing
     * the wrong fight. Three words each, and each says how that round is going to go.
     */
    val roundNames: Array<String> = arrayOf("ROUND ONE", "ROUND TWO", "THE LAST ROUND"),
) {
    // The card's hardness is folded in HERE, once, so every reader of these three sees the tuned
    // value and no call site can forget to apply it. The authored numbers stay readable above.
    val tellMul: Float = tellMul / HARDER
    val recoverMul: Float = recoverMul / HARDER
    val dmgMul: Float = dmgMul * HARDER

    /** How many spikes the crest carries at full health; 0 means he has no crest to read. */
    val hasCrest: Boolean get() = colourTells && id == "rooster"

    enum class Gimmick {
        /** Nothing beyond his numbers. */
        NONE,
        /**
         * FLURRY. Every chained follow-up inside a phrase takes [Fighter.gimmickK] × its authored
         * tell, so his second and third shots arrive on a fraction of the warning the first had.
         * Cheap individually; the phrase is the punch.
         */
        FLURRY,
        /**
         * COUNTER. Throw at him while his guard is up and he is not committed, and he answers with
         * a free shot inside [Fighter.gimmickK] world seconds. The fight asks him on every punch
         * that does not land clean — see [Boxer.wantsCounter].
         */
        COUNTER,
        /**
         * QUIET. He has no colour channel. The renderer draws no pupil flash, no crest, no white
         * glove; the tell is the shoulder, the foot and the dropped hand. Nothing about the TIMING
         * changes — this is purely a legibility tax, and it is the honest kind: the information is
         * all still there, in the shape.
         */
        QUIET,
        /**
         * TEMPO — the champion. His tell and his strike do not run on the world clock's floor like
         * everyone else's; they run on a clock the player's own motion drives, amplified by
         * [Fighter.gimmickK]. Stand still and he is slower than the Rooster. Move — dodge, punch,
         * even look about — and he is faster than the Sardine. He is the game explaining itself:
         * every verb you have costs time, and against him time is the opponent.
         */
        TEMPO,
    }

    companion object {
        /**
         * THE CARD'S HARDNESS, applied to every fighter at once — the owner played the Rooster and
         * said the card was too easy, so this is one dial rather than twenty edited numbers.
         *
         * It is 20 % on the three things that ARE the difficulty of a boxer, and deliberately not
         * on his health:
         *   - his TELL is 20 % shorter, so you get less warning;
         *   - his RECOVERY is 20 % shorter, so the opening you earned is smaller;
         *   - his PUNCH costs 20 % more.
         * Health is left alone because more HP does not make a fight harder, it makes it longer,
         * and a boxing round that outlasts the player's neck is a worse game and not a harder one.
         *
         * It is a MULTIPLIER ON THE PROFILE rather than new numbers in each row, so the relationships
         * the card was designed around — the Anvil telegraphs longer than the Sardine, Silk's
         * openings are stingier than the Rooster's — all survive being turned up.
         */
        const val HARDER = 1.20f

        private val MAGENTA = floatArrayOf(1.00f, 0.15f, 0.60f)
        private val ORANGE = floatArrayOf(1.00f, 0.45f, 0.05f)
        private val GREEN = floatArrayOf(0.30f, 1.00f, 0.35f)
        private val ICE = floatArrayOf(0.55f, 0.85f, 1.00f)
        private val GOLD = floatArrayOf(1.00f, 0.80f, 0.10f)
        private val RED = floatArrayOf(1.00f, 0.22f, 0.18f)
        private val CYAN = floatArrayOf(0.35f, 0.95f, 1.00f)
        private val VIOLET = floatArrayOf(0.60f, 0.20f, 1.00f)
        private val STEEL = floatArrayOf(0.70f, 0.75f, 0.85f)
        private val WHITE = floatArrayOf(0.92f, 1.00f, 1.00f)

        // ============================================================== 2. THE SARDINE
        /**
         * Sal "The Sardine" Marino — a small, jittery flyweight who never throws once. His phrases
         * are chains: the first shot buys the warning, the rest arrive inside it. Nothing he throws
         * hurts much, and that is the trap — the player who trades with him loses on volume, and
         * every punch they throw runs the clock his flurry is riding on.
         */
        private val SARDINE_R1 = listOf(
            Phrase("S1", listOf(
                Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = 0.22f), Step.Wait(0.9f),
                Step.Branch(Read.STILL_OR_GUARDING, then = listOf(Step.Hit(Attack.PECK_L, tellT = 0.22f))),
            )),
            Phrase("S2", listOf(
                Step.Hit(Attack.PECK_R), Step.Hit(Attack.PECK_L, tellT = 0.20f), Step.Hit(Attack.PECK_R, tellT = 0.20f),
                Step.Wait(1.1f),
                Step.Branch(Read.SLIPPED, then = listOf(Step.Hit(Attack.WING_L))),
            )),
            Phrase("S3", listOf(
                Step.Hit(Attack.WING_L), Step.Wait(0.7f), Step.Hit(Attack.PECK_L, tellT = 0.22f),
                Step.Branch(Read.DUCKED, then = listOf(Step.Hit(Attack.SUNRISE)), say = Lines.WAKE_UP),
            ), repeats = 1),
            Phrase("S4", listOf(
                Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = 0.20f),
                Step.Hit(Attack.WING_R, tellT = 0.30f), Step.Wait(1.2f),
            )),
        )
        private val SARDINE_R2 = listOf(
            Phrase("S1b", listOf(
                Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = 0.18f), Step.Hit(Attack.PECK_L, tellT = 0.18f),
                Step.Hit(Attack.PECK_R, tellT = 0.18f), Step.Wait(0.9f),
            )),
            Phrase("S2b", listOf(
                Step.Feint(Boxer.Feint.HALF_PECK), Step.Wait(0.25f),
                Step.Branch(Read.SLIPPED, then = listOf(Step.Hit(Attack.PECK_R, tellT = 0.20f), Step.Hit(Attack.WING_L, tellT = 0.28f))),
                Step.Branch(Read.STILL_OR_GUARDING, then = listOf(Step.Hit(Attack.WING_R))),
            )),
            Phrase("S3b", listOf(
                Step.Hit(Attack.WING_L), Step.Hit(Attack.PECK_R, tellT = 0.18f), Step.Wait(0.8f),
                Step.Hit(Attack.SUNRISE, tellT = 0.45f),
            ), hpBelow = 0.7f),
        )
        private val SARDINE_R3 = listOf(
            Phrase("S1c", listOf(
                Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = 0.15f), Step.Hit(Attack.PECK_L, tellT = 0.15f),
                Step.Hit(Attack.PECK_R, tellT = 0.15f), Step.Hit(Attack.WING_R, tellT = 0.26f),
            )),
            Phrase("S2c", listOf(
                Step.Feint(Boxer.Feint.HALF_PECK), Step.Hit(Attack.PECK_R, tellT = 0.16f),
                Step.Feint(Boxer.Feint.HALF_PECK, Hand.RIGHT), Step.Hit(Attack.WING_L, tellT = 0.26f),
                Step.Wait(0.8f),
            )),
            Phrase("S3c", listOf(
                Step.Hit(Attack.SUNRISE, tellT = 0.40f), Step.Wait(1.0f),
                Step.Hit(Attack.PECK_L, tellT = 0.15f), Step.Hit(Attack.PECK_R, tellT = 0.15f),
            ), hpBelow = 0.5f),
        )

        // ============================================================== 3. THE ANVIL
        /**
         * Duke "The Anvil" Odell — vast, unhurried, and the most dangerous thing on the card to a
         * player who has just learned to punch. His tells are enormous. His openings are real. And
         * if you throw at a raised guard he takes your arm off for it: [Gimmick.COUNTER].
         *
         * His phrases are short and end in long waits ON PURPOSE — the wait is the bait.
         */
        private val ANVIL_R1 = listOf(
            Phrase("N1", listOf(
                Step.Hit(Attack.WING_R), Step.Wait(2.0f),
                Step.Branch(Read.DUCKED, then = listOf(Step.Open(0.5f))),
            )),
            Phrase("N2", listOf(
                Step.Hit(Attack.WING_L), Step.Wait(2.0f),
                Step.Branch(Read.GUARD_COUNTERED, then = listOf(Step.Open(0.7f)), say = Lines.WAKE_UP),
            ), repeats = 1),
            Phrase("N3", listOf(
                Step.Hit(Attack.PECK_R), Step.Wait(1.6f), Step.Hit(Attack.WING_R), Step.Wait(1.8f),
            )),
            Phrase("N4", listOf(
                Step.Hit(Attack.SUNRISE), Step.Wait(2.4f),
                Step.Branch(Read.SLIPPED, then = listOf(Step.Open(0.6f))),
            ), hpBelow = 0.8f),
        )
        private val ANVIL_R2 = listOf(
            Phrase("N1b", listOf(
                Step.Hit(Attack.WING_R), Step.Wait(0.9f), Step.Hit(Attack.WING_L), Step.Wait(1.7f),
            )),
            Phrase("N2b", listOf(
                Step.Hit(Attack.WING_L), Step.Wait(1.4f), Step.Hit(Attack.SUNRISE), Step.Wait(1.8f),
            )),
            Phrase("N3b", listOf(
                Step.Feint(Boxer.Feint.HALF_STAMP), Step.Wait(0.6f),
                Step.Branch(Read.STILL_OR_GUARDING, then = listOf(Step.Hit(Attack.WING_R))),
                Step.Branch(Read.DUCKED, then = listOf(Step.Hit(Attack.WING_L))),
            )),
        )
        private val ANVIL_R3 = listOf(
            Phrase("N1c", listOf(
                Step.Hit(Attack.WING_R), Step.Hit(Attack.WING_L, tellT = 0.34f), Step.Wait(1.3f),
                Step.Award("STEP +400", 400),
            )),
            Phrase("N2c", listOf(
                Step.Feint(Boxer.Feint.HALF_STAMP), Step.Hit(Attack.SUNRISE, tellT = 0.50f), Step.Wait(1.5f),
            )),
            Phrase("N3c", listOf(
                Step.Hit(Attack.WING_L), Step.Wait(1.0f), Step.Hit(Attack.WING_R), Step.Wait(1.0f),
                Step.Hit(Attack.SUNRISE), Step.Wait(2.0f),
            ), hpBelow = 0.5f),
        )

        // ============================================================== 4. SILK
        /**
         * "Silk" Sorensen — a technician behind a mirrored visor. He never lights up. Every tell
         * he owns is a shape: the shoulder that dips, the foot that plants, the glove that leaves
         * the frame. His timings sit between the Rooster's and the Sardine's, which is the point —
         * he is not hard because he is fast, he is hard because you have been reading the wrong
         * channel for three fights.
         */
        private val SILK_R1 = listOf(
            Phrase("K1", listOf(
                Step.Hit(Attack.PECK_L), Step.Wait(1.0f), Step.Hit(Attack.PECK_R), Step.Wait(1.0f),
                Step.Branch(Read.STILL_OR_GUARDING, then = listOf(Step.Hit(Attack.WING_L))),
            )),
            Phrase("K2", listOf(
                Step.Hit(Attack.WING_R), Step.Wait(1.1f), Step.Hit(Attack.WING_L), Step.Wait(1.2f),
                Step.Branch(Read.DUCKED, then = listOf(Step.Hit(Attack.WING_L)), say = Lines.WAKE_UP),
            ), repeats = 1),
            Phrase("K3", listOf(
                Step.Hit(Attack.PECK_R), Step.Hit(Attack.WING_R, tellT = 0.34f), Step.Wait(1.3f),
            )),
            Phrase("K4", listOf(
                Step.Hit(Attack.SUNRISE), Step.Wait(1.6f),
                Step.Branch(Read.CENTRED, then = listOf(Step.Hit(Attack.PECK_L, tellT = 0.26f))),
            ), hpBelow = 0.75f),
        )
        private val SILK_R2 = listOf(
            Phrase("K1b", listOf(
                Step.Feint(Boxer.Feint.HALF_PECK), Step.Wait(0.3f),
                Step.Branch(Read.SLIPPED_L, then = listOf(Step.Hit(Attack.PECK_R, tellT = 0.24f))),
                Step.Branch(Read.SLIPPED_R, then = listOf(Step.Hit(Attack.WING_L, tellT = 0.30f))),
                Step.Branch(Read.STILL_OR_GUARDING, then = listOf(Step.Hit(Attack.SUNRISE))),
            )),
            Phrase("K2b", listOf(
                Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = 0.22f), Step.Hit(Attack.WING_R, tellT = 0.32f),
                Step.Wait(1.1f),
            )),
            Phrase("K3b", listOf(
                Step.Hit(Attack.WING_L), Step.Wait(0.8f), Step.Hit(Attack.SUNRISE, tellT = 0.55f), Step.Wait(1.4f),
            )),
        )
        private val SILK_R3 = listOf(
            Phrase("K1c", listOf(
                Step.Feint(Boxer.Feint.HALF_PECK), Step.Hit(Attack.WING_R, tellT = 0.28f),
                Step.Hit(Attack.PECK_L, tellT = 0.18f), Step.Wait(0.9f),
            )),
            Phrase("K2c", listOf(
                Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = 0.18f),
                Step.Hit(Attack.SUNRISE, tellT = 0.42f), Step.Wait(1.2f),
            )),
            Phrase("K3c", listOf(
                Step.Hit(Attack.WING_R), Step.Hit(Attack.WING_L, tellT = 0.28f), Step.Wait(1.0f),
            ), hpBelow = 0.5f),
        )

        // ============================================================== 5. THE METRONOME
        /**
         * Max "The Metronome" Voss — the champion, and the only opponent who has read the rules of
         * this universe. His pattern is deliberately PLAIN: no feint spam, no unreadable chains,
         * long clean phrases. He does not need tricks, because his gimmick is the game.
         *
         * Everything he does runs on the player's own motion ([Gimmick.TEMPO]). A still player
         * fights a slow, honest, entirely readable boxer. The moment that player dodges, punches or
         * looks around — the moment they do anything the last four fights taught them to do — he
         * accelerates. Beating him is not a matter of moving better; it is a matter of moving
         * LESS, and only when it is worth it. Every mechanic in the game points here.
         */
        private val METRONOME_R1 = listOf(
            Phrase("M1", listOf(
                Step.Hit(Attack.PECK_L), Step.Wait(1.1f), Step.Hit(Attack.PECK_R), Step.Wait(1.1f),
                Step.Hit(Attack.WING_R), Step.Wait(1.3f),
            )),
            Phrase("M2", listOf(
                Step.Hit(Attack.WING_L), Step.Wait(1.2f), Step.Hit(Attack.WING_R), Step.Wait(1.2f),
                Step.Branch(Read.DUCKED, then = listOf(Step.Hit(Attack.SUNRISE))),
            )),
            Phrase("M3", listOf(
                Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = 0.26f), Step.Wait(1.0f),
                Step.Hit(Attack.SUNRISE), Step.Wait(1.5f),
            )),
        )
        private val METRONOME_R2 = listOf(
            Phrase("M1b", listOf(
                Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = 0.24f),
                Step.Hit(Attack.WING_R, tellT = 0.34f), Step.Wait(1.0f),
            )),
            Phrase("M2b", listOf(
                Step.Hit(Attack.WING_L), Step.Wait(0.9f), Step.Hit(Attack.SUNRISE), Step.Wait(1.2f),
                Step.Branch(Read.SLIPPED, then = listOf(Step.Hit(Attack.PECK_R, tellT = 0.22f))),
            )),
            Phrase("M3b", listOf(
                Step.Hit(Attack.WING_R), Step.Hit(Attack.WING_L, tellT = 0.30f), Step.Wait(1.1f),
            )),
        )
        private val METRONOME_R3 = listOf(
            Phrase("M1c", listOf(
                Step.Hit(Attack.PECK_L), Step.Hit(Attack.PECK_R, tellT = 0.20f),
                Step.Hit(Attack.WING_R, tellT = 0.30f), Step.Hit(Attack.SUNRISE, tellT = 0.44f),
            )),
            Phrase("M2c", listOf(
                Step.Hit(Attack.SUNRISE), Step.Wait(0.9f), Step.Hit(Attack.SUNRISE), Step.Wait(1.4f),
            ), hpBelow = 0.6f),
            Phrase("M3c", listOf(
                Step.Hit(Attack.WING_L), Step.Hit(Attack.WING_R, tellT = 0.28f),
                Step.Hit(Attack.PECK_L, tellT = 0.18f), Step.Wait(1.0f),
            )),
        )

        // ============================================================== the card
        /** #1 — the grammar. Unchanged: every number is the one the owner has already played. */
        val ROOSTER = Fighter(
            id = "rooster", name = "THE ROOSTER", billing = "ROY RUDD - THE STRUTTING CHAMPION OF NOWHERE",
            asset = "boxer", hp = intArrayOf(100, 120, 140),
            roundNames = arrayOf("THE STRUT", "THE RUFFLE", "THE COCKFIGHT"),
            patterns = emptyList(),          // he keeps Boxer's own PATTERN_R1..R3
            primary = MAGENTA, trunks = CYAN, glove = RED,
        )

        val SARDINE = Fighter(
            id = "sardine", name = "THE SARDINE", billing = "SAL MARINO - NEVER THROWS JUST ONE",
            asset = "boxer_sardine", hp = intArrayOf(110, 130, 150),
            tellMul = 0.78f, strikeMul = 0.92f, recoverMul = 0.85f, dmgMul = 0.7f,
            hangMul = 0.85f, openMul = 0.85f, feintsFromRound = 2,
            // never still: a fast shallow jitter, a quick nervous roll, and he flinches at everything
            bobHz = 2.4f, bobAmp = 0.75f, swayHz = 2.0f, swayAmp = 0.6f, reactMul = 1.35f, stature = 0.90f,
            gimmick = Gimmick.FLURRY, gimmickK = 0.6f,
            roundNames = arrayOf("THE SHOAL", "THE BOIL", "THE FEEDING"),
            patterns = listOf(SARDINE_R1, SARDINE_R2, SARDINE_R3),
            primary = GREEN, trunks = GOLD, glove = ORANGE,
        )

        val ANVIL = Fighter(
            id = "anvil", name = "THE ANVIL", billing = "DUKE ODELL - HE WAITS FOR YOU TO SWING",
            asset = "boxer_anvil", hp = intArrayOf(150, 180, 210),
            tellMul = 1.25f, strikeMul = 1.0f, recoverMul = 1.15f, dmgMul = 1.7f,
            hangMul = 1.1f, openMul = 1.0f, feintsFromRound = 2,
            // a slow deep heave, almost no roll, and he hardly registers being hit
            bobHz = 0.45f, bobAmp = 1.9f, swayHz = 0.4f, swayAmp = 0.5f, reactMul = 0.45f, stature = 1.12f,
            gimmick = Gimmick.COUNTER, gimmickK = 0.45f,
            roundNames = arrayOf("THE WEIGHT", "THE SWING", "THE DROP"),
            patterns = listOf(ANVIL_R1, ANVIL_R2, ANVIL_R3),
            primary = ORANGE, trunks = RED, glove = GOLD,
        )

        val SILK = Fighter(
            id = "silk", name = "SILK", billing = "SORENSEN - NOTHING SHOWS ON HIM",
            asset = "boxer_silk", hp = intArrayOf(120, 145, 170),
            tellMul = 0.88f, strikeMul = 0.95f, recoverMul = 0.9f, dmgMul = 1.1f,
            hangMul = 0.9f, openMul = 0.8f, feintsFromRound = 1,
            // a long lazy weight-shift and almost no bounce: a man who is not going to show you anything
            bobHz = 0.7f, bobAmp = 0.35f, swayHz = 0.55f, swayAmp = 1.7f, reactMul = 0.7f, stature = 1.06f,
            gimmick = Gimmick.QUIET, gimmickK = 0f,
            roundNames = arrayOf("NOTHING SHOWS", "STILL NOTHING", "TOO LATE"),
            patterns = listOf(SILK_R1, SILK_R2, SILK_R3),
            primary = ICE, trunks = VIOLET, glove = STEEL, colourTells = false,
        )

        val METRONOME = Fighter(
            id = "metronome", name = "THE METRONOME", billing = "MAX VOSS - HE KEEPS YOUR TIME",
            asset = "boxer_metronome", hp = intArrayOf(140, 170, 200),
            tellMul = 1.0f, strikeMul = 1.0f, recoverMul = 0.85f, dmgMul = 1.4f,
            hangMul = 1.0f, openMul = 0.7f, feintsFromRound = 3,
            // A TICK. Square, upright, metronomic: no roll at all and a bounce exactly on the beat.
            // The stillest man on the card until the player moves, which is the joke and the threat.
            bobHz = 1.0f, bobAmp = 0.5f, swayHz = 0f, swayAmp = 0f, reactMul = 0.6f, stature = 1.04f,
            gimmick = Gimmick.TEMPO, gimmickK = 2.2f,
            roundNames = arrayOf("ANDANTE", "ALLEGRO", "PRESTO"),
            patterns = listOf(METRONOME_R1, METRONOME_R2, METRONOME_R3),
            primary = GOLD, trunks = WHITE, glove = MAGENTA,
        )

        /** The card, in order. The bout ladder walks this; index 0 is the first fight. */
        val CARD = listOf(ROOSTER, SARDINE, ANVIL, SILK, METRONOME)

        fun byId(id: String): Fighter = CARD.firstOrNull { it.id == id } ?: ROOSTER
        fun at(i: Int): Fighter = CARD[i.coerceIn(0, CARD.size - 1)]
    }
}
