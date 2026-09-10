package com.x3knockout

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import com.x3knockout.engine.Fight
import com.x3knockout.engine.GameHost
import com.x3knockout.engine.Hand
import com.x3knockout.engine.Swipe
import com.x3knockout.gl.GLRenderer
import com.x3knockout.head.HeadTracker
import com.x3knockout.head.MotionTracker
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * X3Knockout — the hands are the temples, the head is the aim, and the body is the clock:
 *  - a TAP on the LEFT pad (`cyttsp6_mt`: a finger-lift inside 400 ms with under 115 px of travel)
 *    is the LEFT PUNCH; a tap on the RIGHT pad (`cyttsp5_mt`) is the RIGHT PUNCH. Head level is a
 *    head shot and head down is a body blow — the fight decides that; this file only ever says
 *    WHICH PAD LIFTED, and it says it from the touch stream alone (see THE LEFT PAD below)
 *  - BOTH pads inside [Fight.SPECIAL_MS] is THE SPECIAL when the KO meter is lit, else a 1-2. It
 *    is decided from the two per-pad TOUCH lift stamps and nothing else; keys never take part
 *  - SWIPE LEFT / RIGHT on the right pad is the STEP, one step per gesture, settled on finger-up
 *    (the prototype's `PAD` sidestep fallback is gone — DESIGN.md §1.2)
 *  - SWIPE DOWN on the right pad raises the GUARD on the crossing and drops it on the lift; a
 *    flick is just a short hold, and the 0.5 s pop is the FLOOR on the guard's life, not a ceiling
 *  - SWIPE UP on the right pad is a one-shot: the special with `LEFT PAD` OFF, else a soft click
 *  - DOUBLE-TAP on the right pad opens the settings, TRIPLE-TAP re-centres the head — and both
 *    are decided by COUNTING finger-lifts, not by the single KEYCODE_BACK this hardware injects
 *    for a double-tap. The pad's own verdict is corroboration, never the decision; see
 *    [lastTouchTapMs] for why.
 *  - the LEFT pad is TAP ONLY. Its swipes are the system's volume slider and are consumed here and
 *    ignored; its double-tap is a system MEDIA key, which this activity owns while it is in front
 *    and swallows (see [mediaSession]); it never counts toward a menu.
 *
 * THE LEFT PAD, AND WHY IT IS A ROUTE AND NOT A SECOND COPY OF THE RIGHT (DESIGN.md §1.6,
 * INPUT_LEFTPAD.md). Every earlier X3 app dropped `cyttsp6` on the first line of the touch
 * dispatcher as "the system volume pad". The owner measured it for this game: a single tap on the
 * left temple reaches the app and triggers nothing system-side; a double- or triple-tap is
 * classified by RayNeo's gesture service into a MEDIA key (play/pause, next); a slide is the volume.
 * So the left pad gets exactly one verb, the tap, and the dispatcher below is extended at these
 * seams and nowhere else:
 *
 *  1. The drop became [leftPad], a ROUTE with its own tiny gesture record ([lDownT], [lDownX],
 *     [lDownY], [lFar]). The right pad's `downX/downY/downT/farDx/farDy` are single fields, and a
 *     left ACTION_DOWN while a right finger is still on its pad would clobber the right gesture —
 *     the two temples are two input devices with two independent pointer streams, and a boxer
 *     has a hand on each. A left lift that was a tap goes STRAIGHT to [Fight.punch] on the GL
 *     thread, urgent, never through the burst; anything else on the left (a slide, a hold, a
 *     cancel) is consumed and ignored — no swipes, no burst, no drive. [MotionTracker.padEvent]
 *     is still stamped for every left event: a tap is a mechanical impulse on either temple.
 *  2. A left tap stamps [lastTouchTapMs] exactly as a right tap does. If the service injects a
 *     BUTTON_A for a left tap too (unknown until TEST.md L1), that key is the pad describing a
 *     press this app already counted, and without the stamp the echo rule would count it as a
 *     RIGHT tap 100–300 ms later — a phantom right punch after every left punch. A key with no
 *     touch behind it defaults to RIGHT (`KEY … echo=false hand=R`): a key carries no device name
 *     that could say otherwise, and a Bluetooth remote or `adb shell input keyevent 96` has to
 *     land somewhere.
 *  3. The special's pair is [Fight.lastLeftLiftMs] against [Fight.lastRightLiftMs], written HERE,
 *     from TOUCH lifts that were taps, and read on the other pad's next tap lift: the gap goes to
 *     [Fight.punch] as `pairMs`, and the fight decides whether that is a special or a 1-2. Never
 *     from a key, and never on a pad's own ACTION_DOWN — a down without a lift is how a volume
 *     slide begins. One measured exception: this OS cancels a pad's stream when the OTHER pad
 *     goes down and drops the lift that follows, so a two-handed slap has no lifts at all; a
 *     short, still gesture cut off BY THE OTHER PAD is therefore committed as the tap it was —
 *     see [cutLeft] for the measurement and the two tests that keep this inert with one pad.
 *  4. [onBack] tests "a finger has just gone down" against BOTH pads' down stamps, and a BACK that
 *     trails two left lifts inside [BURST_MS] is eaten on [lastLeftMultiMs] exactly as a BACK
 *     trailing a right double-tap is eaten on [lastMultiMs]; MEDIA keys are dropped outright,
 *     from the window and from the session both, because on this hardware they are what a left
 *     double-tap becomes and a jab-jab must not pause whatever the owner was listening to.
 *  5. `LEFT PAD` OFF (the fallback that ships in the same build, DESIGN.md §1.5) means the left
 *     pad is not trusted: its taps still stamp the echo clock (so any phantom key for them is
 *     still dropped) but throw nothing and pair with nothing.
 *  6. Every touch event on either pad logs one `PAD dev= id= src= act= x= y= dt=` line and every
 *     key logs one `KEY code= act= devId= dev= echo= hand=` line — nobody has ever logged the
 *     key's device on this hardware, and if the service turns out to use a distinct virtual
 *     device per pad, keys become attributable and rule 2's default is refined THEN, not before.
 *     The last of each is mirrored onto the lab plate ([Fight.lastPadText], [Fight.lastKeyText]).
 *
 * THE TWO VERTICAL VERBS, AND WHY THE RIGHT PAD IS READ IN TWO DIFFERENT WAYS.
 *
 * A vertical gesture is classified on the ACTION_MOVE that first crosses the threshold rather than
 * waiting for finger-up, because the guard has to be up the instant the finger commits to it and
 * not a gesture later. The axis is LATCHED at that first crossing: a gesture that crossed sideways
 * can never raise the guard no matter where the finger wanders afterwards, so a held horizontal is
 * one step and only one step.
 *
 * A DOWNWARD crossing calls [Fight.guardStart] and the finger-lift calls [Fight.guardEnd]; an
 * UPWARD crossing fires [Fight.swipeUp] once and ENDS the gesture there and then, because a
 * one-shot has nothing to hold and nothing to reverse. The tank's reversal hysteresis is therefore
 * gone: there is no verb in this game that a finger changing its mind mid-swipe should switch to.
 *
 * OFF THE ARENA THE OLD CLASSIFIER IS USED UNCHANGED — see [Fight.holdDriveArmed]. On the title and
 * in the settings the pad is read once, on finger-up, exactly as before, which is what keeps the
 * menu at one swipe per step however long the pad is held.
 *
 * The suite's standing rule is that a long-press belongs to the system (the X3 reserves a temple
 * hold for its quick-settings shade), and this is the documented exception to it: a 2.5 s injected
 * hold kept this activity focused with no shade and nothing in logcat, and the owner then held the
 * physical pad with a real finger and confirmed the same. Test B3 holds for 1 / 3 / 8 s and checks
 * it again with a guard on the glass.
 */
class MainActivity : Activity(), GameHost {

    private companion object {
        const val TAG = "X3Knockout"
        /** The gesture is settled the old way, once, on finger-up. */
        const val AXIS_UP = 1
        /** The gesture crossed vertically on the arena and is holding the guard until it lifts. */
        const val AXIS_DRIVE = 2
        /**
         * The gesture has ALREADY been acted on and its finger-up means nothing.
         *
         * This state exists because of a bug that reached the glasses: an upward crossing fired
         * the one-shot on the ACTION_MOVE and then, because the axis had been latched to
         * [AXIS_UP], the finger-lift classifier settled the very same swipe a second time and
         * fired it again — two verbs and two action quanta for one movement of one finger. A verb
         * that is committed on the crossing has to say so, and [AXIS_UP] cannot say it, because
         * [AXIS_UP] means the opposite: *nothing has happened yet, decide on the lift*.
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
         *
         * In a fight it is open continuously — taps every 200 ms never let it close — so EVERY
         * BUTTON_A during a flurry is dropped. That is correct (DESIGN.md §1.6 #7): the touch
         * stream carries every punch, and the only thing lost is a key-only remote this game does
         * not have.
         */
        const val ECHO_MS = 900L
        /**
         * HOW FAR APART TWO LIFTS ON OPPOSITE PADS CAN BE AND STILL BE CALLED A PAIR AT ALL. The
         * fight's own window is [Fight.SPECIAL_MS] (120 ms; the `PAIR ms=` p90 ships, TEST.md
         * L4); this wider one only decides what gets HANDED to the fight as `pairMs` and what
         * gets logged as `gap=` — a deliberate 1-2 sits in the hundreds of milliseconds and the
         * standing test needs to see that number next to the slaps', which is what the running
         * `p90=` on the `PAD` line is for. Beyond a second the other pad is simply an old event.
         */
        const val PAIR_LOG_MS = 1000L
        /**
         * HOW CLOSE THE OTHER PAD'S DOWN HAS TO FOLLOW A CANCEL TO HAVE CAUSED IT — see [cutLeft].
         * Measured at 1–3 ms on the glasses (the dispatcher cancels the live stream and starts
         * the new one in the same batch); 30 is generous and still a tenth of the fastest human
         * double action, so nothing a player does on purpose can land inside it by accident.
         */
        const val CUT_MS = 30L
        /** A stamp older than this is nobody's: the initial value of every uptime clock in here. */
        const val NEVER = -10_000L
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
    /** THE FIGHT — `Game` in x3discs. Kept under the old name so the arbitration below reads as it always has. */
    private lateinit var game: Fight
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    private val ui = Handler(Looper.getMainLooper())

    /**
     * THE MEDIA BUTTONS ARE OURS WHILE THE FIGHT IS IN FRONT (INPUT_LEFTPAD.md §2).
     *
     * On this hardware a double-tap on the left temple is classified by RayNeo's gesture service
     * into a MEDIA key, and `dumpsys media_session` on the owner's glasses shows where that key
     * goes when nobody claims it: `com.android.bluetooth/BluetoothMediaBrowserService` — the
     * phone's music, over AVRCP. A boxer who jabs twice pauses the owner's playlist. The system
     * hands media keys to the most recently playing app that holds an ACTIVE session, so this one
     * is made active and set PLAYING in [onResume] (the app does play — the music track and every
     * SoundPool click run under this uid) and dropped in [onPause], at which point the Bluetooth
     * session gets the buttons straight back. The callback swallows every key it is given: the
     * left pad's double-tap is not a verb in this game and there is nothing to forward it to.
     *
     * The window path is covered separately in [dispatchKeyEvent], because nothing in this repo
     * knows whether the service injects its media key as an input event to the focused window
     * (which arrives there first) or dispatches it through `MediaSessionManager` (which arrives
     * here). Both are logged with the same `KEY` line and a `via=` that says which.
     */
    private var mediaSession: MediaSession? = null

    /**
     * WHEN THE RAW TOUCH STREAM LAST REPORTED A FINGER LIFTING OFF AFTER A TAP — ON EITHER PAD.
     *
     * This one timestamp is the whole key/touch dedupe, and it stands where a latch used to stand
     * that switched the game's ears off twice. The shape of it comes down to two facts about this
     * pad, and they are worth writing out longhand because having them the wrong way round is what
     * shipped, both times:
     *
     *  1. THE TOUCH STREAM IS ALWAYS THERE AND IT IS ALWAYS FIRST. cyttsp5_mt hands this app the
     *     raw multitouch protocol for every gesture on the right temple (and cyttsp6_mt for the
     *     left). Nothing interprets it, nothing can decide to withhold it, and a finger-lift is
     *     reported when the finger lifts.
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
     *
     * The LEFT pad stamps it too (DESIGN.md §1.6 #2), and [lastTouchTapHand] remembers which pad
     * did, so the `KEY` line can say whose press a trailing key is describing. Neither is a
     * decision about the key's pad — a key has none — only about whether it is news.
     */
    private var lastTouchTapMs = NEVER
    /** `L` or `R`: the pad behind [lastTouchTapMs]. Telemetry only; it decides nothing. */
    private var lastTouchTapHand = "R"
    /**
     * THE BURST — the physical taps counted so far, from whichever source saw them. See [countTap].
     *
     * Nothing in this group outlives its gesture. [burstN] is zeroed the moment the burst resolves,
     * and the only two things that survive a finished gesture, [lastTouchTapMs] and [lastMultiMs],
     * are timestamps measured against a fixed window, so they expire on their own without anybody
     * having to remember to clear them. There is deliberately not one boolean in this class that
     * persists across gestures. A latch is a permanent policy built out of a single ambiguous
     * observation, and a temple pad with no key layout is the last place to go looking for one.
     *
     * THE LEFT PAD NEVER TOUCHES THE BURST. The burst is how the RIGHT pad's taps become a menu
     * verb; a left tap is a punch or nothing (DESIGN.md §1.7), so it goes past the counter
     * entirely, and two left taps can no more open the settings than two jabs can.
     */
    private var burstN = 0
    private var burstR: Runnable? = null
    /** Did a tap in this burst already reach the engine live? Then the burst must not send it again. */
    private var burstLive = false
    /**
     * Was a fight on (or your own count running) when the burst opened? Then every tap in it has
     * already gone live as a punch or a rise tap, and the burst resolves to NOTHING — a 1-2 must
     * never open the pause menu on its way past two (DESIGN.md §1.6 #3).
     */
    private var burstClamped = false
    /** When a double- or triple-tap was last committed — the credit a trailing BACK spends. */
    private var lastMultiMs = NEVER
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

    /**
     * THE LEFT TEMPLE'S GESTURE RECORD — the whole of it. Where the finger went down, when, and
     * the furthest it has strayed since. It is deliberately not a copy of the right pad's record
     * and deliberately not shared with it: the two pads are two input devices, each with its own
     * ACTION_DOWN … ACTION_UP stream, and a boxer keeps a hand on each. There is no axis latch,
     * no drive owner and no far-vector here because the left pad has no swipe verbs to latch — a
     * lift is a tap if it came quickly and stayed put, and it is nothing at all otherwise.
     *
     * [lDownT] starts and is reset to [NEVER] (never to "finger up" — a flag whose clearing event
     * is an ACTION_UP wedges the first time a window change eats one). A stale down can therefore
     * never make a tap: a lift is measured against it and a lift hundreds of seconds late fails
     * the 400 ms test by itself.
     */
    private var lDownX = 0f; private var lDownY = 0f; private var lDownT = NEVER
    private var lFar = 0f
    /** When the previous LEFT tap lifted, so two inside [BURST_MS] can be recognised as a pair … */
    private var lastLeftTapMs = NEVER
    /** … and when that last happened: the credit a trailing BACK or MEDIA key spends, see [onBack]. */
    private var lastLeftMultiMs = NEVER
    /**
     * THE CUT — per pad, what the dispatcher did to its last gesture, see [cutLeft] for the
     * measured behaviour this exists for. `…CutT` is when the pad's stream was last CANCELLED
     * (any gesture, any length); `…CutTapT` is set only when that cancelled gesture was short and
     * still — the stash that says "this was a tap, unless nothing on the other pad turns out to
     * have caused the cancel" — and it is spent or expires inside [CUT_MS]. The hold, travel and
     * device identity ride along only so the recovered tap's `PAD … act=CUT` line can be honest
     * about the gesture it stands for. Timestamps, all of them: nothing here is a latch.
     */
    private var lCutT = NEVER; private var lCutTapT = NEVER; private var lCutDt = 0L; private var lCutTravel = 0f
    private var rCutT = NEVER; private var rCutTapT = NEVER; private var rCutDt = 0L; private var rCutTravel = 0f
    /** Each pad's device name, id and source bits as of its last DOWN, for the synthetic `CUT` line. */
    private var lDev = "?"; private var lDevId = 0; private var lSrc = 0
    private var rDev = "?"; private var rDevId = 0; private var rSrc = 0
    /** The last 64 opposite-pad lift gaps, for the running `p90=` on the `PAD` line (TEST.md L4). */
    private val gapRing = IntArray(64)
    private var gapCount = 0

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
        game = Fight(store, this)
        game.motionSrc = motion
        game.applyStepSense()
        voice.onLineStart = { id -> refreshDuck(); glView.queueEvent { game.onVoiceLineStart(id) } }
        voice.onLineEnd = { id -> refreshDuck(); glView.queueEvent { game.onVoiceLineEnd(id) } }
        // The pilot's caption is raised on the beat its clip actually starts — see Game.onHeroLineStart.
        hero.onLineStart = { id -> refreshDuck(); glView.queueEvent { game.onHeroLineStart(id) } }
        hero.onLineEnd = { id -> refreshDuck(); glView.queueEvent { game.onHeroLineEnd(id) } }
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
        openMediaSession()
        applyVolume(store.volume)
        music.enabled = store.music
        voice.enabled = store.voice; hero.enabled = store.voice
        // DEBUGGABLE BUILDS ONLY (TEST.md §1): `am start … --ei round N --ef floor F --ei hp H
        // --es drill peck_l|peck_r|wing_r|wing_l|sunrise|all --es script counter` drops the owner
        // straight into the round a test block is about. It NEVER writes records — a score set
        // against a boxer on his last legs is not a score — so the store is told before the fight
        // boots. There is deliberately no key for the LEFT pad: a key can never name a pad.
        val dbg = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val rnd = if (dbg) (intent?.getIntExtra("round", 0) ?: 0) else 0
        val fl = if (dbg) (intent?.getFloatExtra("floor", -1f) ?: -1f) else -1f
        val hp = if (dbg) (intent?.getIntExtra("hp", 0) ?: 0) else 0
        val drill = if (dbg) intent?.getStringExtra("drill") else null
        val script = if (dbg) intent?.getStringExtra("script") else null
        // `--ei bout N` (1-based) puts a specific man on the card. It counts as a harness: walking
        // straight into the champion is not a record, and the store must be told before boot.
        val bout = if (dbg) (intent?.getIntExtra("bout", 0) ?: 0) else 0
        val harness = rnd > 0 || fl >= 0f || hp > 0 || drill != null || script != null || bout > 0
        if (harness) store.recordsEnabled = false
        if (bout > 0) game.setBout(bout - 1)
        game.boot()
        music.play()
        if (harness) glView.queueEvent { game.debugStart(rnd.coerceAtLeast(1), fl, hp, drill, script) }
    }

    // ------------------------------------------------------------ GameHost (any thread)

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun crowd(level: Float, rate: Float) = sfx.crowd(level, rate)
    override fun say(id: String, urgent: Boolean, patienceMs: Long) = voice.say(id, urgent, patienceMs)
    override fun sayAll(ids: List<String>) = voice.sayAll(ids)
    override fun stopVoice() { voice.stop(); refreshDuck() }
    override fun hero(id: String, urgent: Boolean, patienceMs: Long) = hero.say(id, urgent, patienceMs)
    override fun stopHero() { hero.stop(); refreshDuck() }
    override fun musicEnabled(on: Boolean) { music.enabled = on }
    /**
     * THE CABINET'S CUTS (DESIGN.md §9.5). The fight names the track for the state it is in —
     * `Music.TITLE` on the attract, `FIGHT` / `FIGHT3` from the round card, `COUNT` while somebody
     * is on the canvas, `WIN` on the knockout — and [Music.play] cuts to it: no crossfade (a fade
     * between two brass charts is mud; the cut is the punctuation) and a no-op on the track already
     * playing, so a state may re-assert its track every frame. Posted to the music thread inside
     * [Music.play]; the fight calls this from the GL thread and never waits on it. This is the one
     * line `GameHost.music`'s default body held a place for while this file was being extended.
     */
    override fun music(track: String) = music.play(track)
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
     * ONE PHYSICAL TAP ON THE RIGHT PAD. Both sources end up here, because what a gesture MEANS is
     * decided by how many taps arrived and never by which wire carried them: one is a punch or a
     * menu row, two is the settings menu, three re-centres the head. Counting is the only
     * arrangement that survives all three of the hardware stories this file has to live with — a
     * pad that classifies AND reports touch, a pad that only reports touch, and a plain Bluetooth
     * remote that only sends keys — because it asks each source for the one thing every source can
     * actually supply.
     *
     * ON THE ARENA THE TAP IS COMMITTED THE INSTANT IT IS COUNTED, and the burst goes on counting
     * underneath it. This is the one asymmetry in the file and it is on purpose. A punch that
     * lands 320 ms after you asked for it is not the punch you asked for, and getting up off the
     * canvas is a mashing contest against a count that does not wait. The price is that a
     * double-tap taken in the arena fires one punch on its way into the pause menu — and DESIGN.md
     * §1.6 #3 then takes the menu away too, see [burstClamped]. That is the right way round: a
     * stray punch is one visible thing the player watches happen, and a glove with a beat of lag is
     * a game that feels broken everywhere, all the time.
     *
     * OFF THE ARENA NOTHING IS COMMITTED UNTIL THE BURST SETTLES. There is no punch to miss on the
     * title, on the game-over card or in the settings, and an eager tap there is exactly how a
     * double-tap used to action the highlighted row on its way to closing the menu.
     *
     * [pairMs] is the other pad's lift, measured by the TOUCH path and only ever ≥ 0 from there:
     * the key path has no pad to pair with and passes the default.
     */
    private fun countTap(urgent: Boolean, pairMs: Long = -1L) {
        burstN++
        // DESIGN.md §1.6 #3: in a fight every burst resolves to nothing — its taps have already
        // gone live as punches, and a 1-2 must never open the pause menu.
        // ...AND IT IS RE-READ ON EVERY TAP, not latched on the first. A burst that OPENS off the
        // arena and CLOSES inside it — a right tap on the round card, the bell, then a second tap
        // now in the fight — was resolving as a MENU verb, because the clamp had been decided
        // 300 ms earlier when there was no fight to protect. The state that matters is the state
        // the burst RESOLVES in, and any tap that was live is enough to disqualify the burst.
        if (game.inFight) burstClamped = true
        if (urgent) { burstLive = true; glView.queueEvent { game.punch(Hand.RIGHT, pairMs) } }
        burstR?.let { ui.removeCallbacks(it) }
        val r = Runnable { resolveBurst() }
        burstR = r
        ui.postDelayed(r, BURST_MS)
    }

    /**
     * The burst is over; say what the taps in it added up to.
     *
     * FOUR OR MORE IS NOT A GESTURE, IT IS A PLAYER MASHING, and it resolves to nothing at all.
     * That is what keeps the settings menu out of a fight even without the clamp: rising from your
     * own knockdown is [Fight.RISE_TAPS] alternated taps against a count, so anybody actually
     * getting up runs the count well past three, and without this rule the tail of every
     * successful rise would pause the game.
     *
     * CLAMPED IS THE SAME RULE HELD HARDER. While a fight is on there is no double-tap and no
     * triple-tap on offer: the taps have already gone to the engine live, every one of them is a
     * punch or a rise tap, and a burst that happens to stop on two must not open the pause menu
     * on top of a 1-2 the player is in the middle of throwing. It is read once, when the burst
     * opens, so a bell mid-burst cannot turn the last two punches into a menu toggle either.
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
     * touch stream at all. Nothing else is going to toggle the menu for those, so this does — and
     * in a fight it is the one way into the menu that is not the corner (DESIGN.md §1.6 #3).
     *
     * THE LEFT PAD IS UNDER IT TOO (DESIGN.md §1.6 #4). "A finger has just gone down" is tested
     * against the later of the two pads' down stamps, and two left lifts inside [BURST_MS] leave
     * the same [ECHO_MS] credit on [lastLeftMultiMs] that a right double-tap leaves on
     * [lastMultiMs]: should the service describe a left double-tap with a BACK rather than the
     * media key the owner measured, that BACK is the pad talking about lifts this app has already
     * turned into two jabs, and a jab-jab must not pause the game.
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
        if (burstR != null || now - max(downT, lDownT) < 400) return
        // Eaten: the burst that describes this BACK has already committed. The pad can trail its own
        // verdict past the end of the burst window, and without this a slow BACK toggles the menu
        // straight back shut behind the double-tap that just opened it.
        if (now - lastMultiMs < ECHO_MS || now - lastLeftMultiMs < ECHO_MS) return
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
     * paths, the second cannot raise a second guard or steal the release.
     *
     * UP is a one-shot and does not become an owner: [Fight.swipeUp] fires here and the gesture is
     * over. DOWN takes ownership, because the guard stays up until the finger lifts.
     */
    private fun startDrive(sign: Int, source: Int) {
        if (driveOwner != SRC_NONE && driveOwner != source) return
        if (sign < 0) { glView.queueEvent { game.swipeUp() }; return }
        driveOwner = source
        glView.queueEvent { game.guardStart(source) }
    }

    private fun endDrive(source: Int) {
        if (driveOwner != source) return
        driveOwner = SRC_NONE
        glView.queueEvent { game.guardEnd(source) }
    }

    /** The keys that are a TAP by any name: the pad's injected BUTTON_A, the D-pad's centre, a keyboard's enter and space. */
    private fun isTapKey(code: Int) = code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE

    /**
     * The keys a media session would be handed: what the left temple's double- and triple-tap
     * become on this hardware (INPUT_LEFTPAD.md). Spelled out rather than `KeyEvent.isMediaSessionKey`
     * because that helper is API 31 and this app's floor is 29 — and because the list IS the
     * decision. `KEYCODE_VOLUME_*` is deliberately NOT here: a left slide is the system's volume
     * and this activity passes those keys through to the system untouched until TEST.md L2 says
     * what a slide actually does with the app consuming the touches. If it changes the volume
     * through THIS window, eating VOLUME keys here is the fix; if it changes it behind the window,
     * no key filter can help and the title's warning is the answer.
     */
    private fun isMediaKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_HEADSETHOOK -> true
        else -> false
    }

    /**
     * IS THIS KEY NEWS, OR THE PAD DESCRIBING TOUCHES ALREADY COUNTED? The same three rules the
     * dispatcher acts on, gathered in one place so the `KEY` line and the action can never
     * disagree: a tap key is an echo inside [ECHO_MS] of a touch tap on either pad; a BACK is an
     * echo while a burst is open, inside 400 ms of a down on either pad, or inside [ECHO_MS] of a
     * committed multi-tap on either pad; a media key is an echo of any recent touch tap or left
     * pair (it is dropped regardless — the flag only says WHY it arrived). Everything else is news.
     */
    private fun keyIsEcho(code: Int, now: Long): Boolean = when {
        isTapKey(code) -> now - lastTouchTapMs < ECHO_MS
        code == KeyEvent.KEYCODE_BACK -> burstR != null || now - max(downT, lDownT) < 400 ||
            now - lastMultiMs < ECHO_MS || now - lastLeftMultiMs < ECHO_MS
        isMediaKey(code) -> now - lastTouchTapMs < ECHO_MS || now - lastLeftMultiMs < ECHO_MS
        else -> false
    }

    /**
     * ONE `KEY` LINE PER KEY EVENT (DESIGN.md §13), from whichever door it came in by. `devId=` and
     * `dev=` are the two things nobody has ever logged on this hardware: if the RayNeo service
     * injects its keys under a distinct virtual device per pad, keys become attributable and the
     * `hand=R` default below gets refined THEN. Until then `hand=` is `R` for a key that will be
     * counted (a tap key with no touch behind it — `KEY→R`, DESIGN.md §1.6 #2), the echoed pad's
     * letter for an echo, and `-` for a key that is neither (a BACK, a dropped media key).
     * [via] is `SESSION` for a key handed over by [mediaSession] rather than the window.
     */
    private fun logKey(e: KeyEvent, echo: Boolean, hand: String, via: String?) {
        val act = when (e.action) { KeyEvent.ACTION_DOWN -> "DOWN"; KeyEvent.ACTION_UP -> "UP"; else -> "A${e.action}" }
        val dev = e.device?.name ?: "?"
        Log.i(TAG, "KEY code=${e.keyCode} act=$act devId=${e.deviceId} dev=$dev echo=$echo hand=$hand" + (if (via != null) " via=$via" else ""))
        // The plate's copy uses StrokeFont's glyphs only: capitals, digits, space and the dash.
        game.lastKeyText = "KEY ${e.keyCode} $act " + when { echo -> "ECHO $hand"; via != null -> via; else -> hand }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::motion.isInitialized) motion.padEvent()
        // THE TELEMETRY FIRST (DESIGN.md §1.6 #8): every key, its device, and the verdict the rules
        // below are about to reach — computed once here so the line can never contradict the act.
        val now = SystemClock.uptimeMillis()
        val echo = keyIsEcho(event.keyCode, now)
        val counted = !echo && isTapKey(event.keyCode) && event.action == KeyEvent.ACTION_UP && !event.isCanceled &&
            event.eventTime - event.downTime < 450
        logKey(event, echo, when { echo -> lastTouchTapHand; counted -> "R"; else -> "-" }, null)
        // A MEDIA KEY IS DROPPED HERE, ALWAYS. On this hardware it is what a left double-tap becomes
        // (INPUT_LEFTPAD.md §2), it is at most corroboration of two left lifts this app already
        // turned into punches, and returning true is what stops the window from forwarding it to
        // whichever media session would otherwise pause the owner's music. It is never a count:
        // the media key for a double-tap cannot arrive before the second lift, and the second lift
        // is already a punch.
        if (isMediaKey(event.keyCode)) return true
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
                    //
                    // And it is a RIGHT tap, always (`KEY→R`): a key carries no device name, so no
                    // key can ever say it came from the left temple. The left pad's punches come
                    // from its touch stream and nowhere else.
                    if (SystemClock.uptimeMillis() - lastTouchTapMs >= ECHO_MS) countTap(game.tapsAreUrgent)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) onBack(); return true }
            // The D-pad mirrors the RIGHT pad for desk testing (DESIGN.md §1.7): DOWN held is the
            // guard, UP is the swipe-up verb, LEFT / RIGHT step, CENTER is a right punch. Off the
            // arena each stays a single discrete step delivered on key-up, so the menu is
            // unaffected. There is no key for the left pad by design.
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

    /** A D-pad UP that already fired the one-shot on the key-down; its key-up is nothing. */
    private var keyOneShot = false

    /** Horizontal swipes are the STEP; the pad's raw dx sign maps straight onto the direction. */
    private fun turnDir(sign: Int): Swipe = if (sign < 0) Swipe.LEFT else Swipe.RIGHT

    /**
     * Milliseconds since the OTHER pad's last tap lift, or -1 when there is no pair to speak of:
     * the `pairMs` [Fight.punch] is handed and the `gap=` the `PAD` line prints. The fight applies
     * its own [Fight.SPECIAL_MS]; this only refuses to call a second-old lift a partner.
     */
    private fun gapSince(otherLiftMs: Long, now: Long): Long {
        val g = now - otherLiftMs
        return if (g in 0..PAIR_LOG_MS) g else -1L
    }

    /** Feed one opposite-pad gap into the ring and return the running p90 — the number TEST.md L4 reads. */
    private fun gapP90(gap: Long): Int {
        gapRing[gapCount % gapRing.size] = gap.toInt(); gapCount++
        val n = min(gapCount, gapRing.size)
        val sorted = gapRing.copyOf(n); sorted.sort()
        return sorted[((n - 1) * 0.9f).toInt()]
    }

    /**
     * ONE `PAD` LINE PER TOUCH EVENT ON EITHER PAD (DESIGN.md §13; TEST.md L1–L3 are read on it).
     * `dev=` is the kernel name the InputReader attached — `cyttsp6_mt` left, `cyttsp5_mt` right,
     * `?` for an injected event with no device — and `id=` / `src=` are the InputDevice id and the
     * source bits, so a pad that ever shows up under a different name or path is caught by the
     * log rather than by silence. `dt=` is milliseconds since that pad's own down. A lift adds
     * `travel=` and `tap=` (the verdict the classifier reaches for it), and a tap that pairs with
     * the other pad adds `gap=` and the running `p90=`.
     */
    private fun logPad(ev: MotionEvent, dev: String, hand: String, dt: Long, travel: Float, tap: Boolean?, gap: Long, note: String = "") {
        val act = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"; MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"; MotionEvent.ACTION_CANCEL -> "CANCEL"; else -> "A${ev.actionMasked}"
        }
        logPadLine(dev, ev.deviceId, ev.source, act, ev.x.toInt(), ev.y.toInt(), hand, dt, travel, tap, gap, note)
    }

    /** The line itself; `act=CUT` is the one act with no MotionEvent behind it — a tap recovered from a cancel, see [cutLeft]. */
    private fun logPadLine(dev: String, id: Int, src: Int, act: String, x: Int, y: Int, hand: String, dt: Long, travel: Float, tap: Boolean?, gap: Long, note: String) {
        val sb = StringBuilder(112)
        sb.append("PAD dev=").append(dev).append(" id=").append(id).append(" src=0x").append(Integer.toHexString(src))
            .append(" act=").append(act).append(" x=").append(x).append(" y=").append(y).append(" dt=").append(dt)
        if (tap != null) sb.append(" travel=").append(travel.toInt()).append(" tap=").append(tap)
        if (gap >= 0) sb.append(" gap=").append(gap).append(" p90=").append(gapP90(gap))
        if (note.isNotEmpty()) sb.append(' ').append(note)
        Log.i(TAG, sb.toString())
        // The plate's copy: `L TAP 73MS`, `R CUT 36MS GAP 38`, `R UP 410MS 132PX`, `L MOVE 40PX`,
        // `R DOWN` — StrokeFont's glyphs only.
        game.lastPadText = when {
            tap == true -> "$hand ${if (act == "CUT") "CUT" else "TAP"} ${dt}MS" + (if (gap >= 0) " GAP $gap" else "")
            tap == false -> "$hand UP ${dt}MS ${travel.toInt()}PX"
            act == "MOVE" -> "$hand MOVE ${travel.toInt()}PX"
            else -> "$hand $act"
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // THE ROUTE, WHERE THE DROP USED TO BE. The left temple's device name is the ONLY thing in
        // this app that can say "left", and it says it here, once, at the top.
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return leftPad(ev)
        if (::motion.isInitialized) motion.padEvent()
        val thresh = max(48f, 0.09f * resources.displayMetrics.widthPixels)
        // THE TELEMETRY FIRST (DESIGN.md §1.6 #8), with a read-only mirror of the verdict the
        // classifier below will reach for a lift, so the line can say `tap=` and `gap=` before the
        // tap is acted on. The mirror decides nothing: the classifier still decides, on the same
        // numbers, and [rPair] is only ever handed on when both agree that this was a tap.
        val now = SystemClock.uptimeMillis()
        var rPair = -1L
        var rTravel = 0f
        run {
            val cur = hypot(ev.x - downX, ev.y - downY)
            val isUp = ev.actionMasked == MotionEvent.ACTION_UP
            val isLift = isUp || ev.actionMasked == MotionEvent.ACTION_CANCEL
            rTravel = if (isLift) max(cur, hypot(farDx, farDy)) else cur
            val tap = if (isUp) gestureAxis == 0 && rTravel < thresh && now - downT < 400 else null
            if (tap == true) rPair = gapSince(game.lastLeftLiftMs, now)
            logPad(ev, ev.device?.name ?: "?", "R", if (ev.actionMasked == MotionEvent.ACTION_DOWN) 0L else now - downT, rTravel, tap, rPair)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downT = SystemClock.uptimeMillis()
                gestureAxis = 0; driveSign = 0; farDx = 0f; farDy = 0f
                rDev = ev.device?.name ?: "?"; rDevId = ev.deviceId; rSrc = ev.source
                // THE CUT, from this side (see [cutLeft]): a short left gesture the dispatcher
                // cancelled a moment ago was cut by THIS finger, and it was the tap it looked like.
                if (now - lCutTapT < CUT_MS) commitLeftCut(now, lCutDt, lCutTravel, "cut=DOWN")
            }
            MotionEvent.ACTION_MOVE -> {
                // HOW FAR THIS GESTURE GOT, remembered before anything else returns. The temple pad
                // is about a finger and a half long, so a swipe often runs out of pad and the finger
                // relaxes back toward where it started before it lifts. Classifying on the finger's
                // LAST position turns that into a tap — a punch nobody asked for in the arena, a
                // menu row actioned in the settings — so the classifier below is given the furthest
                // point instead. A gesture that crossed the threshold stays crossed.
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
                    // AN UPWARD CROSSING IS OVER THE MOMENT IT IS COUNTED. The one-shot has nothing
                    // to hold, so the gesture is FINISHED here — not merely latched — and the finger
                    // can wander home without the lift classifier finding a second verb in it.
                    if (driveSign < 0) gestureAxis = AXIS_DONE
                }
                // NO REVERSAL HYSTERESIS. The tank had two directions to change its mind between;
                // this game has a guard and a one-shot, and a finger wobbling at the end of a hold
                // must never turn a raised guard into anything at all. Once the axis has latched,
                // the only thing left to watch for is the lift.
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureAxis == AXIS_DRIVE) { endDrive(SRC_TOUCH); gestureAxis = 0; return true }
                if (gestureAxis == AXIS_DONE) { gestureAxis = 0; return true }
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) { cutRight(now, rTravel, thresh); return true }
                // THE ORIGINAL CLASSIFIER, UNTOUCHED. It still runs for every horizontal gesture,
                // for everything off the arena, and — the case that matters — for a flick so fast
                // that the threshold was never crossed by an ACTION_MOVE at all, which is how a
                // batched or single-sample gesture still steps instead of doing nothing.
                //
                // EVERY DIRECTION IT CAN EMIT HAS TO LAND ON A VERB, or this path quietly eats
                // gestures the crossing path would have honoured. [Swipe.DOWN] used to fall
                // through the game's `else` and vanish, which made the guard — the only verb whose
                // whole job is the panic flick — the one verb with no fallback; it now reaches
                // [Fight.guardFlick]. Check that before adding a direction here.
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
                    rightTap(SystemClock.uptimeMillis(), rPair)
                }
            }
        }
        return true
    }

    /**
     * THE LEFT TEMPLE (`cyttsp6_mt`), read for exactly one verb. A lift that comes inside
     * [Fight.TAP_MAX_MS] of its down with under [Fight.TAP_TRAVEL_PX] of travel (the same
     * `thresh` the right pad's classifier uses, 115 px on this surface) is a LEFT PUNCH and goes
     * to [Fight.punch] on the GL thread THE INSTANT THE FINGER LIFTS — urgent, like a right tap on
     * the arena, and never through the burst, because a left tap is never a menu verb (DESIGN.md
     * §1.7: on every card and in every menu the left pad is silent, and [Fight.punch] ignores it
     * there). Every other left event — a slide, a hold, a cancel, a lift that travelled — is
     * consumed and ignored: no swipe verbs, no drive, no count. The slide is the system's volume
     * and whether consuming its touches here stops that is TEST.md L2's question, not this file's.
     *
     * THE PAIR (DESIGN.md §1.4, §1.6 #5): the gap to the RIGHT pad's last tap lift is read before
     * this lift is stamped, and handed to the fight as `pairMs`; inside [Fight.SPECIAL_MS] with
     * the meter lit the fight upgrades the punch already in flight, otherwise it is a 1-2. The
     * punch is never held back to wait for a partner.
     *
     * `LEFT PAD` OFF (DESIGN.md §1.5): the tap still stamps [lastTouchTapMs] — a phantom key the
     * service injects for it is still an echo and still dropped, which is the very failure mode
     * OFF exists for — but it throws nothing and it stamps no pair.
     */
    private fun leftPad(ev: MotionEvent): Boolean {
        if (::motion.isInitialized) motion.padEvent()
        val now = SystemClock.uptimeMillis()
        val thresh = max(48f, 0.09f * resources.displayMetrics.widthPixels)
        val cur = hypot(ev.x - lDownX, ev.y - lDownY)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lDownX = ev.x; lDownY = ev.y; lDownT = now; lFar = 0f
                lDev = ev.device?.name ?: "?"; lDevId = ev.deviceId; lSrc = ev.source
                logPad(ev, lDev, "L", 0L, 0f, null, -1L)
                // THE CUT (see [cutLeft]): a short right gesture the dispatcher cancelled a moment
                // ago was cut by THIS finger, and it was the tap it looked like.
                if (now - rCutTapT < CUT_MS) commitRightCut(now, rCutDt, rCutTravel, "cut=DOWN")
            }
            MotionEvent.ACTION_MOVE -> {
                if (cur > lFar) lFar = cur
                logPad(ev, lDev, "L", now - lDownT, cur, null, -1L)
            }
            MotionEvent.ACTION_UP -> {
                val travel = max(lFar, cur)
                val hold = now - lDownT
                if (travel < thresh && hold < Fight.TAP_MAX_MS) leftTap(now, "UP", hold, travel, "")
                else logPad(ev, lDev, "L", hold, travel, false, -1L)
                lDownT = NEVER
            }
            MotionEvent.ACTION_CANCEL -> {
                val travel = max(lFar, cur)
                logPad(ev, lDev, "L", now - lDownT, travel, null, -1L)
                cutLeft(now, travel, thresh)
                lDownT = NEVER
            }
        }
        return true
    }

    /**
     * THE COMMIT OF A LEFT TAP — from its lift ([act] `UP`) or recovered from a cut ([act] `CUT`,
     * see [cutLeft]). Stamps the echo clock and the left multi credit; reads the gap to the RIGHT
     * stamp BEFORE writing the LEFT one, so a lift can never pair with itself; sends the punch to
     * the GL thread — or, with `LEFT PAD` OFF, does everything but the pair and the punch.
     */
    private fun leftTap(now: Long, act: String, hold: Long, travel: Float, note: String) {
        lastTouchTapMs = now
        lastTouchTapHand = "L"
        if (now - lastLeftTapMs < BURST_MS) lastLeftMultiMs = now
        lastLeftTapMs = now
        var pair = -1L
        var n = note
        if (game.leftPadEnabled) {
            pair = gapSince(game.lastRightLiftMs, now)
            game.lastLeftLiftMs = now
            glView.queueEvent { game.punch(Hand.LEFT, pair) }
        } else n = (n + " leftpad=OFF").trim()
        logPadLine(lDev, lDevId, lSrc, act, lDownX.toInt(), lDownY.toInt(), "L", hold, travel, true, pair, n)
    }

    /**
     * THE COMMIT OF A RIGHT TAP: the echo clock, then THE RIGHT LIFT STAMP (DESIGN.md §1.4 — the
     * other half of the special's pair, written after the gap to the LEFT stamp was read, so a
     * lift can never pair with itself; a touch lift, and only a touch lift, ever writes it), then
     * the count. From the classifier's lift or recovered from a cut — [countTap] does not care.
     */
    private fun rightTap(now: Long, pair: Long) {
        lastTouchTapMs = now
        lastTouchTapHand = "R"
        game.lastRightLiftMs = now
        countTap(game.tapsAreUrgent, pair)
    }

    /**
     * THE CUT — TWO PADS ON ONE DISPATCHER, measured on the glasses (Android 12), 2026-09-09.
     *
     * Android's InputDispatcher keeps ONE live touch stream per display. When a second input
     * device goes down while a first is down, the first device's gesture is CANCELLED (this app
     * sees ACTION_CANCEL) and the second becomes the live stream; the first device's lift, when
     * it comes, is DROPPED — and if the second device is still down at that moment, the second
     * stream is cancelled too. Two uhid touch screens registered under the pads' own names showed
     * every ordering (`PAD` lines, 40 ms apart in the script):
     *
     *   L↓ R↓ R↑ L↑    →  L DOWN, L CANCEL, R DOWN, R UP        (R is a tap; L's lift is gone)
     *   L↓ R↓ L↑ R↑    →  L DOWN, L CANCEL, R DOWN, R CANCEL    (nothing lifts at all)
     *   L↓ R↓ same ms  →  R DOWN, R CANCEL, L DOWN, L CANCEL    (nothing lifts at all)
     *
     * The last two rows are the two-handed slap — THE SPECIAL'S OWN GESTURE (DESIGN.md §1.4) —
     * and as delivered it is no punch at all. Rule 3 of THE LEFT PAD says the pair is decided
     * from two touch LIFTS; on this OS a slap has no lifts, so this is the one place a cancel is
     * read for what it was:
     *
     * A SHORT, STILL GESTURE THAT WAS CANCELLED COUNTS AS THE TAP IT WAS ABOUT TO BE, IF AND
     * ONLY IF THE OTHER PAD IS THE REASON. The dispatcher gives the other pad exactly two ways to
     * be the reason:
     *  (i)  its DOWN arrives inside [CUT_MS] of the cancel — the cancel and the new DOWN are one
     *       batch (1–3 ms apart, measured). The cancelled gesture is stashed ([lCutTapT] /
     *       [rCutTapT]) and committed BY the other pad's DOWN, logged `act=CUT cut=DOWN`;
     *  (ii) its own stream was cut at or after OUR down (`…CutT ≥ ourDownT − CUT_MS`): our down
     *       cancelled it, so its lift — which the dispatcher drops before we ever see it — is the
     *       only thing that could have cancelled us. Committed on the spot, `cut=LIFT`.
     * Anything else that cancels a gesture — the window going away, the system taking the touch
     * — has no other-pad event beside it, and the stash expires by itself inside 30 ms. A long or
     * travelled gesture is never a tap however it ended, and a `LEFT PAD` OFF left tap is still
     * recovered and still throws nothing.
     *
     * WHY THIS CANNOT DISTURB THE RIGHT PAD'S BATTLE-TESTED ARBITRATION: both tests need the OTHER
     * pad, and until this game the other pad was dropped on the first line of the touch
     * dispatcher. With one pad in play neither condition can ever be true, and a cancel is what
     * it always was here — nothing. A recovered tap goes through exactly the commit a lift goes
     * through ([leftTap] / [rightTap]); the only new thing is the door it comes in by, and the
     * `PAD … act=CUT` line that says so. The hazard is a system cancel of a short press that
     * happens to sit inside 30 ms of the other pad's down, or a short press cut by the system
     * while the other pad was cut by our own down — one visible punch, once in a blue moon.
     */
    private fun cutLeft(now: Long, travel: Float, thresh: Float) {
        val hold = now - lDownT
        lCutT = now
        if (!(travel < thresh && hold < Fight.TAP_MAX_MS)) return
        if (rCutT >= lDownT - CUT_MS) leftTap(now, "CUT", hold, travel, "cut=LIFT")
        else { lCutTapT = now; lCutDt = hold; lCutTravel = travel }
    }

    private fun cutRight(now: Long, travel: Float, thresh: Float) {
        val hold = now - downT
        rCutT = now
        if (!(travel < thresh && hold < Fight.TAP_MAX_MS)) return
        if (lCutT >= downT - CUT_MS) commitRightCut(now, hold, travel, "cut=LIFT")
        else { rCutTapT = now; rCutDt = hold; rCutTravel = travel }
    }

    /** A cancelled left gesture that the other pad has just shown to be a tap; the stash is spent. */
    private fun commitLeftCut(now: Long, hold: Long, travel: Float, note: String) {
        lCutTapT = NEVER
        leftTap(now, "CUT", hold, travel, note)
    }

    private fun commitRightCut(now: Long, hold: Long, travel: Float, note: String) {
        rCutTapT = NEVER
        val pair = gapSince(game.lastLeftLiftMs, now)
        rightTap(now, pair)
        logPadLine(rDev, rDevId, rSrc, "CUT", downX.toInt(), downY.toInt(), "R", hold, travel, true, pair, note)
    }

    /**
     * Insurance for the standing test, not a verb: if either temple ever hands the app its events
     * as GENERIC motion (a hover, a stylus-class source) instead of touches, the `PAD` log says so
     * with `act=GENERIC` rather than the pad silently doing nothing. Nothing is consumed here.
     */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val dev = ev.device?.name
        if (dev != null && dev.contains("cyttsp", ignoreCase = true)) {
            Log.i(TAG, "PAD dev=$dev id=${ev.deviceId} src=0x${Integer.toHexString(ev.source)} act=GENERIC a=${ev.actionMasked} x=${ev.x.toInt()} y=${ev.y.toInt()}")
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ------------------------------------------------------------ the media session

    /** See [mediaSession]. Created once; claimed and released with the window's foreground life. */
    private fun openMediaSession() {
        val s = MediaSession(this, "x3knockout")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ke = keyOf(mediaButtonIntent)
                if (ke != null) {
                    val echo = keyIsEcho(ke.keyCode, SystemClock.uptimeMillis())
                    logKey(ke, echo, if (echo) lastTouchTapHand else "-", "SESSION")
                }
                // Swallowed, whatever it was. Returning true is what keeps the default callback from
                // turning it into onPlay/onPause, and what keeps it from going anywhere else.
                return true
            }
        })
        mediaSession = s
    }

    /** The KeyEvent inside a media-button intent; the un-typed getter is all API 29–32 has. */
    @Suppress("DEPRECATION")
    private fun keyOf(i: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else i.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    private fun claimMediaButtons(on: Boolean) {
        val s = mediaSession ?: return
        runCatching {
            val state = if (on) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (on) 1f else 0f).build())
            s.isActive = on
        }.onFailure { Log.w(TAG, "media session", it) }
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
        claimMediaButtons(true)
    }

    override fun onPause() {
        // A finger still on the pad when the window goes away never delivers its ACTION_UP.
        if (driveOwner != SRC_NONE) endDrive(driveOwner)
        gestureAxis = 0
        // ...and neither does a finger still on the LEFT pad: its record goes with the window,
        // and so does any cut still waiting for the other pad to explain it.
        lDownT = NEVER; lFar = 0f; lCutTapT = NEVER; rCutTapT = NEVER
        // ...and a burst still being counted when the window goes away must not land 300 ms later
        // on a paused engine, or worse, survive to fire into whatever the player comes back to.
        cancelBurst()
        // The media buttons go back to whoever had them: the phone's music, over Bluetooth.
        claimMediaButtons(false)
        head.stop()
        motion.stop()
        sfx.stopHum(); sfx.stopCrowd()
        music.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mediaSession?.let { runCatching { it.release() } }; mediaSession = null
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
