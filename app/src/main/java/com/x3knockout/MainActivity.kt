package com.x3knockout

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.x3knockout.audio.Music
import com.x3knockout.audio.Sfx
import com.x3knockout.audio.Voice
import com.x3knockout.audio.VoiceBus
import com.x3knockout.engine.Game
import com.x3knockout.engine.GameHost
import com.x3knockout.engine.Swipe
import com.x3knockout.gl.GLRenderer
import com.x3knockout.head.HeadTracker
import com.x3knockout.head.MotionTracker
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * X3Knockout — the head aims, the temple pad commits, and the body is the clock:
 *  - TAP is THE ONE COMMIT: steal a disc in the catch zone, else catch your own, else throw, else a
 *    soft click that still spends its quantum (arrives as a KEY on the glasses; touch taps too)
 *  - SWIPE LEFT / RIGHT is the HOP to the adjacent platform, one hop per gesture, settled on
 *    finger-up (the prototype's `PAD` sidestep fallback is gone — DESIGN.md §5)
 *  - SWIPE DOWN raises the DEFLECTOR on the crossing and drops it on the lift; a flick is just a
 *    short hold, and the 0.5 s pop is the FLOOR on the arc's life, not a ceiling
 *  - SWIPE UP is RECALL, once per gesture
 *  - DOUBLE-TAP opens the settings, TRIPLE-TAP re-centres the head — and both are decided by
 *    COUNTING finger-lifts, not by the single KEYCODE_BACK this hardware injects for a double-tap.
 *    The pad's own verdict is corroboration, never the decision; see [lastTouchTapMs] for why.
 *  - the left temple (cyttsp6) is the system volume pad and is ignored.
 *
 * THE TWO VERTICAL VERBS, AND WHY THE PAD IS READ IN TWO DIFFERENT WAYS.
 *
 * A vertical gesture is classified on the ACTION_MOVE that first crosses the threshold rather than
 * waiting for finger-up, because the deflector has to be up the instant the finger commits to it
 * and not a gesture later. The axis is LATCHED at that first crossing: a gesture that crossed
 * sideways can never raise the shield no matter where the finger wanders afterwards, so a held
 * horizontal is one hop and only one hop.
 *
 * A DOWNWARD crossing calls [Game.deflectorStart] and the finger-lift calls [Game.deflectorEnd];
 * an UPWARD crossing fires [Game.recall] once and ENDS the gesture there and then, because a
 * one-shot has nothing to hold and nothing to reverse. The tank's reversal hysteresis is therefore
 * gone: there is no verb in this game that a finger changing its mind mid-swipe should switch to.
 *
 * OFF THE ARENA THE OLD CLASSIFIER IS USED UNCHANGED — see [Game.holdDriveArmed]. On the title and
 * in the settings the pad is read once, on finger-up, exactly as before, which is what keeps the
 * menu at one swipe per step however long the pad is held.
 *
 * The suite's standing rule is that a long-press belongs to the system (the X3 reserves a temple
 * hold for its quick-settings shade), and this is the documented exception to it: a 2.5 s injected
 * hold kept this activity focused with no shade and nothing in logcat, and the owner then held the
 * physical pad with a real finger and confirmed the same. Test B3 holds for 1 / 3 / 8 s and checks
 * it again with a shield on the glass.
 */
class MainActivity : Activity(), GameHost {

    private companion object {
        /** The gesture is settled the old way, once, on finger-up. */
        const val AXIS_UP = 1
        /** The gesture crossed vertically on the arena and is holding the deflector until it lifts. */
        const val AXIS_DRIVE = 2
        /**
         * The gesture has ALREADY been acted on and its finger-up means nothing.
         *
         * This state exists because of a bug that reached the glasses: an upward crossing fired
         * `recall()` on the ACTION_MOVE and then, because the axis had been latched to [AXIS_UP],
         * the finger-lift classifier settled the very same swipe a second time and recalled again
         * — two verbs and two action quanta for one movement of one finger. A verb that is
         * committed on the crossing has to say so, and [AXIS_UP] cannot say it, because [AXIS_UP]
         * means the opposite: *nothing has happened yet, decide on the lift*.
         */
        const val AXIS_DONE = 3
        const val SRC_NONE = 0
        const val SRC_TOUCH = 1
        const val SRC_KEY = 2

        /**
         * HOW LONG THE APP WAITS AFTER A FINGER-LIFT TO FIND OUT WHETHER MORE TAPS ARE COMING.
         *
         * It has to be at least as wide as the pad's OWN double-tap window, whatever that turns out
         * to be. If this is the narrower of the two, a gesture the pad itself calls a double-tap is
         * split into two single taps in here, and in the settings that is two rows actioned instead
         * of one menu closed. 320 sits a shade above Android's own 300 ms default and it is the one
         * number in this file that is still a guess — `getevent -lt` against a real finger is what
         * turns it into a measurement, and until somebody does that this is the seam to suspect.
         */
        const val BURST_MS = 320L
        /**
         * HOW LONG A TOUCH TAP GOES ON SUPPRESSING THE PAD'S OWN TRAILING VERDICT ABOUT THAT TAP.
         *
         * Must comfortably clear [BURST_MS] plus however long the pad's classifier takes to make up
         * its mind, because the failure on the far side of it is not cosmetic: a KEYCODE_BUTTON_A
         * that lands after the burst has already acted gets counted as a SECOND tap, and one
         * physical press on the QUIT row would then both arm the confirm and spend it. Generous on
         * purpose. The only thing a too-wide window costs is a genuine remote-control tap taken
         * within 900 ms of touching the pad, on a headset that has no second pointing device.
         */
        const val ECHO_MS = 900L
    }

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    /** The SYSTEM: the assets `voice` directory, Zarvox through a crusher. The game itself, talking. */
    private lateinit var voice: Voice
    /** The PILOT: the assets `voice_hero` directory, the owner's fish.audio model. Talking back. */
    private lateinit var hero: Voice
    /** The floor the two of them share — see [VoiceBus]. They never speak at once. */
    private val voiceBus = VoiceBus()
    private lateinit var music: Music
    private lateinit var head: HeadTracker
    private lateinit var motion: MotionTracker
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    private val ui = Handler(Looper.getMainLooper())

    /**
     * WHEN THE RAW TOUCH STREAM LAST REPORTED A FINGER LIFTING OFF AFTER A TAP.
     *
     * This one timestamp is the whole key/touch dedupe, and it stands where a latch used to stand
     * that switched the game's ears off twice. The shape of it comes down to two facts about this
     * pad, and they are worth writing out longhand because having them the wrong way round is what
     * shipped, both times:
     *
     *  1. THE TOUCH STREAM IS ALWAYS THERE AND IT IS ALWAYS FIRST. cyttsp5_mt hands this app the
     *     raw multitouch protocol for every gesture on the right temple. Nothing interprets it,
     *     nothing can decide to withhold it, and a finger-lift is reported when the finger lifts.
     *  2. THE KEY STREAM MAY NOT BE THERE AT ALL, AND WHERE IT IS, IT IS LATE. There is no .kl file
     *     for cyttsp5_mt on this device — at the kernel the pad is KEY_F1..KEY_F8 plus a touch
     *     protocol, nothing a game could use. A RayNeo system service watches the same pad and
     *     injects KEYCODE_BUTTON_A for a tap and a single KEYCODE_BACK for a double-tap. It cannot
     *     decide which of those a gesture was until the gesture is OVER, so its verdict always
     *     trails the very finger-lift it describes, by an amount nobody here has measured. On
     *     hardware without that service it simply never comes.
     *
     * So the touch leads and the key follows, and suppression may only ever run in that direction:
     * a key tap within [ECHO_MS] of a touch tap is the pad describing a press this app already
     * counted, and it is dropped. A TOUCH IS NEVER DROPPED BECAUSE OF ANYTHING A KEY DID. Both
     * times this game went deaf it went deaf by breaking that one rule — first by latching "the pad
     * classifies taps" on a KEYCODE_BACK, then by blanking the touch path for 600 ms after any key
     * gesture — and both times the mechanism was identical: the possibly-absent source was made the
     * gatekeeper of the always-present one, so on hardware where the key never turns up there was
     * nothing left to tap with. Run it this way round and the worst a misjudgement can do is let
     * one redundant tap through, which the player sees happen and can undo. The other way round the
     * pad goes silent and there is nothing the player can do at all.
     */
    private var lastTouchTapMs = -10_000L
    /**
     * THE BURST — the physical taps counted so far, from whichever source saw them. See [countTap].
     *
     * Nothing in this group outlives its gesture. [burstN] is zeroed the moment the burst resolves,
     * and the only two things that survive a finished gesture, [lastTouchTapMs] and [lastMultiMs],
     * are timestamps measured against a fixed window, so they expire on their own without anybody
     * having to remember to clear them. There is deliberately not one boolean in this class that
     * persists across gestures. A latch is a permanent policy built out of a single ambiguous
     * observation, and a temple pad with no key layout is the last place to go looking for one.
     */
    private var burstN = 0
    private var burstR: Runnable? = null
    /** Did a tap in this burst already reach the engine live? Then the burst must not send it again. */
    private var burstLive = false
    /** Was the hull in a Recognizer's clamp when the burst opened? Then every tap in it is a pull. */
    private var burstClamped = false
    /** When a double- or triple-tap was last committed — the credit a trailing BACK spends. */
    private var lastMultiMs = -10_000L
    private var downX = 0f; private var downY = 0f; private var downT = 0L
    /** The furthest this gesture ever got from its touch-down. A swipe is judged on this, not on
     *  where the finger happened to be resting when it lifted. */
    private var farDx = 0f; private var farDy = 0f
    /** 0 = not yet classified, [AXIS_UP] = settle it on finger-up as before, [AXIS_DRIVE] = driving. */
    private var gestureAxis = 0
    /** Which way the live touch drive is going: -1 = finger up = forward, +1 = finger down = back. */
    private var driveSign = 0
    /** The furthest the finger has got in [driveSign]'s direction — the anchor a reversal is measured from. */
    private var driveExtremeY = 0f
    /** Who owns the live drive: [SRC_NONE], [SRC_TOUCH] or [SRC_KEY]. One source at a time. */
    private var driveOwner = SRC_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        voice = Voice(this, "voice", "m4a", voiceBus).also { it.load() }
        hero = Voice(this, "voice_hero", "mp3", voiceBus).also { it.load() }
        music = Music(this).also { it.load() }
        head = HeadTracker(this)
        motion = MotionTracker(this).also { it.logTo(java.io.File(cacheDir, "motion.log")) }
        // Effects duck under EITHER voice, and now so does the music: with two speakers trading
        // lines, the track underneath them is the difference between a conversation and a wash.
        sfx.duckProvider = { voice.isSpeaking || hero.isSpeaking }
        game = Game(store, this)
        game.motionSrc = motion
        game.applyStepSense()
        voice.onLineStart = { id -> refreshDuck(); glView.queueEvent { game.onVoiceLineStart(id) } }
        voice.onLineEnd = { id -> refreshDuck(); glView.queueEvent { game.onVoiceLineEnd(id) } }
        // The pilot's caption is raised on the beat its clip actually starts — see Game.onHeroLineStart.
        hero.onLineStart = { id -> refreshDuck(); glView.queueEvent { game.onHeroLineStart(id) } }
        hero.onLineEnd = { refreshDuck() }
        game.debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        renderer = GLRenderer(this, game, head, motion, store).also { it.sbs = store.sbs }
        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            // EIGHT BITS PER CHANNEL, asked for out loud. GLSurfaceView's own default chooser asks
            // for 5-6-5, and this renderer spends most of its light in the bottom of the range: the
            // fog floor, the far walls and the rung ladder all live at alphas that land on values
            // like (0,4,1) and (1,6,2). In 565 those quantise to a handful of steps and the arena's
            // distance cue turns into banding, so the strokes that say "far away" stop saying it.
            // ALPHA STAYS 0, deliberately: that is what the default chooser asks for and what this
            // waveguide is already composited with. On a see-through display the window's alpha
            // channel is not a free parameter — black is transparency here, and asking for an 8-bit
            // alpha invites the compositor to blend the surface differently.
            // The 16 is a depth buffer the renderer no longer uses (see GLRenderer's OCCLUSION note:
            // hiding is decided on the CPU now). It is left in the request because this is the exact
            // config verified on the glasses, and a depth attachment that is never cleared, tested
            // or read costs a tile buffer nobody touches.
            setEGLConfigChooser(8, 8, 8, 0, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        applyVolume(store.volume)
        music.enabled = store.music
        voice.enabled = store.voice; hero.enabled = store.voice
        // DEBUGGABLE BUILDS ONLY (TEST_PLAN.md §1): `am start … --ei level N --ei round M --ei
        // lives K --ef floor F --ei restarts R` drops the owner straight into the round a test
        // block is about. It NEVER writes records — a high score set from halfway up the ladder
        // with three fresh lives is not a high score — so the store is told before the game boots.
        val dbg = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val lvl = if (dbg) (intent?.getIntExtra("level", 0) ?: 0) else 0
        if (lvl > 0) store.recordsEnabled = false
        game.boot()
        music.play()
        if (lvl > 0) {
            val rnd = intent?.getIntExtra("round", 1) ?: 1
            val lv = intent?.getIntExtra("lives", 0) ?: 0
            val fl = intent?.getFloatExtra("floor", -1f) ?: -1f
            val rs = intent?.getIntExtra("restarts", 0) ?: 0
            glView.queueEvent { game.debugStart(lvl, rnd, lv, fl, rs) }
        }
    }

    // ------------------------------------------------------------ GameHost (any thread)

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun hum(level: Float, rate: Float) = sfx.hum(level, rate)
    override fun say(id: String, urgent: Boolean, patienceMs: Long) = voice.say(id, urgent, patienceMs)
    override fun sayAll(ids: List<String>) = voice.sayAll(ids)
    override fun stopVoice() { voice.stop(); refreshDuck() }
    override fun hero(id: String, patienceMs: Long) = hero.say(id, false, patienceMs)
    override fun stopHero() { hero.stop(); refreshDuck() }
    override fun musicEnabled(on: Boolean) { music.enabled = on }
    override fun voiceEnabled(on: Boolean) {
        voice.enabled = on; hero.enabled = on
        if (!on) { voice.stop(); hero.stop(); refreshDuck() }
    }
    override fun recentreHead() { head.recentre() }
    override fun recentreYaw() { head.recentreYaw() }
    override fun applyVolume(v0to10: Int) {
        val v = v0to10 / 10f
        // The pilot sits a shade under the system voice: the machine is loud because it does not
        // care, and the program talking back over its own stolen code is the quieter of the two.
        music.volume = 0.55f * v; sfx.volume = 0.9f * v; voice.volume = 1f * v; hero.volume = 0.92f * v
    }
    override fun voiceDurationMs(id: String): Int = voice.durations[id] ?: 0

    /**
     * Leave the game. The sign-off line is already speaking when this arrives, so the exit waits
     * out the clip rather than cutting the machine off mid-sentence — the one place in the game
     * where the system voice gets the last word. finish() and not a force-stop: the launcher drops
     * a force-stopped app off the Mercury drawer, and a game you quit politely should still be
     * there when you want it again.
     */
    override fun quitGame() {
        val hold = (voice.durations["end_of_line"] ?: 0).coerceIn(0, 2000) + 250L.toInt()
        ui.postDelayed({ if (!isFinishing) finish() }, hold.toLong())
    }
    override fun heroDurationMs(id: String): Int = hero.durations[id] ?: 0
    override fun voiceBusy(): Boolean = voice.isSpeaking || hero.isSpeaking

    private fun refreshDuck() { music.duck = voice.isSpeaking || hero.isSpeaking }

    // --------------------------------------------------------------- input

    /**
     * ONE PHYSICAL TAP. Both sources end up here, because what a gesture MEANS is decided by how
     * many taps arrived and never by which wire carried them: one is a shot or a menu row, two is
     * the settings menu, three re-centres the head. Counting is the only arrangement that survives
     * all three of the hardware stories this file has to live with — a pad that classifies AND
     * reports touch, a pad that only reports touch, and a plain Bluetooth remote that only sends
     * keys — because it asks each source for the one thing every source can actually supply.
     *
     * ON THE ARENA THE TAP IS COMMITTED THE INSTANT IT IS COUNTED, and the burst goes on counting
     * underneath it. This is the one asymmetry in the file and it is on purpose. A shell that
     * arrives 320 ms after you asked for it is not the shot you asked for, and breaking a
     * Recognizer's clamp is a mashing contest against a meter that is bleeding out while you tap.
     * The price is that a double-tap taken in the arena fires one shell on its way into the pause
     * menu. That is the right way round: a stray shell is one visible thing the player watches
     * happen, and a cannon with a beat of lag is a game that feels broken everywhere, all the time.
     *
     * OFF THE ARENA NOTHING IS COMMITTED UNTIL THE BURST SETTLES. There is no shot to miss on the
     * title, on the game-over card or in the settings, and an eager tap there is exactly how a
     * double-tap used to action the highlighted row on its way to closing the menu.
     */
    private fun countTap(urgent: Boolean) {
        burstN++
        if (burstN == 1) burstClamped = false   // x3knockout has no clamp; every burst may resolve
        if (urgent) { burstLive = true; glView.queueEvent { game.tap() } }
        burstR?.let { ui.removeCallbacks(it) }
        val r = Runnable { resolveBurst() }
        burstR = r
        ui.postDelayed(r, BURST_MS)
    }

    /**
     * The burst is over; say what the taps in it added up to.
     *
     * FOUR OR MORE IS NOT A GESTURE, IT IS A PLAYER MASHING, and it resolves to nothing at all.
     * That is what keeps the settings menu out of a fight: escaping a clamp is [Game.CAPT_TAPS]
     * taps inside a second and a half against a meter that decays between them, so anybody actually
     * getting out runs the count well past three, and without this rule the tail of every
     * successful escape would pause the game.
     *
     * CLAMPED IS THE SAME RULE HELD HARDER. While a machine has the hull there is no double-tap and
     * no triple-tap on offer: the taps have already gone to the engine live, every one of them is a
     * pull against the grip, and a burst that happens to stop on two must not open the pause menu
     * on top of a struggle the player is in the middle of winning. It is read once, when the burst
     * opens, so escaping mid-burst cannot turn the last two pulls into a menu toggle either.
     */
    private fun resolveBurst() {
        val n = burstN; val live = burstLive; val clamped = burstClamped
        burstN = 0; burstR = null; burstLive = false; burstClamped = false
        if (n >= 2) lastMultiMs = SystemClock.uptimeMillis()
        if (clamped) return
        when (n) {
            1 -> if (!live) glView.queueEvent { game.tap() }
            2 -> glView.queueEvent { game.doubleTap() }
            3 -> glView.queueEvent { game.tripleTap() }
            else -> {}
        }
    }

    /** Settle a burst NOW rather than at its deadline, because something else is about to land. */
    private fun flushBurst() { burstR?.let { ui.removeCallbacks(it); resolveBurst() } }

    /** Drop a burst on the floor: the window is going away and its taps are going with it. */
    private fun cancelBurst() {
        burstR?.let { ui.removeCallbacks(it) }
        burstR = null; burstN = 0; burstLive = false; burstClamped = false
    }

    /**
     * THE PAD'S OWN VERDICT THAT A GESTURE WAS A DOUBLE-TAP, and it is worth being clear about what
     * that verdict is actually worth here. It is not the gesture. The two finger-lifts underneath
     * it are the gesture, and they were counted before this arrived. So a BACK that lands on top of
     * a burst this app is already assembling is redundant, and it is eaten: the burst knows how
     * many taps there were and the burst decides whether that is two (settings) or three (re-centre
     * the head). Letting the BACK decide instead is precisely how the triple-tap stopped working —
     * the BACK for taps 1 and 2 fired its own toggle before tap 3 had even been looked at, so the
     * menu opened underneath a gesture that was supposed to straighten the horizon.
     *
     * A BACK with no touches under it is a different animal: the launcher's back gesture, a paired
     * remote, `adb shell input keyevent 4`, or pad hardware that classifies without handing us the
     * touch stream at all. Nothing else is going to toggle the menu for those, so this does.
     *
     * Note what is NOT here any more: this used to stamp a clock that the touch path then checked
     * before it would accept a tap. That is the wrong direction and it cost the game its taps — see
     * [lastTouchTapMs]. A BACK now suppresses nothing but itself.
     */
    private fun onBack() {
        val now = SystemClock.uptimeMillis()
        // Eaten: the taps this BACK is describing are already counted, or are still arriving. The
        // second test covers the gap between a finger going down and the burst existing, and it is a
        // timestamp rather than a "finger is down" flag on purpose — a flag whose clearing event is
        // an ACTION_UP is a flag that wedges the first time a window change eats one.
        if (burstR != null || now - downT < 400) return
        // Eaten: the burst that describes this BACK has already committed. The pad can trail its own
        // verdict past the end of the burst window, and without this a slow BACK toggles the menu
        // straight back shut behind the double-tap that just opened it.
        if (now - lastMultiMs < ECHO_MS) return
        glView.queueEvent { game.doubleTap() }
    }

    /**
     * A swipe settles any tap still being counted BEFORE it moves anything. Off the arena a burst
     * can still be in flight when the next gesture finishes, and a tap that resolves afterwards
     * would action whichever row the swipe had just moved the highlight to — which on this menu is
     * how a stray flick could put the cursor on QUIT and a 300 ms old tap could arm it.
     */
    private fun onSwipe(dir: Swipe) { flushBurst(); glView.queueEvent { game.swipe(dir) } }

    /**
     * A VERTICAL GESTURE HAS COMMITTED. [sign] is -1 for UP (the finger went up the pad) and +1
     * for DOWN. The owner check means the gesture belongs to whichever input path started it:
     * should this hardware ever deliver one physical gesture down BOTH the touch and the D-pad
     * paths, the second cannot raise a second shield or steal the release.
     *
     * UP is a one-shot and does not become an owner: [Game.recall] fires here and the gesture is
     * over. DOWN takes ownership, because the arc stays up until the finger lifts.
     */
    private fun startDrive(sign: Int, source: Int) {
        if (driveOwner != SRC_NONE && driveOwner != source) return
        if (sign < 0) { glView.queueEvent { game.recall() }; return }
        driveOwner = source
        glView.queueEvent { game.deflectorStart(source) }
    }

    private fun endDrive(source: Int) {
        if (driveOwner != source) return
        driveOwner = SRC_NONE
        glView.queueEvent { game.deflectorEnd(source) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::motion.isInitialized) motion.padEvent()
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.eventTime - event.downTime < 450) {
                    // THE ECHO RULE, AND IT ONLY EVER RUNS THIS WAY ROUND — see [lastTouchTapMs].
                    // A key tap hard on the heels of a touch tap is the pad's service telling us
                    // about a press this app counted a moment ago. A key tap with no touch behind it
                    // is a real one: pad hardware that classifies without handing over the touches, a
                    // Bluetooth remote, a D-pad, `adb shell input keyevent 96`.
                    //
                    // There is deliberately NO DEVICE FILTER here, unlike the touch path. The pad's
                    // keycodes are injected by a RayNeo system service and nothing in this repo has
                    // ever established which device id they arrive under, so a filter written on a
                    // guess would be one more way to switch taps off for good. It is not needed:
                    // because suppression only runs touch-over-key, a stray key from anywhere can
                    // only ever ADD a tap, never take one away, and it expires by itself.
                    if (SystemClock.uptimeMillis() - lastTouchTapMs >= ECHO_MS) countTap(game.tapsAreUrgent)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) onBack(); return true }
            // The D-pad mirrors the pad for desk testing (DESIGN.md §10): DOWN held is the
            // deflector, UP is recall, LEFT / RIGHT hop, CENTER commits. Off the arena each stays a
            // single discrete step delivered on key-up, so the menu is unaffected.
            KeyEvent.KEYCODE_DPAD_UP -> { driveKey(event, -1, Swipe.UP); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { driveKey(event, 1, Swipe.DOWN); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_UP) onSwipe(turnDir(-1)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_UP) onSwipe(turnDir(1)); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun driveKey(event: KeyEvent, sign: Int, fallback: Swipe) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && game.holdDriveArmed) {
                startDrive(sign, SRC_KEY)
                // UP took its one shot inside startDrive and owns nothing, so the key-up below
                // must not deliver the swipe as well — the same double-fire the touch path had.
                if (sign < 0) keyOneShot = true
            }
            KeyEvent.ACTION_UP -> when {
                keyOneShot -> keyOneShot = false
                driveOwner == SRC_KEY -> endDrive(SRC_KEY)
                else -> onSwipe(fallback)
            }
        }
    }

    /** A D-pad UP that already fired recall on the key-down; its key-up is nothing. */
    private var keyOneShot = false

    /** Horizontal swipes are the HOP; the pad's raw dx sign maps straight onto the direction. */
    private fun turnDir(sign: Int): Swipe = if (sign < 0) Swipe.LEFT else Swipe.RIGHT

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        if (::motion.isInitialized) motion.padEvent()
        val thresh = max(48f, 0.09f * resources.displayMetrics.widthPixels)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downT = SystemClock.uptimeMillis()
                gestureAxis = 0; driveSign = 0; farDx = 0f; farDy = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                // HOW FAR THIS GESTURE GOT, remembered before anything else returns. The temple pad
                // is about a finger and a half long, so a swipe often runs out of pad and the finger
                // relaxes back toward where it started before it lifts. Classifying on the finger's
                // LAST position turns that into a tap — a shot nobody asked for in the arena, a menu
                // row actioned in the settings — so the classifier below is given the furthest point
                // instead. A gesture that crossed the threshold stays crossed.
                run { val mdx = ev.x - downX; val mdy = ev.y - downY
                      if (hypot(mdx, mdy) > hypot(farDx, farDy)) { farDx = mdx; farDy = mdy } }
                if (gestureAxis == AXIS_UP || gestureAxis == AXIS_DONE) return true   // settled: nothing to watch for
                if (gestureAxis == 0) {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (hypot(dx, dy) < thresh) return true
                    // Off the arena — title, game over, and above all the settings menu — the pad
                    // reverts to being read once, on finger-up. That is the whole guarantee that a
                    // held pad cannot walk the menu.
                    gestureAxis = if (!game.holdDriveArmed || abs(dx) >= abs(dy)) AXIS_UP else AXIS_DRIVE
                    if (gestureAxis == AXIS_UP) return true
                    driveSign = if (dy < 0) -1 else 1
                    driveExtremeY = ev.y
                    startDrive(driveSign, SRC_TOUCH)
                    // AN UPWARD CROSSING IS OVER THE MOMENT IT IS COUNTED. Recall has nothing to
                    // hold, so the gesture is FINISHED here — not merely latched — and the finger
                    // can wander home without the lift classifier finding a second verb in it.
                    if (driveSign < 0) gestureAxis = AXIS_DONE
                }
                // NO REVERSAL HYSTERESIS. The tank had two directions to change its mind between;
                // this game has a shield and a recall, and a finger wobbling at the end of a hold
                // must never turn a raised deflector into anything at all. Once the axis has
                // latched, the only thing left to watch for is the lift.
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureAxis == AXIS_DRIVE) { endDrive(SRC_TOUCH); gestureAxis = 0; return true }
                if (gestureAxis == AXIS_DONE) { gestureAxis = 0; return true }
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) return true
                // THE ORIGINAL CLASSIFIER, UNTOUCHED. It still runs for every horizontal gesture,
                // for everything off the arena, and — the case that matters — for a flick so fast
                // that the threshold was never crossed by an ACTION_MOVE at all, which is how a
                // batched or single-sample gesture still dashes instead of doing nothing.
                //
                // EVERY DIRECTION IT CAN EMIT HAS TO LAND ON A VERB, or this path quietly eats
                // gestures the crossing path would have honoured. [Swipe.DOWN] used to fall
                // through [Game.swipe]'s `else` and vanish, which made the deflector — the only
                // verb whose whole job is the panic flick — the one verb with no fallback; it
                // now reaches [Game.deflectorFlick]. Check that before adding a direction here.
                var dx = ev.x - downX; var dy = ev.y - downY
                if (hypot(farDx, farDy) > hypot(dx, dy)) { dx = farDx; dy = farDy }
                val dist = hypot(dx, dy)
                if (dist >= thresh) {
                    if (abs(dx) >= abs(dy)) onSwipe(turnDir(if (dx < 0) -1 else 1))
                    else onSwipe(if (dy < 0) Swipe.UP else Swipe.DOWN)
                } else if (SystemClock.uptimeMillis() - downT < 400) {
                    // THE TAP, AND IT IS NEVER SUPPRESSED HERE. Whatever the pad's own classifier
                    // has or has not been saying, this is a finger that went down and came up in
                    // under 400 ms without travelling, and on hardware that reports touch and
                    // nothing else it is the only evidence a tap happened that this app will ever
                    // get. There is no condition in front of it, by design — every guard that has
                    // ever stood here has been a way for the key stream to switch the touch stream
                    // off, and that is the bug, not the fix. Count it, stamp the clock the key path
                    // measures its echoes against, and let [resolveBurst] name the gesture.
                    lastTouchTapMs = SystemClock.uptimeMillis()
                    countTap(game.tapsAreUrgent)
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
        // HEAD TRACKING IS NOT A SETTING ANY MORE (DESIGN.md §11): the game cannot aim without
        // it, and a device that has no rotation vector puts HEAD TRACKING REQUIRED on the title
        // rather than offering a switch that turns the game off.
        head.start()
        motion.start()
        music.resume()
    }

    override fun onPause() {
        // A finger still on the pad when the window goes away never delivers its ACTION_UP.
        if (driveOwner != SRC_NONE) endDrive(driveOwner)
        gestureAxis = 0
        // ...and a burst still being counted when the window goes away must not land 300 ms later
        // on a paused engine, or worse, survive to fire into whatever the player comes back to.
        cancelBurst()
        head.stop()
        motion.stop()
        sfx.stopHum()
        music.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release(); voice.release(); hero.release(); music.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
