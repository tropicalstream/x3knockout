package com.x3knockout.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.x3knockout.SettingsStore
import com.x3knockout.engine.Boxer
import com.x3knockout.engine.Clock
import com.x3knockout.engine.Fight
import com.x3knockout.engine.Hand
import com.x3knockout.engine.Level
import com.x3knockout.engine.SpriteMaterial
import com.x3knockout.engine.State
import com.x3knockout.engine.StripSet
import com.x3knockout.engine.StrokeModel
import com.x3knockout.engine.Who
import com.x3knockout.head.HeadTracker
import com.x3knockout.head.MotionTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * EVERYTHING IS A STROKE. Additive lines on black, drawn twice (a wide dim pass for the glow and a
 * fine bright pass for the core), no depth buffer — the vector-monitor idiom, in colour, on a
 * waveguide where black is the room and saturation is the only body a thing has (DESIGN.md §7, §12).
 *
 * THE CONTRACT (§12.1, measured on x3discs at 59.8–60.0 fps with 8–10 k line vertices): `GL_LINES`
 * from a stream [Batch] of 7-float vertices (`x y z r g b a`), one `glBufferData(STREAM)` per batch
 * per frame; two passes per batch per eye — wide `glLineWidth(min(4, maxLine))` at `uAlpha 0.30`,
 * then core 1.5 px at 1.0 (the HUD 3 px @ 0.28 / 1.2 px @ 1.0); blend `SRC_ALPHA, ONE`; no depth
 * test or mask; the fragment writes premultiplied `(rgb·a, a)`; `perspectiveM(62°, 4:3, 0.15,
 * 120)` drawn twice with the viewport shifted — NEVER a blit (this Adreno rejects a
 * default-framebuffer self-blit). HUD `orthoM(0, 640, 480, 0)`; [project] = `320 + ndc·320, 240 −
 * ndc·240`. `maxLine` from `GL_ALIASED_LINE_WIDTH_RANGE`, logged once as `surface: maxLine=`.
 *
 * THE WORKAROUNDS, IN THE CABINET'S OWN SPIRIT (§12.2): one big sprite (the boxer is a billboard of
 * held 12-fps frames, [boxerScene]); the palette flash (colour is per part, from [SpriteMaterial],
 * written each frame from the boxer's semantic state — the drawing never changes, [tintPass]); the
 * transparent player (your gloves are 40 strokes a hand in PLATE space, drawn by `Hud`, and he sums
 * through them for free); per-row scroll → one sway uniform for the crowd and the ropes
 * ([StaticBatch] + `uSway`, no CPU); the scoreboard off the fight plane; frames held, camera live
 * (the billboard's placement and its secondary motion are this file's model matrix at 60 Hz).
 *
 * TWO THINGS THE SPRITE CANNOT CARRY ARE DONE HERE IN WORLD SPACE, per segment, after
 * [StripSet.walkFrame] has placed the frame: the crest's HP DROOP (each spike rotated about its own
 * base, outward, 90° → 40° as he loses health — BOXER.md §4 says the crest is his health readout,
 * and a health readout cannot be baked into 122 frames) and the HEAD TILT (the stagger's ±20°
 * wobble and the hit snap, rotated about the `chin` marker rather than the feet, because a roll on
 * the whole model matrix swings a 1.9 m figure's head half a metre sideways). The sprite plane's
 * own x axis is known from the billboard yaw, so both are 2D rotations in that plane; `StripSet`
 * keeps its segments private and its transform inside `walkFrame`, which is why the work is done
 * on the emitted world coordinates and not on the model. The hit SQUASH (a non-uniform scale about
 * the feet) is the same trick, because `Place.scale` is one number.
 *
 * WHAT THIS FILE OWNS AND WHAT IT ASKS FOR. The world strokes, the camera and the GL plumbing are
 * here; the 640 × 480 plate is `Hud`'s, and this file's job for the plate is to fill one
 * [Hud.Model] a frame and hand it over ([fillModel]). The lab's posture dial is drawn here on top,
 * because it needs the tracker rather than the snapshot. **Never stop the render loop**: a
 * hit-stop stops the clocks (`Clock.Forced.HITSTOP`) and the picture keeps tracking the head.
 *
 * THE VERTEX BUDGET (§12.3): the boxer ≤ 700 segments a frame, the static ring + crowd ≈ 800, the
 * stream (the referee, your gloves, the plate, the words, the arc, the trails, the FX) ≈ 700 —
 * ≈ 4 400 line vertices, a third of what x3discs held at 60.0. The `fps=` line carries each
 * group's count; TEST.md's ceilings are `boxer=` ≤ 1 400 and `stream=` ≤ 3 000.
 */
class GLRenderer(private val ctx: Context, private val fight: Fight, private val head: HeadTracker,
                 private val motion: MotionTracker, private val store: SettingsStore) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0; private var aColor = 0
    private var uMVP = 0; private var uPointSize = 0; private var uPoint = 0; private var uAlpha = 0; private var uSway = 0; private var uT = 0
    private var uBounce = 0; private var uT2 = 0
    private var width = 1; private var height = 1
    private var lastNanos = 0L
    private var maxLine = 1f
    private val proj = FloatArray(16); private val view = FloatArray(16); private val mvp = FloatArray(16); private val ortho = FloatArray(16)
    /** The stream: the boxer, the referee, the arc, the trails, the FX. */
    private val lines = Batch(24000)
    private val pts = Batch(2000)
    private val hudBatch = Batch(16000)
    /**
     * THE EXTEND HALO (§7.4 beat 4): for the two frames of the strike in which his glove is drawn
     * 1.8× the flashed glove's strokes go here instead of [lines], so their wide pass can be drawn
     * at `glLineWidth(min(6, maxLine))` — the one place a width bump is spent, on two frames.
     */
    private val extendBatch = Batch(1200)
    /** The static groups, uploaded once, drawn with the sway uniform (§12.5). */
    private val ringBatch = StaticBatch(2000)
    private val crowdBatch = StaticBatch(4000)
    private val rnd = Random(7)
    private var statT = 0f; private var statFrames = 0
    private var labT = 0f
    private var verifyT = 0f

    /** The sprite and its tint table; `ring` / `crowd` / `referee` are plain stroke models. */
    private var strips: StripSet? = null
    private val material = SpriteMaterial()
    private lateinit var ringModel: StrokeModel
    private lateinit var crowdModel: StrokeModel
    private lateinit var refereeModel: StrokeModel
    private var refereePose: StrokeModel.Pose? = null
    private var refereeArm = -1
    private val place = StrokeModel.Place()
    private val refPlace = StrokeModel.Place()
    private val markerOut = FloatArray(3)
    private val chinW = FloatArray(3)
    private val crownW = FloatArray(3)

    // the sprite's part table, resolved once per set (see [bindParts])
    private var boundSet: StripSet? = null
    private var isHeadPart = BooleanArray(0)
    private var isCrestPart = BooleanArray(0)
    private var isBlockHatch = BooleanArray(0)
    private var pHead = -1; private var pTorso = -1; private var pHatchTorso = -1
    private var pGloveL = -1; private var pGloveR = -1; private var pHatchGloveL = -1; private var pHatchGloveR = -1
    private var pPupilL = -1; private var pPupilR = -1; private var pEyes = -1
    private var pSweat = -1; private var pSpirals = -1; private var pTongue = -1; private var pTeeth = -1
    private val pCrest = IntArray(5) { -1 }
    private val pMouth = IntArray(5) { -1 }
    private var mGloveL = -1; private var mGloveR = -1; private var mChin = -1; private var mBody = -1; private var mCrown = -1
    /** The crest's segments, held back from the emitter so each spike can be drooped about its own base. */
    private val crestBuf = FloatArray(CREST_CAP * 7)
    private var crestN = 0

    // the renderer's own real-time state: edges on the fight's fields, ramps, the crowd
    private var flashRamp = 0f; private var prevFlashGlove: Hand? = null
    private var prevHeadFlashT = 0f; private var headFlashFrames = 0; private var headRingT0 = 0f; private val headRingAt = FloatArray(3)
    private var prevBodyFlashT = 0f; private var bodyFlashFrames = 0; private var bodyRingT0 = 0f; private val bodyRingAt = FloatArray(3)
    private var headSnap = 0f
    private var prevSparksT = 0f; private var sparkT0 = 0f; private val sparkAt = FloatArray(3); private val sparkDir = FloatArray(SPARKS * 3)
    private var whooshT = 0f; private val whooshAt = FloatArray(3); private val whooshDir = FloatArray(3); private var prevFeedback = ""
    private var wasCounting = false; private var ropeShakeT = -1f
    private var answerShown = ""; private var prevAnswerWord = ""; private var answerPopT = 0f; private var answerDropT = 0f
    private var hisHpShown = 1f
    private var crowdLevel = 0f; private var ovationT = 0f; private var bobMul = 1f
    private var fightDim = 1f; private var koDim = 1f
    private var sinkY = 0f; private var downRoll = 0f
    private var detailOn = true
    private var ringAcid = false
    private var refX = REF_HOME_X; private var refZ = REF_HOME_Z; private var refArm = 0f
    /** The last four `glove_*` positions on world time — the motion trails, gone when time freezes. */
    private val trailL = FloatArray(TRAIL_N * 3); private val trailR = FloatArray(TRAIL_N * 3)
    private var trailN = 0; private var trailAcc = 0f; private var trailStrip = ""

    // camera
    private var camX = 0f; private var camY = Fight.EYE_H; private var camZ = 0f
    private var camRoll = 0f
    private var camPitchComp = 0f
    /** The sprite plane's in-plane x axis in world, from the billboard yaw (its y axis is world y). */
    private var ux = -1f; private var uz = 0f

    // palette (DESIGN.md §7.2) — the Hud's constants, so the two never drift
    private val CYAN = Hud.CYAN; private val WHITE = Hud.WHITE; private val MAGENTA = Hud.MAGENTA; private val RED = Hud.RED
    private val VIOLET = Hud.VIOLET; private val GOLD = Hud.GOLD; private val ACID = Hud.ACID; private val BLUE = Hud.BLUE
    private val WHITE_GOLD = Hud.WHITE_GOLD; private val AMBER = Hud.AMBER

    // ------------------------------------------------------------------ the plate
    private val hudSink = object : Hud.Sink {
        override fun color(rgb: FloatArray, a: Float) { hr = rgb[0]; hg = rgb[1]; hb = rgb[2]; ha = a }
        override fun colorRGB(r: Float, g: Float, b: Float, a: Float) { hr = r; hg = g; hb = b; ha = a }
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) = hl(x0, y0, x1, y1)
    }
    private val hud = Hud(hudSink)
    private val model = Hud.Model()
    private val labRows = ArrayList<Pair<String, String>>(28)
    private val menuValues = ArrayList<String>(20)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uSway = GLES30.glGetUniformLocation(program, "uSway")
        uT = GLES30.glGetUniformLocation(program, "uT")
        uBounce = GLES30.glGetUniformLocation(program, "uBounce")
        uT2 = GLES30.glGetUniformLocation(program, "uT2")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        val range = FloatArray(2); GLES30.glGetFloatv(GLES30.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        maxLine = max(1f, range[1])
        lastNanos = 0L
        for (b in arrayOf(lines, pts, hudBatch, extendBatch)) b.contextLost()
        for (b in arrayOf(ringBatch, crowdBatch)) b.contextLost()
        strips = StripSet.load(ctx, "boxer").also { fight.boxer.attach(it); bindParts(it) }
        ringModel = StrokeModel.load(ctx, "ring")
        crowdModel = StrokeModel.load(ctx, "crowd")
        refereeModel = StrokeModel.load(ctx, "referee")
        refereePose = if (refereeModel.parts.isEmpty()) null else refereeModel.newPose()
        refereeArm = refereeModel.part("arm_R")
        buildStatic()
        Log.i(TAG, "surface: maxLine=$maxLine")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        head.update(dt)
        motion.update(dt)
        val headOn = head.running && motion.available
        fight.update(dt, head.yaw, head.pitch, head.running)
        tick(dt)

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()

        lines.reset(); pts.reset(); hudBatch.reset(); extendBatch.reset()
        setupCamera(aspect, dt)
        buildScene(dt)
        fillModel(dt, headOn)
        hud.draw(model)
        overlays()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uT, fight.clock.worldT)
        GLES30.glUniform1f(uT2, ovationT)

        statFrames++; statT += dt
        if (statT >= 1f) {
            Log.i(TAG, "fps=%.1f boxer=%d static=%d stream=%d hud=%d ts=%.2f".format(Locale.US, statFrames / statT,
                strips?.frameSegCount(fight.boxer.strip.frame)?.times(2) ?: 0, ringBatch.count + crowdBatch.count, lines.count + extendBatch.count + pts.count, hudBatch.count, fight.clock.timeScale))
            statFrames = 0; statT = 0f
        }
        // The 5 Hz VERIFY pair of DESIGN.md §13: his state and yours. The review process is logcat-driven.
        verifyT += dt
        if (verifyT >= 0.2f && (fight.state == State.FIGHT || fight.state == State.KNOCKDOWN_COUNT)) {
            verifyT = 0f
            Log.i(TAG, fight.boxer.verifyLine(fight.body, fight.clock.floor))
            Log.i(TAG, fight.verifyLine())
            Log.i(TAG, motion.trace())
        }

        val wide = min(4f, maxLine)
        val core = 1.5f.coerceAtMost(maxLine)
        val ovation = fight.state == State.KO || fight.state == State.KNOCKDOWN_COUNT
        val title = fight.state == State.TITLE
        // the crowd: the wave's amplitude and alpha from the crowd meter (the lagged rate); the ovation at 0.2 / 0.6
        val crowdAmp = if (ovation) 0.20f else 0.02f + 0.10f * crowdLevel
        val crowdK = if (title) 0.25f else if (ovation) 0.60f else 0.25f + 0.35f * crowdLevel
        val bounce = if (ovation) 0.05f else 0f
        // the ring: the knockdown's shake (a decaying impulse computed here, spatial phase only), the multiplier's glow, the clapper's pulse
        val ringAmp = if (ropeShakeT in 0f..RING_SHAKE_T) 0.05f * exp(-ropeShakeT / 0.12f) * cos(ropeShakeT * 2f * PI.toFloat() * 9f) else 0f
        var ringGlow = 1f + (fight.multiplier - 1).coerceIn(0, 3) / 3f * 0.667f
        if (fight.state == State.FIGHT && fight.roundClock <= Fight.CLAPPER_FROM_S) ringGlow *= 0.85f + 0.15f * (0.5f + 0.5f * sin(fight.t * 2f * 2f * PI.toFloat()))
        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            // the static groups sway in the shader; everything else gets (0, 0, 0, 0)
            GLES30.glUniform4f(uSway, crowdAmp, 1.7f, 0.9f, 0.5f); GLES30.glUniform2f(uBounce, bounce, -6.6f)
            GLES30.glLineWidth(wide); GLES30.glUniform1f(uAlpha, 0.30f * crowdK); crowdBatch.draw()
            GLES30.glLineWidth(core); GLES30.glUniform1f(uAlpha, crowdK); crowdBatch.draw()
            GLES30.glUniform4f(uSway, ringAmp, 0f, 1.3f, 0.3f); GLES30.glUniform2f(uBounce, 0f, 0f)
            GLES30.glLineWidth(wide); GLES30.glUniform1f(uAlpha, 0.30f * ringGlow); ringBatch.draw()
            GLES30.glLineWidth(core); GLES30.glUniform1f(uAlpha, ringGlow); ringBatch.draw()
            GLES30.glUniform4f(uSway, 0f, 0f, 0f, 0f)
            GLES30.glLineWidth(wide); GLES30.glUniform1f(uAlpha, 0.30f); lines.draw(GLES30.GL_LINES)
            GLES30.glLineWidth(core); GLES30.glUniform1f(uAlpha, 1f); lines.draw(GLES30.GL_LINES)
            if (extendBatch.count > 0) {
                GLES30.glLineWidth(min(EXTEND_WIDTH, maxLine)); GLES30.glUniform1f(uAlpha, 0.30f); extendBatch.draw(GLES30.GL_LINES)
                GLES30.glLineWidth(core); GLES30.glUniform1f(uAlpha, 1f); extendBatch.draw(GLES30.GL_LINES)
            }
            GLES30.glUniform1f(uPoint, 1f); GLES30.glUniform1f(uPointSize, 9f); GLES30.glUniform1f(uAlpha, 1f); pts.draw(GLES30.GL_POINTS)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            GLES30.glLineWidth(min(3f, maxLine)); GLES30.glUniform1f(uAlpha, 0.28f); hudBatch.draw(GLES30.GL_LINES)
            GLES30.glLineWidth(1.2f.coerceAtMost(maxLine)); GLES30.glUniform1f(uAlpha, 1f); hudBatch.draw(GLES30.GL_LINES)
        }
    }

    // ------------------------------------------------------------------ the renderer's own clocks

    /**
     * THE PICTURE'S OWN BOOKKEEPING, on REAL time, once a frame before anything is drawn. Every
     * flash and ramp here is an EDGE on a field the fight or the boxer owns — `flashGlove` going
     * non-null, `headFlashT` rising from zero, a feedback word appearing — because the fight's
     * fields are a snapshot, not an event stream, and the plate must not reach back into them
     * (`Hud`'s rule). Two-frame flashes are counted in frames, as §2.5 says them: at 60 Hz that is
     * 33 ms, and on a dropped frame it is still two impacts' worth of white and not one.
     */
    private fun tick(dt: Float) {
        val f = fight; val b = f.boxer; val c = f.clock
        val inFight = f.state == State.FIGHT || f.state == State.KNOCKDOWN_COUNT
        // the telegraph flash ramps in over 30 ms real, held for the whole tell (§7.4 beat 1)
        if (b.flashGlove != null) { if (prevFlashGlove != b.flashGlove) flashRamp = 0f; flashRamp = min(1f, flashRamp + dt / FLASH_RAMP_T) } else flashRamp = 0f
        prevFlashGlove = b.flashGlove
        // the hit flashes: two frames of white on the rising edge, the impact ring for the field's life
        if (b.headFlashT > 0f && prevHeadFlashT <= 0f) {
            headFlashFrames = FLASH_FRAMES; headRingT0 = b.headFlashT
            if (mChin >= 0) strips?.markerWorld(b.strip.frame, mChin, place, headRingAt)
            // his head snaps away from the glove that landed: your LEFT glove is on world −x, so the head goes to +x
            headSnap = if (f.gloveFlashHand == Hand.RIGHT) -HEAD_SNAP else HEAD_SNAP
        } else if (headFlashFrames > 0) headFlashFrames--
        prevHeadFlashT = b.headFlashT
        if (b.bodyFlashT > 0f && prevBodyFlashT <= 0f) {
            bodyFlashFrames = FLASH_FRAMES; bodyRingT0 = b.bodyFlashT
            if (mBody >= 0) strips?.markerWorld(b.strip.frame, mBody, place, bodyRingAt)
        } else if (bodyFlashFrames > 0) bodyFlashFrames--
        prevBodyFlashT = b.bodyFlashT
        // the snap relaxes on WORLD time: the impact frame holds under a hit-stop, then recoils as the world runs
        headSnap *= exp(-c.wdt / 0.08f)
        if (abs(headSnap) < 1e-3f) headSnap = 0f
        // the crest sparks after a COUNTER or the SPECIAL (BOXER.md §3): eight, flung outward from the crown
        if (b.sparksT > 0f && prevSparksT <= 0f) {
            sparkT0 = b.sparksT
            if (mCrown >= 0) strips?.markerWorld(b.strip.frame, mCrown, place, sparkAt)
            for (i in 0 until SPARKS) {
                val ang = (i + 0.5f) * 2f * PI.toFloat() / SPARKS + (rnd.nextFloat() - 0.5f) * 0.5f
                val ca = cos(ang); val sa = sin(ang)
                sparkDir[i * 3] = ca * ux; sparkDir[i * 3 + 1] = sa; sparkDir[i * 3 + 2] = ca * uz
            }
        }
        prevSparksT = b.sparksT
        // the whoosh: his glove passed you (§7.4 beat 4). The fight names the miss in its feedback word.
        val fb = f.feedback
        if (fb != prevFeedback && fb.isNotEmpty() && (fb.startsWith("DODGE") || fb.startsWith("PERFECT"))) {
            val h = b.attack?.hand ?: b.flashGlove
            val mk = if (h == Hand.LEFT) mGloveL else mGloveR
            if (mk >= 0 && strips?.markerWorld(b.strip.frame, mk, place, whooshAt) == true) {
                whooshT = WHOOSH_T
                val tr = if (h == Hand.LEFT) trailL else trailR
                var dx = 0f; var dy = 0f; var dz = 1f
                if (trailN >= 2) { dx = tr[0] - tr[3]; dy = tr[1] - tr[4]; dz = tr[2] - tr[5] }
                val l = sqrt(dx * dx + dy * dy + dz * dz)
                if (l > 1e-3f) { whooshDir[0] = dx / l; whooshDir[1] = dy / l; whooshDir[2] = dz / l } else { whooshDir[0] = 0f; whooshDir[1] = 0f; whooshDir[2] = 1f }
            }
        }
        prevFeedback = fb
        if (whooshT > 0f) whooshT = max(0f, whooshT - dt)
        // the ropes shake on the fall of either fighter — a 0.4 s impulse on the ring's sway uniform
        val counting = f.state == State.KNOCKDOWN_COUNT
        if (counting && !wasCounting) ropeShakeT = 0f
        wasCounting = counting
        if (ropeShakeT >= 0f) { ropeShakeT += dt; if (ropeShakeT > RING_SHAKE_T) ropeShakeT = -1f }
        // the answer word: pops over 80 ms real, held on world time until the strike, dropped over 120 ms (§7.4 beat 3)
        val w = f.answerWord
        if (w != prevAnswerWord) { if (w.isNotEmpty()) { answerShown = w; answerPopT = 0f; answerDropT = 0f }; prevAnswerWord = w }
        if (answerShown.isNotEmpty()) {
            if (f.answerDropping || w.isEmpty()) answerDropT += dt else answerPopT += dt
            if (answerDropT >= Fight.ANSWER_DROP_T) answerShown = ""
        }
        // his HP bar eases over 0.3 s real (DESIGN.md §8)
        hisHpShown += (b.hpFrac - hisHpShown) * (1f - exp(-dt / 0.1f))
        // the crowd meter is the rate, lagged 200 ms — the visual twin of the audible bed (§9.3)
        crowdLevel += ((if (inFight) c.timeScale else 0.15f) - crowdLevel) * (1f - exp(-dt / Fight.CROWD_LAG_T))
        if (f.state == State.KO || counting) ovationT += dt
        // the idle sway doubles while time runs (§7.5) — eased, so the doubling is never a pop
        bobMul += ((if (c.timeScale > 0.7f) 2f else 1f) - bobMul) * (1f - exp(-dt / 0.25f))
        // looked away (§6): the fight dims to 0.5 and comes back over RESQUARE_T; never punished
        val away = inFight && abs(f.yaw) > Boxer.LOOK_AWAY_RAD
        fightDim += ((if (away) 0.5f else 1f) - fightDim) * (1f - exp(-dt / Boxer.RESQUARE_T))
        // the KO: the engine fades his gain 1 → 0.3 (BOXER.md §8's `ko` strip)
        koDim = if (f.state == State.KO) 1f - 0.7f * (f.stateT / Clock.SLOW_KO_T).coerceIn(0f, 1f) else 1f
        // your own knockdown: the view sinks 0.4 m over 0.4 s real and tips a little, the ropes rise past the frame (§5.2)
        val youDown = counting && f.downWho == Who.YOU
        sinkY += ((if (youDown) YOU_DOWN_SINK else 0f) - sinkY) * (1f - exp(-dt / 0.13f))
        downRoll += ((if (youDown) 0.12f else 0f) - downRoll) * (1f - exp(-dt / 0.2f))
        // the ring's seams flip to the player's ACID on the win (§5.4); back to BLUE for the next fight
        val acid = f.state == State.KO
        if (acid != ringAcid) { ringAcid = acid; buildRing() }
    }

    // ------------------------------------------------------------------ camera

    /**
     * THE LENS IS THE INFERRED EYE. The lean, the duck and the step move it at full strength, so
     * the world slides behind your fixed gloves — you see yourself dodge (MOTION.md, the owner's
     * note). The horizon is counter-rolled by putting the camera's up vector ON the head's own up
     * (`ROLL_COMP` 1, τ 0.08 — and mind the sign: the other way lands the arena at twice the
     * tilt). `PITCH COMP` un-pitches the view by that fraction of the nod during a duck (0 ships).
     * The hit-stop kick moves the DRAWN SCENE, not the camera (§2.5): see [buildScene]. Your own
     * knockdown sinks the eye 0.4 m and tips it 7°: the canvas comes up to meet you.
     */
    private fun setupCamera(aspect: Float, dt: Float) {
        val b = fight.body
        camX = b.headX; camY = b.headY - sinkY; camZ = b.headZ
        val shake = fight.damageFlash * Fight.CAM_SHAKE
        val sx = (rnd.nextFloat() - 0.5f) * shake; val sy = (rnd.nextFloat() - 0.5f) * shake
        val yaw = fight.yaw
        camPitchComp += (fight.pitchComp * (motion.restPitch - motion.pitchG).coerceAtLeast(0f) - camPitchComp) * (1f - exp(-dt / Fight.ROLL_TAU))
        val pitch = fight.pitch + camPitchComp
        val cp = cos(pitch)
        val fx = sin(yaw) * cp; val fy = sin(pitch); val fz = -cos(yaw) * cp
        camRoll += (Fight.ROLL_COMP * motion.roll - camRoll) * (1f - exp(-dt / Fight.ROLL_TAU))
        val roll = camRoll + downRoll
        val rx = cos(yaw); val rz = sin(yaw)
        val upx = sin(roll) * rx; val upy = cos(roll); val upz = sin(roll) * rz
        Matrix.setLookAtM(view, 0, camX + sx, camY + sy, camZ, camX + sx + fx, camY + sy + fy, camZ + fz, upx, upy, upz)
        Matrix.perspectiveM(proj, 0, 62f, aspect, 0.15f, 120f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
    }

    private val projIn = FloatArray(4); private val projOut = FloatArray(4)
    /** World → plate (640 × 480, y down). False behind the eye. */
    private fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        projIn[0] = x; projIn[1] = y; projIn[2] = z; projIn[3] = 1f
        Matrix.multiplyMV(projOut, 0, mvp, 0, projIn, 0)
        val w = projOut[3]
        if (w < 0.05f) return false
        out[0] = 320f + (projOut[0] / w) * 320f
        out[1] = 240f - (projOut[1] / w) * 240f
        return true
    }

    // ------------------------------------------------------------------ world strokes

    private var cr = 1f; private var cg = 1f; private var cb = 1f; private var ca = 1f
    private fun wcolor(t: FloatArray, a: Float) { cr = t[0]; cg = t[1]; cb = t[2]; ca = a }
    private fun wline(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        lines.v(x0 + kickWx, y0 + kickWy, z0, cr, cg, cb, ca); lines.v(x1 + kickWx, y1 + kickWy, z1, cr, cg, cb, ca)
    }
    /** A ring of [n] segments in the sprite plane (its x axis [ux],[uz]; y up) around a world point. */
    private fun wring(cx: Float, cy: Float, cz: Float, r: Float, n: Int) {
        var px = cx + r * ux; var py = cy; var pz = cz + r * uz
        for (i in 1..n) {
            val a = i * 2f * PI.toFloat() / n
            val nx = cx + r * cos(a) * ux; val ny = cy + r * sin(a); val nz = cz + r * cos(a) * uz
            wline(px, py, pz, nx, ny, nz); px = nx; py = ny; pz = nz
        }
    }
    /** The hit-stop kick, in world metres at the boxer's range: 6 px ≈ 6 / 10.3 px-per-degree ≈ 0.026 m at 2.6 m. */
    private var kickWx = 0f; private var kickWy = 0f

    /**
     * THE STATIC GROUPS: the ring (BLUE, its `grid` part at 0.18 — alpha is not in the file, so the
     * part name carries it) and the crowd (VIOLET, the back rows dimmer so depth reads by
     * brightness). Built once; the sway is the shader's. The ring model is authored about its own
     * centre and placed at the boxer's range; the crowd is authored in world space and placed at
     * the origin. The referee is NOT static: he walks to the centre and counts with an arm, so he
     * is walked into the stream each frame ([refereeScene]) — nine segments, nothing.
     */
    private fun buildStatic() {
        buildRing()
        crowdBatch.reset()
        place.x = 0f; place.y = 0f; place.z = 0f; place.yaw = 0f; place.pitch = 0f; place.roll = 0f; place.scale = 1f
        if (crowdModel.parts.isEmpty()) proceduralCrowd() else crowdModel.walk(place, null) { x0, y0, z0, x1, y1, z1, pi ->
            val a = when (crowdModel.parts[pi].name) { "row0" -> 1f; "row1" -> 0.8f; else -> 0.6f }
            crowdBatch.v(x0, y0, z0, VIOLET[0], VIOLET[1], VIOLET[2], a); crowdBatch.v(x1, y1, z1, VIOLET[0], VIOLET[1], VIOLET[2], a)
        }
        crowdBatch.upload()
    }

    private fun buildRing() {
        ringBatch.reset()
        val c = if (ringAcid) ACID else BLUE
        place.x = 0f; place.y = 0f; place.z = Boxer.Z; place.yaw = 0f; place.pitch = 0f; place.roll = 0f; place.scale = 1f
        if (ringModel.parts.isEmpty()) proceduralRing(c) else ringModel.walk(place, null) { x0, y0, z0, x1, y1, z1, pi ->
            val a = when (ringModel.parts[pi].name) { "grid" -> 0.18f; "apron" -> 0.4f; else -> 0.6f }
            ringBatch.v(x0, y0, z0, c[0], c[1], c[2], a); ringBatch.v(x1, y1, z1, c[0], c[1], c[2], a)
        }
        ringBatch.upload()
    }

    /**
     * A ring with no `ring.json`: four posts, three straight ropes a side, the apron. Straight
     * ropes read as a fence (DESIGN.md §6) — the authored asset's catenaries replace this the day
     * it exists; the fallback is here so a build with a missing asset still has a floor under him.
     */
    private fun proceduralRing(c: FloatArray) {
        val hw = 3f; val cz = Boxer.Z
        fun v(x: Float, y: Float, z: Float, a: Float) = ringBatch.v(x, y, z, c[0], c[1], c[2], a)
        val corners = arrayOf(floatArrayOf(-hw, cz - hw), floatArrayOf(hw, cz - hw), floatArrayOf(hw, cz + hw), floatArrayOf(-hw, cz + hw))
        for (k in corners) { v(k[0], 0f, k[1], 0.6f); v(k[0], 1.3f, k[1], 0.6f) }
        for (i in 0 until 4) {
            val a = corners[i]; val b = corners[(i + 1) % 4]
            for (h in floatArrayOf(0.45f, 0.85f, 1.25f)) { v(a[0], h, a[1], 0.6f); v(b[0], h, b[1], 0.6f) }
            v(a[0], 0f, a[1], 0.6f); v(b[0], 0f, b[1], 0.6f)
        }
        for (i in 0..6) {
            val t = -hw + i * (2f * hw / 6f)
            v(t, 0f, cz - hw, 0.18f); v(t, 0f, cz + hw, 0.18f)
            v(-hw, 0f, cz + t, 0.18f); v(hw, 0f, cz + t, 0.18f)
        }
    }

    /** A crowd with no `crowd.json`: three rows of three-stroke humps, so the ovation has something to raise. */
    private fun proceduralCrowd() {
        val rows = floatArrayOf(-6f, 0.55f, -7.5f, 1.05f, -9f, 1.55f)
        for (r in 0 until 3) {
            val z = rows[r * 2]; val base = rows[r * 2 + 1]; val a = 1f - 0.2f * r
            for (i in 0 until 40) {
                val x = (i - 19.5f) * 0.5f
                fun v(px: Float, py: Float) = crowdBatch.v(px, py, z, VIOLET[0], VIOLET[1], VIOLET[2], a)
                v(x - 0.24f, base + 0.30f); v(x - 0.08f, base + 0.66f)
                v(x - 0.08f, base + 0.66f); v(x + 0.08f, base + 0.66f)
                v(x + 0.08f, base + 0.66f); v(x + 0.24f, base + 0.30f)
            }
        }
    }

    private fun buildScene(dt: Float) {
        // the hit-stop kick: the whole drawn scene, not the camera, 6 px away from the punch, easing back over the stop
        kickWx = fight.kickX * 0.0045f; kickWy = fight.kickY * 0.0045f
        if (fight.state == State.TITLE) { boxerScene(dim = 0.55f); refereeScene(dt); return }
        boxerScene(dim = 1f)
        sampleTrails(fight.clock.wdt)
        telegraphScene()
        trailsScene()
        fxScene()
        refereeScene(dt)
    }

    // ------------------------------------------------------------------ the boxer

    /**
     * Resolve the sprite's part table once per set: which parts ride on the head (they tilt with
     * it), which are the crest (they droop), which hatches are colour blocks at 0.55 rather than
     * skin at 0.35, and the named parts and markers the tint pass writes by name. The names are
     * `blender/assets/boxer.py`'s PARTS and MARKERS; a name that is not there resolves to −1 and
     * every write to it is a no-op, so a re-authored asset with fewer parts still draws.
     */
    private fun bindParts(set: StripSet) {
        boundSet = set
        val n = set.nParts
        isHeadPart = BooleanArray(n); isCrestPart = BooleanArray(n); isBlockHatch = BooleanArray(n)
        for ((i, p) in set.parts.withIndex()) {
            val nm = p.name
            isCrestPart[i] = nm.startsWith("crest")
            isHeadPart[i] = isCrestPart[i] || nm == "head" || nm.startsWith("brow") || nm.startsWith("eye") || nm.startsWith("pupil") ||
                nm == "nose" || nm.startsWith("ear") || nm.startsWith("mouth") || nm == "jaw_hatch" || nm.startsWith("hatch_head") ||
                nm == "sweat" || nm.startsWith("spiral") || nm == "teeth" || nm == "tongue"
            isBlockHatch[i] = nm.startsWith("hatch_glove") || nm == "hatch_trunks"
        }
        pHead = set.part("head"); pTorso = set.part("torso"); pHatchTorso = set.part("hatch_torso")
        pGloveL = set.part("glove_L"); pGloveR = set.part("glove_R"); pHatchGloveL = set.part("hatch_glove_L"); pHatchGloveR = set.part("hatch_glove_R")
        pPupilL = set.part("pupil_L"); pPupilR = set.part("pupil_R"); pEyes = set.part("eyes")
        pSweat = set.part("sweat"); pSpirals = set.part("spirals"); pTongue = set.part("tongue"); pTeeth = set.part("teeth")
        for (k in 0 until 5) pCrest[k] = set.part("crest_$k")
        val mouths = arrayOf("mouth_grin", "mouth_flat", "mouth_o", "mouth_grimace", "mouth_crow")
        for (k in mouths.indices) pMouth[k] = set.part(mouths[k])
        mGloveL = set.marker("glove_L"); mGloveR = set.marker("glove_R"); mChin = set.marker("chin"); mBody = set.marker("body"); mCrown = set.marker("crown")
    }

    /**
     * THE BOXER: one held frame of one strip, billboarded to the camera POSITION through
     * [StripSet.headingTo] (the eye moves only by the lean and the step, so the yaw changes by at
     * most ≈ 12° — enough parallax to sell the dodge), with the SECONDARY MOTION in the matrix at
     * 60 Hz: the idle sway (±2° roll, ±3 cm bob on `sin(worldT)`, doubled while time runs), the
     * stagger wobble and the hit snap (about the chin, see the class note), the hit squash. Colour
     * is the tint table, written by [tintPass] from his semantic state. Exactly one thing white.
     */
    private fun boxerScene(dim: Float) {
        val set = strips ?: return
        if (set.frameCount == 0) return
        if (boundSet !== set) bindParts(set)
        val b = fight.boxer
        val frame = b.strip.frame
        val wt = fight.clock.worldT
        val gain = dim * fightDim * koDim
        place.x = Boxer.X; place.z = Boxer.Z
        place.y = 0.03f * sin(wt * 2.1f) * bobMul
        place.yaw = set.headingTo(Boxer.X, Boxer.Z, camX, camZ)
        place.roll = 0.035f * sin(wt * 1.3f)
        place.pitch = 0f
        place.scale = 1f
        ux = cos(place.yaw); uz = -sin(place.yaw)
        val d = sqrt((Boxer.X - camX) * (Boxer.X - camX) + (Boxer.Z - camZ) * (Boxer.Z - camZ))
        val hatchK = sqrt(2.6f / d.coerceAtLeast(0.3f))
        // DETAIL LOD with hysteresis (§12.7): off beyond 3.4 m, on inside 3.0 m, so nothing flickers on a lean
        if (d > DETAIL_FAR) detailOn = false else if (d < DETAIL_NEAR) detailOn = true
        if (!set.markerWorld(frame, mChin, place, chinW)) { chinW[0] = Boxer.X; chinW[1] = 1.2f; chinW[2] = Boxer.Z }
        if (!set.markerWorld(frame, mCrown, place, crownW)) { crownW[0] = Boxer.X; crownW[1] = 2.1f; crownW[2] = Boxer.Z }

        tintPass(set, gain, hatchK)

        val tint = material.tint
        val squash = b.squash.coerceIn(-1f, 1f)
        val sxz = 1f + squash * 0.10f; val sy = 1f - squash * 0.08f
        val tilt = b.wobble + headSnap
        val ct = cos(tilt); val st = sin(tilt)
        val extendPart = if (b.extend) (if (b.flashGlove == Hand.LEFT) pGloveL else if (b.flashGlove == Hand.RIGHT) pGloveR else -1) else -1
        val extendHatch = if (b.extend) (if (b.flashGlove == Hand.LEFT) pHatchGloveL else if (b.flashGlove == Hand.RIGHT) pHatchGloveR else -1) else -1
        crestN = 0
        set.walkFrame(frame, place) { x0, y0, z0, x1, y1, z1, pi ->
            val s = pi.coerceAtMost(SpriteMaterial.MAX_PARTS - 1) * 4
            val g = tint[s + 3]
            if (g <= 0f) return@walkFrame
            if (pi < isCrestPart.size && isCrestPart[pi] && crestN < CREST_CAP) {
                val at = crestN * 7
                crestBuf[at] = x0; crestBuf[at + 1] = y0; crestBuf[at + 2] = z0; crestBuf[at + 3] = x1; crestBuf[at + 4] = y1; crestBuf[at + 5] = z1; crestBuf[at + 6] = pi.toFloat()
                crestN++
                return@walkFrame
            }
            val head = pi < isHeadPart.size && isHeadPart[pi]
            emitSeg(x0, y0, z0, x1, y1, z1, tint[s], tint[s + 1], tint[s + 2], g, head, ct, st, sxz, sy, pi == extendPart || pi == extendHatch)
        }
        crestPass(b, tint, ct, st, sxz, sy)
    }

    /**
     * THE TINT PASS — the cabinet's palette flash (§7.2, §7.4, §12.4, BOXER.md §1), written every
     * frame from the boxer's semantic fields into the 32-slot table. The rules, in the order they
     * overwrite each other: the class gains (outline 1, detail by the LOD, hatch 0.35 skin / 0.55
     * blocks × √(2.6 / d)); the variants nobody should see now at 0 (sweat and spirals unless he
     * is hurt or staggered; the mouth variants, if the asset ever splits them, all but one); the
     * crest's hue and its lost spikes; the pupils (WHITE on a jab's tell, hidden behind a hook's
     * slits — no pupil flash is how you know it is not a jab); the telegraph glove WHITE α 1.5 with
     * its hatch at full, ramped in over 30 ms; the sparks dimming the crest they scatter from; and
     * last, because it must win, the two-frame WHITE of the head or the torso on a hit.
     */
    private fun tintPass(set: StripSet, gain: Float, hatchK: Float) {
        val b = fight.boxer
        material.reset(set)
        for ((i, p) in set.parts.withIndex()) {
            when (p.cls) {
                StripSet.Cls.HATCH -> material.gain(i, (if (i < isBlockHatch.size && isBlockHatch[i]) HATCH_BLOCK else HATCH_SKIN) * hatchK * gain)
                StripSet.Cls.DETAIL -> material.gain(i, if (detailOn) gain else 0f)
                StripSet.Cls.OUTLINE -> material.gain(i, gain)
            }
        }
        val hurt = b.headFlashT > 0f || b.bodyFlashT > 0f || b.phase == Boxer.Phase.HIT || b.phase == Boxer.Phase.STAGGER || b.phase == Boxer.Phase.STUN
        if (!hurt) material.gain(pSweat, 0f)
        if (!b.spirals) material.gain(pSpirals, 0f)
        if (pTongue >= 0 && !b.tongue) material.gain(pTongue, 0f)
        if (pTeeth >= 0 && b.mouth != Boxer.Mouth.GRIMACE) material.gain(pTeeth, 0f)
        for (k in 0 until 5) if (pMouth[k] >= 0 && k != b.mouth.ordinal) material.gain(pMouth[k], 0f)
        // the crest: VIOLET at rest, GOLD for a hook, WHITE for the uppercut, AMBER and dim when he waits you out; a spike lost per knockdown
        val crestRgb = when (b.crest) { Boxer.Crest.VIOLET -> VIOLET; Boxer.Crest.GOLD, Boxer.Crest.GOLD_DROOP -> GOLD; Boxer.Crest.WHITE -> WHITE; Boxer.Crest.AMBER -> AMBER }
        val crestGain = gain * (if (b.crest == Boxer.Crest.AMBER) 0.6f else if (b.crest == Boxer.Crest.WHITE) 1.3f else 1f) * (if (b.sparksT > 0f) 0.3f else 1f)
        for (k in 0 until 5) material.set(pCrest[k], crestRgb, if (k < b.crestSpikes) crestGain else 0f)
        // the eyes: a pupil flash is a jab and which hand; slits are a hook (the frame draws the slits, the pupils go dark)
        if (b.slits) { material.gain(pPupilL, 0f); material.gain(pPupilR, 0f) }
        val pupilGain = (1f + 0.4f * flashRamp) * gain
        if (b.pupils == Boxer.Pupils.LEFT || b.pupils == Boxer.Pupils.BOTH) material.set(pPupilL, mixR(RED, WHITE, flashRamp), mixG(RED, WHITE, flashRamp), mixB(RED, WHITE, flashRamp), pupilGain)
        if (b.pupils == Boxer.Pupils.RIGHT || b.pupils == Boxer.Pupils.BOTH) material.set(pPupilR, mixR(RED, WHITE, flashRamp), mixG(RED, WHITE, flashRamp), mixB(RED, WHITE, flashRamp), pupilGain)
        // the telegraph glove: RED → WHITE over the 30 ms ramp, α 1.5 for the tell, 1.6 on the extend; its hatch to full gain
        b.flashGlove?.let { h ->
            val g = if (h == Hand.LEFT) pGloveL else pGloveR
            val hg = if (h == Hand.LEFT) pHatchGloveL else pHatchGloveR
            material.set(g, mixR(RED, WHITE, flashRamp), mixG(RED, WHITE, flashRamp), mixB(RED, WHITE, flashRamp), (1f + (if (b.extend) 0.6f else 0.5f) * flashRamp) * gain)
            material.gain(hg, (HATCH_BLOCK + (1f - HATCH_BLOCK) * flashRamp) * gain)
        }
        // the impact frame's white, two frames, over everything
        if (headFlashFrames > 0) material.set(pHead, WHITE, 1.5f * gain)
        if (bodyFlashFrames > 0) { material.set(pTorso, WHITE, 1.5f * gain); material.set(pHatchTorso, WHITE, 0.8f * gain) }
    }

    private fun mixR(a: FloatArray, b: FloatArray, k: Float) = a[0] + (b[0] - a[0]) * k
    private fun mixG(a: FloatArray, b: FloatArray, k: Float) = a[1] + (b[1] - a[1]) * k
    private fun mixB(a: FloatArray, b: FloatArray, k: Float) = a[2] + (b[2] - a[2]) * k

    /**
     * One placed segment of the sprite → the stream. Head parts are tilted about the chin in the
     * sprite plane (the stagger wobble, the hit snap); then the squash about the feet; then the
     * hit-stop kick; then the batch — the extend halo's for the flashed glove on its two frames.
     */
    private fun emitSeg(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, bl: Float, a: Float,
                        head: Boolean, ct: Float, st: Float, sxz: Float, sy: Float, extend: Boolean) {
        var ax = x0; var ay = y0; var az = z0; var bx = x1; var by = y1; var bz = z1
        if (head && st != 0f) {
            // rotate about the chin in the (u, y) plane: u is the sprite's x axis in world
            var dx = ax - chinW[0]; var dy = ay - chinW[1]; var dz = az - chinW[2]
            var u = dx * ux + dz * uz; var nx = dx - u * ux; var nz = dz - u * uz
            var u2 = u * ct - dy * st; var e2 = u * st + dy * ct
            ax = chinW[0] + u2 * ux + nx; ay = chinW[1] + e2; az = chinW[2] + u2 * uz + nz
            dx = bx - chinW[0]; dy = by - chinW[1]; dz = bz - chinW[2]
            u = dx * ux + dz * uz; nx = dx - u * ux; nz = dz - u * uz
            u2 = u * ct - dy * st; e2 = u * st + dy * ct
            bx = chinW[0] + u2 * ux + nx; by = chinW[1] + e2; bz = chinW[2] + u2 * uz + nz
        }
        if (sxz != 1f || sy != 1f) {
            ax = Boxer.X + (ax - Boxer.X) * sxz; az = Boxer.Z + (az - Boxer.Z) * sxz; ay *= sy
            bx = Boxer.X + (bx - Boxer.X) * sxz; bz = Boxer.Z + (bz - Boxer.Z) * sxz; by *= sy
        }
        val out = if (extend) extendBatch else lines
        out.v(ax + kickWx, ay + kickWy, az, r, g, bl, a); out.v(bx + kickWx, by + kickWy, bz, r, g, bl, a)
    }

    /**
     * THE CREST DROOPS WITH HIS HEALTH (BOXER.md §4: spike angle = 90° × HP / max, minimum 40°),
     * and leans over as one for the low hook's cue (`GOLD_DROOP`). Each spike is rotated in the
     * sprite plane about its own base — the lowest point among its segments — outward from the
     * crown, so a hurt boxer's crest fans and sags instead of shrinking. Done here rather than in
     * the strips because health is continuous and the frames are not.
     */
    private fun crestPass(b: Boxer, tint: FloatArray, ct: Float, st: Float, sxz: Float, sy: Float) {
        if (crestN == 0) return
        val droop = b.crestDroop.coerceIn(0f, 1f) * DROOP_MAX
        val lowLean = if (b.crest == Boxer.Crest.GOLD_DROOP) 0.5f else 0f
        var part = -1; var baseX = 0f; var baseY = 9f; var baseZ = 0f; var phi = 0f; var cphi = 1f; var sphi = 0f
        for (i in 0 until crestN) {
            val at = i * 7
            val pi = crestBuf[at + 6].toInt()
            if (pi != part) {
                // a new spike: find its base (min y over its segments), the lean's side, the angle
                part = pi; baseY = 9f
                var j = i
                while (j < crestN && crestBuf[j * 7 + 6].toInt() == pi) {
                    val q = j * 7
                    if (crestBuf[q + 1] < baseY) { baseY = crestBuf[q + 1]; baseX = crestBuf[q]; baseZ = crestBuf[q + 2] }
                    if (crestBuf[q + 4] < baseY) { baseY = crestBuf[q + 4]; baseX = crestBuf[q + 3]; baseZ = crestBuf[q + 5] }
                    j++
                }
                val side = (baseX - crownW[0]) * ux + (baseZ - crownW[2]) * uz
                val dir = if (side < -0.01f) -1f else 1f
                phi = -dir * droop + lowLean
                cphi = cos(phi); sphi = sin(phi)
            }
            val s = pi.coerceAtMost(SpriteMaterial.MAX_PARTS - 1) * 4
            var x0 = crestBuf[at]; var y0 = crestBuf[at + 1]; var z0 = crestBuf[at + 2]
            var x1 = crestBuf[at + 3]; var y1 = crestBuf[at + 4]; var z1 = crestBuf[at + 5]
            if (phi != 0f) {
                var dx = x0 - baseX; var dy = y0 - baseY; var dz = z0 - baseZ
                var u = dx * ux + dz * uz; var nx = dx - u * ux; var nz = dz - u * uz
                var u2 = u * cphi - dy * sphi; var e2 = u * sphi + dy * cphi
                x0 = baseX + u2 * ux + nx; y0 = baseY + e2; z0 = baseZ + u2 * uz + nz
                dx = x1 - baseX; dy = y1 - baseY; dz = z1 - baseZ
                u = dx * ux + dz * uz; nx = dx - u * ux; nz = dz - u * uz
                u2 = u * cphi - dy * sphi; e2 = u * sphi + dy * cphi
                x1 = baseX + u2 * ux + nx; y1 = baseY + e2; z1 = baseZ + u2 * uz + nz
            }
            emitSeg(x0, y0, z0, x1, y1, z1, tint[s], tint[s + 1], tint[s + 2], tint[s + 3], true, ct, st, sxz, sy, false)
        }
    }

    // ------------------------------------------------------------------ the telegraph, the trails, the FX

    /**
     * THE ARC (§7.4 beat 2), only while `timeScale < 0.35`: a 12-segment line from the punching
     * glove's marker to the aim point on your inferred body, RED at α 0.4 × (1 − rate / 0.35), a
     * WHITE bead (a 0.16 m cross + a point) on the target that slides along the arc as the wind-up
     * ages. World-fixed: a correct dodge is visible as the bead sliding off your face. A feint has
     * no aim point ([Boxer.aimSet] false), so its bead never leaves the glove — the read.
     */
    private fun telegraphScene() {
        val b = fight.boxer
        val set = strips ?: return
        val rate = fight.clock.timeScale
        if (!b.aimSet || rate >= 0.35f) return
        val h = b.flashGlove ?: b.attack?.hand ?: return
        if (!set.markerWorld(b.strip.frame, if (h == Hand.LEFT) mGloveL else mGloveR, place, markerOut)) return
        val a = 0.4f * (1f - rate / 0.35f) * fightDim
        wcolor(RED, a)
        var px = markerOut[0]; var py = markerOut[1]; var pz = markerOut[2]
        for (i in 1..12) {
            val u = i / 12f
            val nx = markerOut[0] + (b.aimX - markerOut[0]) * u; val ny = markerOut[1] + (b.aimY - markerOut[1]) * u; val nz = markerOut[2] + (b.aimZ - markerOut[2]) * u
            wline(px, py, pz, nx, ny, nz); px = nx; py = ny; pz = nz
        }
        val k = b.tellFrac
        val bx = markerOut[0] + (b.aimX - markerOut[0]) * k; val by = markerOut[1] + (b.aimY - markerOut[1]) * k; val bz = markerOut[2] + (b.aimZ - markerOut[2]) * k
        wcolor(WHITE, 0.9f * fightDim)
        wline(bx - 0.08f, by, bz, bx + 0.08f, by, bz); wline(bx, by - 0.08f, bz, bx, by + 0.08f, bz)
        pts.v(bx, by, bz, 1f, 1f, 1f, 0.9f * fightDim)
    }

    /**
     * The glove markers are sampled every 1/30 of a WORLD second into two four-deep rings: at rate
     * 1 that is two samples a frame-pair, at the floor none — the trail is a record of world
     * motion. A strip cut jumps the markers, so the rings are emptied on a strip change rather
     * than draw a speed line across the cut.
     */
    private fun sampleTrails(wdt: Float) {
        val set = strips ?: return
        if (set.nMarkers == 0 || mGloveL < 0 || mGloveR < 0) return
        val name = fight.boxer.strip.name
        if (name != trailStrip) { trailStrip = name; trailN = 0; trailAcc = 0f }
        trailAcc += wdt
        if (trailAcc < TRAIL_DT) return
        trailAcc = 0f
        val frame = fight.boxer.strip.frame
        for (i in TRAIL_N - 1 downTo 1) for (k in 0 until 3) { trailL[i * 3 + k] = trailL[(i - 1) * 3 + k]; trailR[i * 3 + k] = trailR[(i - 1) * 3 + k] }
        if (set.markerWorld(frame, mGloveL, place, markerOut)) { trailL[0] = markerOut[0]; trailL[1] = markerOut[1]; trailL[2] = markerOut[2] }
        if (set.markerWorld(frame, mGloveR, place, markerOut)) { trailR[0] = markerOut[0]; trailR[1] = markerOut[1]; trailR[2] = markerOut[2] }
        trailN = min(TRAIL_N, trailN + 1)
    }

    /** The last four glove positions, drawn as three fading segments each: speed lines with zero authoring, gone when time freezes. */
    private fun trailsScene() {
        if (trailN < 2) return
        val rate = fight.clock.timeScale
        val k = (rate / 0.35f).coerceIn(0f, 1f) * fightDim
        if (k <= 0.02f) return
        val reach = if (rate > 0.7f) trailN else min(trailN, 3)
        for (tr in arrayOf(trailL, trailR)) {
            for (i in 0 until reach - 1) {
                val x0 = tr[i * 3]; val y0 = tr[i * 3 + 1]; val z0 = tr[i * 3 + 2]
                val x1 = tr[i * 3 + 3]; val y1 = tr[i * 3 + 4]; val z1 = tr[i * 3 + 5]
                val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
                val l2 = dx * dx + dy * dy + dz * dz
                if (l2 < TRAIL_MIN_M2 || l2 > 1f) continue
                wcolor(RED, 0.45f * (1f - i / (TRAIL_N - 1f)) * k)
                wline(x0, y0, z0, x1, y1, z1)
            }
        }
    }

    /**
     * The FX at the markers: the impact stars and the WHITE ring on a hit, the crest's sparks after
     * a COUNTER or the SPECIAL, the whoosh of a glove that missed you, the stars circling his crown
     * while he is on the canvas. All on real time (they belong to the plate's clock), all at the
     * CURRENT frame's markers where the thing they mark still moves, and at a captured point where
     * it does not (a ring must expand from where the punch landed, not follow the head's snap).
     */
    private fun fxScene() {
        val set = strips ?: return
        val b = fight.boxer; val f = fight
        // eight WHITE-GOLD points scatter from the marker over the impact's 0.3 s
        if (f.impactT > 0f) {
            val m = set.marker(f.impactMarker.ifEmpty { "chin" })
            if (set.markerWorld(b.strip.frame, m, place, markerOut)) {
                val k = 1f - f.impactT / 0.3f
                for (i in 0 until 8) {
                    val ang = i * (PI.toFloat() / 4f) + 0.3f
                    val r = 0.05f + 0.35f * k
                    pts.v(markerOut[0] + cos(ang) * r * ux, markerOut[1] + sin(ang) * r, markerOut[2] + cos(ang) * r * uz, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], (1f - k) * 0.9f)
                }
            }
        }
        // the WHITE ring expanding from a head hit over the flash's life; the same from a body blow
        if (b.headFlashT > 0f && headRingT0 > 0f) {
            val k = 1f - (b.headFlashT / headRingT0).coerceIn(0f, 1f)
            wcolor(WHITE, 0.8f * (1f - k)); wring(headRingAt[0], headRingAt[1] + 0.25f, headRingAt[2], 0.08f + 0.32f * k, 16)
        }
        if (b.bodyFlashT > 0f && bodyRingT0 > 0f) {
            val k = 1f - (b.bodyFlashT / bodyRingT0).coerceIn(0f, 1f)
            wcolor(WHITE_GOLD, 0.7f * (1f - k)); wring(bodyRingAt[0], bodyRingAt[1], bodyRingAt[2], 0.08f + 0.30f * k, 12)
        }
        // the crest sparks: eight short strokes flung outward from the crown, VIOLET going WHITE-GOLD, 0.4 s
        if (b.sparksT > 0f && sparkT0 > 0f) {
            val k = 1f - (b.sparksT / sparkT0).coerceIn(0f, 1f)
            val dist = 0.08f + 0.55f * k
            for (i in 0 until SPARKS) {
                val dx = sparkDir[i * 3]; val dy = sparkDir[i * 3 + 1] - 1.2f * k * k; val dz = sparkDir[i * 3 + 2]
                val x = sparkAt[0] + dx * dist; val y = sparkAt[1] + dy * dist; val z = sparkAt[2] + dz * dist
                lines.v(x, y, z, mixR(VIOLET, WHITE_GOLD, k), mixG(VIOLET, WHITE_GOLD, k), mixB(VIOLET, WHITE_GOLD, k), (1f - k) * 0.9f)
                lines.v(x + dx * 0.07f, y + dy * 0.07f, z + dz * 0.07f, WHITE_GOLD[0], WHITE_GOLD[1], WHITE_GOLD[2], (1f - k) * 0.9f)
            }
        }
        // the whoosh: three strokes trailing the glove that passed the plate's edge, fading 0.3 s
        if (whooshT > 0f) {
            val k = 1f - whooshT / WHOOSH_T
            val len = 0.25f + 0.35f * k
            // a perpendicular in the sprite plane for the two outer strokes
            val px = -whooshDir[1] * ux; val py = whooshDir[0] * ux + whooshDir[2] * uz; val pz = -whooshDir[1] * uz
            for (o in -1..1) {
                val ox = px * o * 0.09f; val oy = py * o * 0.09f; val oz = pz * o * 0.09f
                val sx = whooshAt[0] + ox - whooshDir[0] * 0.3f * k; val sy = whooshAt[1] + oy - whooshDir[1] * 0.3f * k; val sz = whooshAt[2] + oz - whooshDir[2] * 0.3f * k
                wcolor(RED, 0.35f * (1f - k) * (if (o == 0) 1f else 0.6f))
                wline(sx, sy, sz, sx - whooshDir[0] * len, sy - whooshDir[1] * len, sz - whooshDir[2] * len)
            }
        }
        // out cold: three stars circling the crown on real time (BOXER.md §8, `down` and `ko`)
        if (b.phase == Boxer.Phase.DOWN || b.phase == Boxer.Phase.KO) {
            val t = f.t * 3.5f
            for (i in 0 until 3) {
                val ang = t + i * 2f * PI.toFloat() / 3f
                val x = crownW[0] + 0.28f * cos(ang) * ux; val y = crownW[1] + 0.06f * sin(ang * 2f); val z = crownW[2] + 0.28f * cos(ang) * uz + 0.12f * sin(ang)
                wcolor(WHITE_GOLD, 0.75f * koDim.coerceAtLeast(0.4f))
                wline(x - 0.05f * ux, y, z - 0.05f * uz, x + 0.05f * ux, y, z + 0.05f * uz); wline(x, y - 0.05f, z, x, y + 0.05f, z)
                wline(x - 0.03f * ux, y - 0.03f, z - 0.03f * uz, x + 0.03f * ux, y + 0.03f, z + 0.03f * uz); wline(x - 0.03f * ux, y + 0.03f, z - 0.03f * uz, x + 0.03f * ux, y - 0.03f, z + 0.03f * uz)
            }
        }
    }

    /**
     * THE REFEREE (§6): nine white strokes at the far-left post, walked into the stream each frame
     * so he can move — on a knockdown he slides to the centre over 0.6 s and counts with his right
     * arm, two `arm_R` angles alternating per numeral (the numerals are the plate's). The arm is a
     * `StrokeModel.Pose` roll about the part's pivot — the shoulder, which `referee.py` put there
     * for exactly this — eased so it swings rather than pops. With no asset a nine-stroke stand-in
     * is drawn from a table; the ring and the count still read.
     */
    private fun refereeScene(dt: Float) {
        val f = fight
        val counting = f.state == State.KNOCKDOWN_COUNT
        val tx = if (counting) REF_CENTRE_X else REF_HOME_X; val tz = if (counting) REF_CENTRE_Z else REF_HOME_Z
        val k = 1f - exp(-dt / 0.2f)
        refX += (tx - refX) * k; refZ += (tz - refZ) * k
        val armTarget = if (counting) (if (f.countN % 2 == 1) 2.6f else 0.25f) else 0f
        refArm += (armTarget - refArm) * (1f - exp(-dt / 0.06f))
        refPlace.x = refX; refPlace.y = 0f; refPlace.z = refZ
        refPlace.yaw = atan2(-(camX - refX), -(camZ - refZ))
        refPlace.pitch = 0f; refPlace.roll = 0f; refPlace.scale = 1f
        val a = (if (counting) 0.95f else if (f.state == State.TITLE) 0.4f else 0.55f) * (if (f.state == State.TITLE) 1f else fightDim)
        val pose = refereePose
        if (pose != null && refereeModel.parts.isNotEmpty()) {
            pose.reset()
            if (refereeArm >= 0) pose.roll[refereeArm] = refArm
            refereeModel.walk(refPlace, pose) { x0, y0, z0, x1, y1, z1, _ ->
                lines.v(x0, y0, z0, WHITE[0], WHITE[1], WHITE[2], a); lines.v(x1, y1, z1, WHITE[0], WHITE[1], WHITE[2], a)
            }
        } else {
            val cy = cos(refPlace.yaw); val sy = sin(refPlace.yaw)
            fun p(x: Float, y: Float) = lines.v(refX + x * cy, y, refZ - x * sy, WHITE[0], WHITE[1], WHITE[2], a)
            p(0f, 1.75f); p(0.15f, 1.6f); p(0.15f, 1.6f); p(0f, 1.45f); p(0f, 1.45f); p(-0.15f, 1.6f); p(-0.15f, 1.6f); p(0f, 1.75f)
            p(0f, 0.85f); p(0f, 1.43f)
            p(0f, 1.4f); p(-0.34f, 1.02f)
            val ac = cos(refArm); val `as` = sin(refArm)
            p(0f, 1.4f); p(0.34f * ac + 0.38f * `as`, 1.4f + 0.34f * `as` - 0.38f * ac)
            p(0f, 0.85f); p(-0.14f, 0f); p(0f, 0.85f); p(0.14f, 0f)
        }
    }

    // ------------------------------------------------------------------ the plate

    private var hr = 1f; private var hg = 1f; private var hb = 1f; private var ha = 1f
    private fun hl(x0: Float, y0: Float, x1: Float, y1: Float) {
        val j = fight.stunJitterT
        val jx = if (j > 0f) (rnd.nextFloat() - 0.5f) * 8f else 0f; val jy = if (j > 0f) (rnd.nextFloat() - 0.5f) * 8f else 0f
        hudBatch.v(x0 + jx + fight.kickX, y0 + jy + fight.kickY, 0f, hr, hg, hb, ha); hudBatch.v(x1 + jx + fight.kickX, y1 + jy + fight.kickY, 0f, hr, hg, hb, ha)
    }
    private val sink = object : StrokeFont.LineSink { override fun line(x0: Float, y0: Float, x1: Float, y1: Float) = hl(x0, y0, x1, y1) }
    private fun color(t: FloatArray, a: Float = 1f) { hr = t[0]; hg = t[1]; hb = t[2]; ha = a }
    private fun text(s: String, x: Float, y: Float, sc: Float) = StrokeFont.draw(s, x, y, sc, sink)
    private val projOutHud = FloatArray(2)

    /**
     * FILL THE SNAPSHOT. Everything `Hud` draws comes from here and nothing here reaches back into
     * the fight; the model is re-used between frames so a 60 Hz plate allocates as close to nothing
     * as a Kotlin object graph allows.
     */
    private fun fillModel(dt: Float, headOn: Boolean) {
        val f = fight; val b = f.boxer; val m = model
        m.phase = when {
            f.creditsOpen -> Hud.Phase.CREDITS
            f.state == State.TITLE -> Hud.Phase.TITLE
            f.state == State.INTRO -> Hud.Phase.INTRO
            f.state == State.ROUND_CARD -> Hud.Phase.ROUND_CARD
            f.state == State.FIGHT -> Hud.Phase.FIGHT
            f.state == State.KNOCKDOWN_COUNT -> Hud.Phase.COUNT
            f.state == State.ROUND_END -> Hud.Phase.CORNER
            f.state == State.KO -> Hud.Phase.KO
            else -> Hud.Phase.GAME_OVER
        }
        m.phaseT = f.stateT; m.t = f.t
        m.menuOpen = f.menuOpen && !f.creditsOpen
        m.labOn = store.lab && f.state != State.TITLE
        m.tint = if (f.state == State.KO) ACID else MAGENTA
        m.rate = f.clock.timeScale; m.floor = f.clock.floor; m.halfFlash = f.clock.halfFlash; m.still = f.clock.still
        // The REFLEX rail drains through the STRIKE as well as the tell: under the owner's ruling
        // the glove in flight travels on world time and the hang and the fuse go on burning
        // (Boxer.update), so the read the rail measures includes the glove.
        m.reflex = if (b.phase == Boxer.Phase.TELL || b.phase == Boxer.Phase.STRIKE) ((b.hangLeft + b.fuseLeft) / (b.hangT + Boxer.FUSE_T)).coerceIn(0f, 1f) else -1f
        m.hisHp = hisHpShown; m.hisKd = b.knockdownsRound
        m.yourName = if (store.champion) "CHAMPION" else "YOU"
        m.yourHp = f.hp / Fight.HP_MAX.toFloat(); m.yourKd = f.knockdownsYouRound
        m.roundClock = f.clockText(f.roundClock); m.roundLine = "ROUND ${f.round} OF ${Fight.ROUNDS}"; m.realLine = "REAL " + f.clockText(f.fightRealT)
        m.clockPulse = f.state == State.FIGHT && f.roundClock <= Fight.CLAPPER_FROM_S
        m.score = f.score; m.high = store.highScore; m.newHigh = f.newHigh; m.multiplier = f.multiplier
        m.meter = f.meterShown; m.meterLit = f.meterLit
        // the gloves
        val p = f.punch
        m.punchHand = p?.hand; m.punchK = p?.k ?: 0f
        m.gloveL = gloveState(Hand.LEFT); m.gloveR = gloveState(Hand.RIGHT)
        m.armedHand = if (f.state == State.FIGHT && !f.leftPadEnabled && p == null) f.nextHand else null
        m.punchTargetOn = false
        strips?.let { set ->
            val mk = if (p != null && !p.special && p.level == Level.BODY) mBody else mChin
            if (set.markerWorld(b.strip.frame, mk, place, markerOut) && project(markerOut[0], markerOut[1], markerOut[2], projOutHud)) {
                m.punchTargetX = projOutHud[0]; m.punchTargetY = projOutHud[1]; m.punchTargetOn = true
            }
            // the impact's own marker (the onomatopoeia and the starburst sit on it, not on the next punch's target)
            if (f.impactT > 0f || f.starburstT > 0f) {
                val im = set.marker(f.impactMarker.ifEmpty { "chin" })
                if (set.markerWorld(b.strip.frame, im, place, markerOut) && project(markerOut[0], markerOut[1], markerOut[2], projOutHud)) { m.impactX = projOutHud[0]; m.impactY = projOutHud[1] }
            }
        }
        if (!m.punchTargetOn) { m.punchTargetX = Hud.CX; m.punchTargetY = 200f }
        m.gloveFlashHand = if (f.gloveFlashT > 0f) f.gloveFlashHand else null
        m.hearts = f.hearts; m.winded = f.winded
        // words and FX
        m.answerWord = answerShown; m.answerPop = (answerPopT / Fight.ANSWER_POP_T).coerceIn(0f, 1f); m.answerDrop = (answerDropT / Fight.ANSWER_DROP_T).coerceIn(0f, 1f)
        m.feedback = f.feedback; m.feedbackT = f.feedbackT; m.priorityText = f.priorityText
        m.impactWord = f.impactWord; m.impactT = f.impactT
        m.starburstT = f.starburstT
        m.brackets = ((0.15f - f.clock.timeScale) / 0.15f).coerceIn(0f, 1f)
        m.damageFlash = f.damageFlash; m.stunJitter = f.stunJitterT; m.kickX = f.kickX; m.kickY = f.kickY
        m.countN = f.countN; m.countPop = f.countPopT; m.countYou = f.downWho == Who.YOU
        m.roundCard = "ROUND ${f.round}"; m.roundCardName = f.roundName
        m.captionTag = f.captionTag; m.captionLine = f.captionLine
        m.tally = f.tally; m.noDecision = f.noDecision
        m.continueLeft = if (f.state == State.GAME_OVER) f.continueLeft else 0f
        m.headOn = headOn
        m.bestKo = if (store.bestKoMs > 0) f.clockText(store.bestKoMs / 1000f) else ""
        if (f.menuOpen) {
            m.menuItems = f.menuItems
            menuValues.clear()
            for (i in m.menuItems.indices) menuValues.add(f.menuValue(i))
            m.menuValues = menuValues; m.menuSel = f.menuSel; m.menuTop = f.menuTop
        }
        m.creditsLines = f.creditsLines; m.creditsScroll = f.creditsScroll
        labT += dt
        if (m.labOn && labT >= 0.1f) { labT = 0f; fillLab() }
        m.labRows = labRows
    }

    private fun gloveState(h: Hand): Hud.Glove {
        val f = fight; val p = f.punch
        return when {
            p != null && p.special -> Hud.Glove.SPECIAL
            p != null && p.hand == h -> Hud.Glove.PUNCH
            f.guardUp -> Hud.Glove.GUARD
            f.body.aimLow -> Hud.Glove.AIM_LOW
            else -> Hud.Glove.REST
        }
    }

    /** THE MOTION LAB's rows (DESIGN.md §8) — every body signal and the fight's own numbers, so a standing test is a reading. */
    private fun fillLab() {
        val f = fight; val mo = motion; val c = f.clock; val b = f.boxer
        val now = android.os.SystemClock.uptimeMillis()
        labRows.clear()
        labRows.add("MOTION" to "%.2f".format(Locale.US, c.motion))
        labRows.add("HEAD" to "%.2f R/S".format(Locale.US, mo.angSpeed))
        labRows.add("BODY" to "%.2f M/S2".format(Locale.US, mo.accelMag))
        labRows.add("ACT" to "%.2f".format(Locale.US, c.act))
        labRows.add("RATE" to "%.2f".format(Locale.US, c.timeScale))
        labRows.add("FLOOR" to "${(c.floor * 100f).toInt()} PCT")
        labRows.add("BLANK" to "${mo.blankLeft}")
        labRows.add("KNEE" to "${c.knee.name}  W ${"%.1f".format(Locale.US, c.wRef)}")
        labRows.add("LEAN X" to "%+.2f".format(Locale.US, f.body.leanX))
        labRows.add("DUCK" to "%.2f".format(Locale.US, f.body.duckAmt))
        labRows.add("AIM" to if (f.body.aimLow) "BODY" else "HEAD")
        labRows.add("PAD L" to "${(now - f.lastLeftLiftMs).coerceAtMost(9999)}")
        labRows.add("PAD R" to "${(now - f.lastRightLiftMs).coerceAtMost(9999)}")
        labRows.add("PAIR" to "${f.lastPairMs}")
        labRows.add("HANG" to "%.2f".format(Locale.US, b.hangLeft))
        labRows.add("FUSE" to "%.2f".format(Locale.US, b.fuseLeft))
        labRows.add("STRIP" to "${b.strip.name}:${b.strip.local}")
        labRows.add("FORCED" to c.forced.name)
        labRows.add("HP" to "${f.hp} / ${b.hp}")
        labRows.add("METER" to "${f.meter}")
        labRows.add("HEARTS" to "${f.hearts}")
        labRows.add("ROLL" to "%+.0f  PITCH %+.0f".format(Locale.US, mo.roll * 57.3f, mo.pitchG * 57.3f))
        labRows.add("STEPS" to "L${mo.stepsL} R${mo.stepsR} F${mo.stepsF} B${mo.stepsB}")
        // the activity's texts carry their own `PAD ` / `KEY ` label; the row key already says it
        if (f.lastPadText.isNotEmpty()) labRows.add("PAD" to f.lastPadText.removePrefix("PAD ").trimStart())
        if (f.lastKeyText.isNotEmpty()) labRows.add("KEY" to f.lastKeyText.removePrefix("KEY ").trimStart())
    }

    /** Drawn ON TOP of the plate: the lab's posture dial, which needs the tracker rather than the snapshot. */
    private fun overlays() {
        if (store.lab && fight.state != State.TITLE && !fight.menuOpen) postureDial()
    }

    /**
     * THE POSTURE DIAL sits LEFT, beside the pulse rail and above its `STILL` and the corner
     * caption, at `(110, 300)`. It lived at `(500, 360)` and the lab plate's rows ran straight
     * through it (the 26-row column from y 96 reaches 435); on the left there is nothing between
     * the rail's cross at y 240 and `STILL` at y 360 but room, the answer word at x 220–420 never
     * reaches x 172, and the left glove's circle tops out at y 366.
     */
    private fun postureDial() {
        val px = DIAL_X; val py = DIAL_Y
        color(ACID, 0.4f); circleHud(px, py, 22f, 20)
        val r = motion.roll - motion.restRoll
        color(GOLD, 0.9f); hl(px - sin(r) * 20f, py - cos(r) * 20f, px + sin(r) * 20f, py + cos(r) * 20f)
        val pd = ((motion.pitchG - motion.restPitch) / 0.6f).coerceIn(-1f, 1f)
        color(WHITE, 0.7f); hl(px + 28f, py - pd * 20f, px + 38f, py - pd * 20f)
        color(ACID, 0.35f); hl(px + 33f, py - 20f, px + 33f, py + 20f)
        if (fight.lastStepAge < 0.5f) {
            color(GOLD, 1f - fight.lastStepAge * 2f)
            if (fight.lastStep < 0) { hl(px - 48f, py, px - 32f, py - 8f); hl(px - 48f, py, px - 32f, py + 8f) }
            else { hl(px + 62f, py, px + 46f, py - 8f); hl(px + 62f, py, px + 46f, py + 8f) }
        }
        color(ACID, 0.45f); text("POSTURE", px - 20f, py + 40f, 1.1f)
    }

    private fun circleHud(cx: Float, cy: Float, rad: Float, n: Int) {
        for (i in 0 until n) {
            val a0 = i * 2f * PI.toFloat() / n; val a1 = (i + 1) * 2f * PI.toFloat() / n
            hl(cx + cos(a0) * rad, cy + sin(a0) * rad, cx + cos(a1) * rad, cy + sin(a1) * rad)
        }
    }

    // ------------------------------------------------------------------ GL plumbing

    /** The stream batch: filled every frame, one `glBufferData(STREAM_DRAW)`. */
    private inner class Batch(cap: Int) {
        private val buf: FloatBuffer = ByteBuffer.allocateDirect(cap * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private var n = 0
        private val max = cap
        private var vbo = 0
        private var live = false
        val count get() = n
        fun contextLost() { vbo = 0; live = false }
        fun reset() { buf.clear(); n = 0; live = false }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (n >= max) return
            buf.put(x); buf.put(y); buf.put(z); buf.put(r); buf.put(g); buf.put(b); buf.put(a); n++
        }
        fun draw(mode: Int) {
            if (n == 0) return
            if (vbo == 0) { val id = IntArray(1); GLES30.glGenBuffers(1, id, 0); vbo = id[0] }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            if (!live) { buf.position(0); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 28, buf, GLES30.GL_STREAM_DRAW); live = true }
            bindAndDraw(mode, n)
        }
    }

    /**
     * A static group (§12.5): uploaded once with `STATIC_DRAW` after [upload], drawn every frame
     * with the sway uniform. The crowd's wave and the ropes' shake cost the CPU nothing.
     */
    private inner class StaticBatch(cap: Int) {
        private val buf: FloatBuffer = ByteBuffer.allocateDirect(cap * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private var n = 0
        private val max = cap
        private var vbo = 0
        private var uploaded = false
        val count get() = n
        fun contextLost() { vbo = 0; uploaded = false }
        fun reset() { buf.clear(); n = 0; uploaded = false }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (n >= max) return
            buf.put(x); buf.put(y); buf.put(z); buf.put(r); buf.put(g); buf.put(b); buf.put(a); n++
        }
        fun upload() {
            if (n == 0) return
            if (vbo == 0) { val id = IntArray(1); GLES30.glGenBuffers(1, id, 0); vbo = id[0] }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            buf.position(0); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 28, buf, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            uploaded = true
        }
        fun draw() {
            if (n == 0) return
            if (!uploaded) upload()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            bindAndDraw(GLES30.GL_LINES, n)
        }
    }

    private fun bindAndDraw(mode: Int, n: Int) {
        GLES30.glEnableVertexAttribArray(aPos); GLES30.glEnableVertexAttribArray(aColor)
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, 0)
        GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, 12)
        GLES30.glDrawArrays(mode, 0, n)
        GLES30.glDisableVertexAttribArray(aPos); GLES30.glDisableVertexAttribArray(aColor)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val TAG = "X3Knockout"

        /** The hatch gains (§7.3): skin at 0.35, the colour blocks (gloves, trunks) at 0.55, both × √(2.6 / d). */
        const val HATCH_SKIN = 0.35f
        const val HATCH_BLOCK = 0.55f
        /** DETAIL parts' LOD hysteresis (§12.7): off beyond 3.4 m, on inside 3.0 m. */
        const val DETAIL_FAR = 3.4f
        const val DETAIL_NEAR = 3.0f
        /** The telegraph flash ramps in over 30 ms real; the impact whites hold two frames (§2.5, §7.4). */
        const val FLASH_RAMP_T = 0.03f
        const val FLASH_FRAMES = 2
        /** The extend halo's line width, the one width bump in the game, spent on two frames. */
        const val EXTEND_WIDTH = 6f
        /** The crest's full droop, radians: a spike at 90° at full health sags to 40° at none (BOXER.md §4). */
        const val DROOP_MAX = 0.873f
        /** The hit snap of his head away from the glove, radians, relaxing on world time. */
        const val HEAD_SNAP = 0.22f
        /** The trails: four samples a glove, 1/30 world second apart. */
        const val TRAIL_N = 4
        const val TRAIL_DT = 1f / 30f
        const val SPARKS = 8
        const val WHOOSH_T = 0.3f
        /** The ropes' shake after a knockdown: a 0.4 s decaying impulse on the ring's sway. */
        const val RING_SHAKE_T = 0.4f
        /** Your knockdown sinks the eye this far (§5.2). */
        const val YOU_DOWN_SINK = 0.40f
        /** The crest buffer: five spikes' segments held back for the droop pass. */
        private const val CREST_CAP = 320
        /** The referee's marks: his post between rounds, beside the fallen man for the count. */
        const val REF_HOME_X = -2.55f
        const val REF_HOME_Z = -5.15f
        const val REF_CENTRE_X = 1.15f
        const val REF_CENTRE_Z = -3.15f
        /** The posture dial's home on the plate (see [postureDial]). */
        const val DIAL_X = 110f
        const val DIAL_Y = 300f
        /** A trail segment shorter than this (3 cm) is the idle sway, not a punch, and is not a speed line. */
        const val TRAIL_MIN_M2 = 0.0009f

        /**
         * `uSway = (amp, freq, phaseScale, floorY)`: the vertex shader adds `amp · sin(freq · uT +
         * x · phaseScale) · max(0, y − floorY)` to x — the crowd's wave (a phase across x so it
         * reads as a wave, floor-clamped so feet stay planted) and the ropes' shake are the same
         * four floats; the stream batches are drawn with zeros (§12.5). `uT` is WORLD time: the
         * crowd's bob swings with the clock (§2.9). `uBounce = (amp, zNear)` with `uT2` is the
         * ovation — the front row (z ≥ zNear) bouncing on REAL time during the count and the KO,
         * when the world is stopped and the crowd, like the referee, is outside the bubble.
         */
        private const val VERT = """#version 300 es
            uniform mat4 uMVP; uniform float uPointSize; uniform float uAlpha; uniform vec4 uSway; uniform float uT;
            uniform vec2 uBounce; uniform float uT2;
            in vec3 aPos; in vec4 aColor; out vec4 vColor;
            void main() {
                vec3 p = aPos;
                p.x += uSway.x * sin(uSway.y * uT + p.x * uSway.z) * max(0.0, p.y - uSway.w);
                p.y += uBounce.x * abs(sin(3.4 * uT2 + p.x * 0.9)) * step(uBounce.y, p.z);
                gl_Position = uMVP * vec4(p, 1.0); gl_PointSize = uPointSize; vColor = vec4(aColor.rgb, aColor.a * uAlpha);
            }"""
        private const val FRAG = """#version 300 es
            precision mediump float;
            uniform float uPoint; in vec4 vColor; out vec4 fragColor;
            void main() {
                float a = vColor.a;
                if (uPoint > 0.5) { vec2 d = gl_PointCoord - vec2(0.5); float r = length(d) * 2.0; a *= smoothstep(1.0, 0.2, r); }
                // NOT PREMULTIPLIED. The blend is (SRC_ALPHA, ONE), so the pipeline ALREADY
                // multiplies this colour by its own alpha; emitting rgb*a here multiplied it a
                // second time and every stroke in the game was drawn at alpha SQUARED. A stroke
                // authored at 0.35 reached the glass at 0.12 — nearly three times too dim — and
                // only full-alpha strokes were ever correct, which is exactly why bright cores
                // looked right while everything meant to sit behind them read as murk. On a
                // waveguide, where the picture IS its own light, that is the difference between
                // the owner's "vibrant, saturated" and the washed grey he rejects.
                //
                // Alpha above 1 still works as the white-hot core: the fragment's alpha clamps to
                // 1 on a fixed-point target, so a hot stroke simply saturates rather than being
                // scaled down — the same behaviour as before, since that path never went through
                // the squaring in a way you could see.
                fragColor = vec4(vColor.rgb, a);
            }"""
    }
}
