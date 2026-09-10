package com.x3knockout.head

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import com.x3knockout.engine.Clock
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import java.io.File
import java.io.FileWriter

/**
 * THE BODY, AS THE GLASSES CAN FEEL IT.
 *
 * SUPERHOT's law is "time moves only when you move", and on a 6-DoF headset "move" is the head's
 * position. These glasses have no position: they have a gyroscope, an accelerometer and the
 * gravity vector, all in the DEVICE frame. So this class turns those into the handful of body
 * signals the game is built on, and nothing else:
 *
 *  - [motion]     0..1, how much the player is moving RIGHT NOW — head turning speed and body
 *                 acceleration, whichever is larger, smoothed with a fast attack and a slow release
 *                 so a single jerk cannot buy a whole second of time. This drives the clock.
 *  - [roll]       radians, head tilt about the look axis: + = the top of the head goes RIGHT.
 *                 A LEAN. Read from gravity, so it is drift-free.
 *  - [pitchG]     radians from gravity, + = looking up. A DUCK is a fast drop in this. (The game
 *                 rotation vector also carries pitch; gravity is the one that cannot drift.)
 *  - [lateral]    m/s², body acceleration to the RIGHT, gravity removed, in the head's own frame.
 *  - [fore]       m/s², body acceleration FORWARD along the look axis.
 *  - [vertical]   m/s², UP.
 *  - [step]       -1 / 0 / +1 for one frame when a SIDESTEP was recognised (left / right), and
 *                 [stepFore] likewise for forward / back. A step is the body's leaky velocity
 *                 estimate crossing [V_STEP] while the head is NOT rolling — see the note on
 *                 [V_STEP] — with hysteresis and a refractory period so one step reads as one.
 *
 * DEVICE FRAME OF THE X3 PRO, measured on the glasses: +X is the wearer's RIGHT, +Y is UP, +Z is
 * FORWARD along the look axis (this is why HeadTracker's remap(AXIS_X, AXIS_Z) works — it makes
 * the device's Z the "screen up" that getOrientation treats as the heading). So the linear
 * acceleration's own components ARE lateral / vertical / fore, as long as the head is roughly
 * upright, which a standing player's is. No rotation into a world frame is needed, and none is
 * done, because a game whose "forward" IS the head has no use for world east.
 *
 * Every signal is sampled on a sensor thread and SMOOTHED ON THE GL THREAD in [update], like
 * HeadTracker, so the game reads one consistent set of numbers per frame.
 */
class MotionTracker(ctx: Context) : SensorEventListener {

    companion object {
        /**
         * Head turning speed that counts as "fully moving", and the knee that shapes what a
         * smaller one costs. SET B SHIPS (1.9 rad/s ≈ 109°/s, γ 1.8): MOTION.md measured the
         * owner's own recorded fight and an ordinary scan at 42.6°/s costs 0.20 under B against
         * 0.53 under A, and being taxed for reading the room is the one thing this design exists
         * to prevent (DESIGN.md §1.1, commit `7dcb8de`).
         *
         * Both are `var` because `Clock` owns the decision and `Game.applySettings` copies its
         * `wRef` / `gamma` in here every time the lab's `KNEE` row moves. If they were `const`
         * the shipped rate would run one curve while the `CLK` line's `knee=` and `Clock.target`
         * reported the other, and test A2 — the test the whole design is judged on — would
         * silently measure the wrong thing.
         */
        var W_REF = 1.9f
        /** Body acceleration that counts as "fully moving". A brisk sidestep peaks at 3-5 m/s². */
        var A_REF = 2.2f
        /** See `Clock.omegaEff`: yaw is priced at `Clock.K_YAW`, pitch and roll in full. */
        /**
         * Below this the clock is at its floor: rest noise and breathing must NOT move time.
         * Raised from 0.08 with [K_YAW]: the same band now has to swallow a tracking gait's
         * residue as well as breathing, and every gait on the card lands under it.
         */
        private const val DEAD_W = 0.12f
        private const val DEAD_A = 0.25f
        /** Attack and release of [motion], seconds. Fast in, slow out: motion is spent, not banked. */
        private const val ATTACK = 0.04f
        private const val RELEASE = 0.16f
        /** Shape of the motion curve: >1 makes small motions buy less time than large ones. See [W_REF]. */
        var GAMMA = 1.8f

        /**
         * A STEP IS A VELOCITY, NOT A JOLT. The first standing test asked the owner to step slowly
         * and the acceleration-pulse detector this replaced never fired once: a careful sidestep
         * peaks well under the 1.6 m/s² it wanted, while a lean's gravity leakage could exceed it.
         * So the body's acceleration is integrated into a LEAKY VELOCITY ([vLat], [vFore]) and a
         * step is that velocity crossing [V_STEP] — a 50 cm sidestep taken in a full second still
         * reaches ~0.4 m/s, and a jolt that goes nowhere integrates to nothing.
         */
        var V_STEP = 0.32f
        /** How fast the velocity estimate forgets, seconds: the price of integrating without position. */
        private const val V_LEAK = 0.6f
        /** A head that is ROLLING this fast is leaning, and its lateral acceleration is gravity leaking, not a step. */
        private const val ROLL_GATE = 0.9f
        /** ...and a head TURNING this fast swings the glasses sideways on the neck. The guided run's
         *  "turn your head" phase produced a clean false sidestep at 3.2 rad/s. */
        private const val YAW_GATE = 1.0f
        /** The look-back for the gates, frames at 60 Hz: ~400 ms. */
        private const val WIN = 24
        /** A step needs a real push: the lateral acceleration must have peaked at least this high inside the window. */
        private const val STEP_PUSH = 1.2f
        /** ...and the head must not have tilted more than this across the window, or it was a lean. */
        private const val ROLL_WIN = 0.14f
        /** A head HELD this far off its rest roll is in a lean, whatever its rate: no steps. Large
         *  tilts also leak gravity into the "linear" acceleration, which integrates into a velocity
         *  that looks exactly like a slow step — the guided run caught one at 39 degrees. */
        private const val LEAN_HOLD = 0.35f
        /**
         * THE DUCK MOVES THE HEAD FORWARD, and on the owner's third standing test one nod produced
         * three phantom steps: forward on the way down, back on the recovery, forward again. The
         * head arcs about the neck and the shoulders, so a nod of rate p carries the glasses along
         * the look axis, and a head that is nodding at all cannot be stepping: the same windowed
         * gate as the tilt, on the other axis.
         */
        private const val PITCH_GATE = 0.7f
        private const val PITCH_WIN = 0.14f
        private const val DUCK_HOLD = 0.30f
        /**
         * PAD BLANKING. A tap on the temple is a mechanical impulse on a 70 g frame and the
         * accelerometer reports it faithfully — as a push. So for this long after any pad event
         * the step verdict is withheld (the rings keep filling; only the decision waits). It runs
         * in the permitted direction: the always-present touch stream gates the accelerometer,
         * never the reverse.
         */
        private const val PAD_BLANK_MS = 150L
        /** One step at a time: nothing else is recognised for this long after one lands. */
        private const val STEP_REFRACTORY_MS = 550L
    }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val lin: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val grav: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private var thread: HandlerThread? = null
    var running = false; private set
    /**
     * THE RECORDER. Every 50 ms of play the raw samples go to a file in the app's cache, because
     * logcat's ring buffer is a hundred seconds deep at this rate and a standing test is longer
     * than that. Pulled with `adb shell run-as com.x3knockout cat cache/motion.log`.
     */
    private var logFile: File? = null
    private var logWriter: FileWriter? = null
    private var logHandler: Handler? = null
    private var logT = 0f
    fun logTo(f: File?) { logFile = f }
    /** What the wearer was asked to do when this sample was taken — the guide's phase name. */
    @Volatile var tag = ""
    /** The roll the body rests at, set at the coin. A head held well off it is LEANING, and a lean is not a step. */
    @Volatile var restRoll = 0f
    /** The pitch the head rests at, set at the coin — a head held well below it is DUCKING, not stepping. */
    @Volatile var restPitch = 0f
    /** When the temple pad was last touched or pressed — see [PAD_BLANK_MS]. */
    @Volatile private var padAtMs = -10_000L
    fun padEvent() { padAtMs = SystemClock.uptimeMillis() }
    /** Milliseconds of blanking left, for the lab plate. */
    val blankLeft: Long get() = (PAD_BLANK_MS - (SystemClock.uptimeMillis() - padAtMs)).coerceAtLeast(0L)
    val available get() = gyro != null && lin != null && grav != null

    // raw, written on the sensor thread
    @Volatile private var rawW = 0f
    /** [rawW] with the yaw axis weighted by [K_YAW]: the only angular rate the clock is charged. */
    @Volatile private var rawWEff = 0f
    @Volatile private var rawWx = 0f; @Volatile private var rawWy = 0f; @Volatile private var rawWz = 0f
    @Volatile private var rawAx = 0f; @Volatile private var rawAy = 0f; @Volatile private var rawAz = 0f
    @Volatile private var rawGx = 0f; @Volatile private var rawGy = 9.81f; @Volatile private var rawGz = 0f
    @Volatile var hasData = false; private set

    // smoothed, read on the GL thread
    var motion = 0f; private set
    var angSpeed = 0f; private set
    var accelMag = 0f; private set
    var lateral = 0f; private set
    var fore = 0f; private set
    var vertical = 0f; private set
    var roll = 0f; private set
    var pitchG = 0f; private set
    /**
     * A DEMO HOOK, AND NOTHING ELSE. There is no path anywhere in this app to synthesise a duck
     * or a slip without a real head in the glasses — no replay, no fake-sensor HAL — so capturing
     * a feature reel off the desk had no way to show them at all. `MainActivity` sets these from
     * a debug-only broadcast (`ACTION_DEBUG_MOTION`); both null is the entire non-debug behaviour.
     */
    @Volatile var debugRoll: Float? = null
    @Volatile var debugPitch: Float? = null
    /** Peak signals since the last [resetPeaks] — for the diagnostic plate and for tuning. */
    var peakW = 0f; private set
    var peakA = 0f; private set

    /** Body velocity estimates, m/s, leaky: right, forward, up. */
    var vLat = 0f; private set
    var vFore = 0f; private set
    var vVert = 0f; private set
    /** Angular rates in the head frame, rad/s: roll about the look axis, pitch about the ear axis. */
    var rollRate = 0f; private set
    var pitchRate = 0f; private set
    var yawRate = 0f; private set
    // step recogniser state (GL thread)
    var step = 0; private set
    var stepFore = 0; private set
    /** The velocity must fall back under half the threshold before the same axis can step again. */
    private var latArmed = true; private var foreArmed = true
    private var lastStepMs = -10_000L
    /** Rings over the last [WIN] frames: roll, |yaw rate|, |lateral accel|. Gates look at a window, not an instant. */
    private val rollRing = FloatArray(WIN); private val yawRing = FloatArray(WIN)
    private val pitchRing = FloatArray(WIN)
    /** Signed peaks: a step must be pushed in the direction it is going, or the brake that ends one
     *  step gets counted as the push that starts the next — which is what credited a return step to
     *  its own outbound shove on the third standing test. */
    private val axPosRing = FloatArray(WIN); private val axNegRing = FloatArray(WIN)
    private val azPosRing = FloatArray(WIN); private val azNegRing = FloatArray(WIN)
    private var ringI = 0
    /** Diagnostic: how many steps have been recognised, per axis, since start. */
    var stepsL = 0; private set
    var stepsR = 0; private set
    var stepsF = 0; private set
    var stepsB = 0; private set

    fun start() {
        if (running || !available) return
        thread = HandlerThread("x3knockout-motion").also { it.start() }
        val h = Handler(thread!!.looper)
        // 10 ms: the gyro runs to 416 Hz and the fused sensors to 100; the fastest they will give.
        sm.registerListener(this, gyro, 10_000, h)
        sm.registerListener(this, lin, 10_000, h)
        sm.registerListener(this, grav, 20_000, h)
        running = true; hasData = false
        motion = 0f; step = 0; stepFore = 0
        logHandler = Handler(thread!!.looper)
        logFile?.let { f -> logHandler?.post { runCatching { logWriter = FileWriter(f, false) } } }
    }

    fun stop() {
        if (!running) return
        logHandler?.post { runCatching { logWriter?.close() }; logWriter = null }
        sm.unregisterListener(this)
        thread?.quitSafely(); thread = null
        running = false; hasData = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                rawWx = x; rawWy = y; rawWz = z
                rawW = sqrt(x * x + y * y + z * z)
                // The clock's own magnitude, with the look priced down (see [K_YAW]). The raw one
                // above is kept for the telemetry, the step detector and the rails, all of which
                // want the head's real speed and not what it is being charged for.
                rawWEff = Clock.omegaEff(x, y, z)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> { rawAx = e.values[0]; rawAy = e.values[1]; rawAz = e.values[2]; hasData = true }
            Sensor.TYPE_GRAVITY -> { rawGx = e.values[0]; rawGy = e.values[1]; rawGz = e.values[2] }
        }
    }

    fun resetPeaks() { peakW = 0f; peakA = 0f }

    /** GL-thread smoothing and recognition. Call once per frame, BEFORE the game reads anything. */
    fun update(dt: Float) {
        step = 0; stepFore = 0
        if (!running || !hasData) return
        logT += dt
        if (logT >= 0.05f && logWriter != null) {
            logT = 0f
            val line = "%d %s\n".format(SystemClock.uptimeMillis(), raw())
            logHandler?.post { runCatching { logWriter?.write(line) } }
        }
        val ax = rawAx; val ay = rawAy; val az = rawAz
        val w = rawW
        val wEff = rawWEff
        val a = sqrt(ax * ax + ay * ay + az * az)
        peakW = max(peakW, w); peakA = max(peakA, a)

        // the clock's input: the larger of the two, past its dead band, normalised and shaped
        val mw = ((wEff - DEAD_W) / (W_REF - DEAD_W)).coerceIn(0f, 1f)
        val ma = ((a - DEAD_A) / (A_REF - DEAD_A)).coerceIn(0f, 1f)
        val target = max(mw, ma).pow(GAMMA)
        val tau = if (target > motion) ATTACK else RELEASE
        motion += (target - motion) * (1f - exp(-dt / tau))
        if (motion < 1e-4f) motion = 0f

        // readouts, lightly smoothed
        val k = 1f - exp(-dt / 0.05f)
        angSpeed += (w - angSpeed) * k
        accelMag += (a - accelMag) * k
        lateral += (ax - lateral) * k
        vertical += (ay - vertical) * k
        fore += (az - fore) * k

        // posture from gravity: drift-free by construction
        val gx = rawGx; val gy = rawGy; val gz = rawGz
        val r = atan2(-gx, gy)                 // head's top going right tips "up" toward -x
        // + = looking UP. Nodding down tips "up" toward the look axis, so gz grows: hence the minus.
        // (Confirmed on the glasses: the NOD DOWN phase of the guided run read +52 before this sign.)
        val p = -atan2(gz, sqrt(gx * gx + gy * gy))
        val kp = 1f - exp(-dt / 0.06f)
        roll += (r - roll) * kp
        pitchG += (p - pitchG) * kp
        // DEBUG-ONLY MOTION OVERRIDE — for capturing footage off the desk, where nobody's head is
        // in the loop to produce a duck or a slip. Null in every ordinary run, so this line costs
        // nothing; MainActivity is the only writer, and only when it decides the build is
        // debuggable. Applied AFTER the real smoothing above rather than replacing rawG*, so a
        // demo can still be interrupted by a real head movement mid-capture.
        debugRoll?.let { roll = it }
        debugPitch?.let { pitchG = it }

        // angular rates in the head frame: device Z is the look axis, X the ear axis, Y the neck.
        // The gyro's z is the NEGATIVE of d(roll)/dt in this class's convention — measured on the
        // guided run (slope -0.88 over 56 samples) — so it is flipped here to match [roll].
        rollRate = -rawWz; pitchRate = rawWx; yawRate = rawWy

        // the leaky velocity: integrate the RAW acceleration, forget on V_LEAK
        val leak = exp(-dt / V_LEAK)
        vLat = (vLat + ax * dt) * leak
        vFore = (vFore + az * dt) * leak
        vVert = (vVert + ay * dt) * leak

        // steps: a velocity crossing, with hysteresis, a refractory period, and a lean gate
        val now = SystemClock.uptimeMillis()
        // THE GATES LOOK BACK 400 MS. An instantaneous rate gate let two false steps through on the
        // guided run: the head's swing had already slowed below the gate while the velocity it had
        // integrated was still above the step threshold. So: was the head turning fast, or tilting
        // by more than a few degrees, at ANY point in the window? And was there a real push?
        rollRing[ringI] = r; pitchRing[ringI] = p; yawRing[ringI] = abs(rawWy)
        axPosRing[ringI] = max(0f, ax); axNegRing[ringI] = max(0f, -ax)
        azPosRing[ringI] = max(0f, az); azNegRing[ringI] = max(0f, -az)
        ringI = (ringI + 1) % WIN
        var rMin = 9f; var rMax = -9f; var pMin = 9f; var pMax = -9f
        var yawPeak = 0f; var axPos = 0f; var axNeg = 0f; var azPos = 0f; var azNeg = 0f
        for (i in 0 until WIN) {
            val rv = rollRing[i]; if (rv < rMin) rMin = rv; if (rv > rMax) rMax = rv
            val pv = pitchRing[i]; if (pv < pMin) pMin = pv; if (pv > pMax) pMax = pv
            if (yawRing[i] > yawPeak) yawPeak = yawRing[i]
            if (axPosRing[i] > axPos) axPos = axPosRing[i]; if (axNegRing[i] > axNeg) axNeg = axNegRing[i]
            if (azPosRing[i] > azPos) azPos = azPosRing[i]; if (azNegRing[i] > azNeg) azNeg = azNegRing[i]
        }
        val leaning = abs(rollRate) > ROLL_GATE || (rMax - rMin) > ROLL_WIN || abs(r - restRoll) > LEAN_HOLD
        val nodding = abs(pitchRate) > PITCH_GATE || (pMax - pMin) > PITCH_WIN || abs(p - restPitch) > DUCK_HOLD
        val turning = yawPeak > YAW_GATE
        val vStep = vLat
        if (abs(vStep) < V_STEP * 0.5f) latArmed = true
        if (abs(vFore) < V_STEP * 0.5f) foreArmed = true
        val blanked = now - padAtMs < PAD_BLANK_MS
        if (now - lastStepMs > STEP_REFRACTORY_MS && !leaning && !turning && !nodding && !blanked) {
            val latPush = if (vStep > 0f) axPos else axNeg
            val forePush = if (vFore > 0f) azPos else azNeg
            if (latArmed && latPush >= STEP_PUSH && abs(vStep) > V_STEP) {
                step = if (vStep > 0f) 1 else -1; latArmed = false; lastStepMs = now
                if (step < 0) stepsL++ else stepsR++
            } else if (foreArmed && forePush >= STEP_PUSH && abs(vFore) > V_STEP) {
                stepFore = if (vFore > 0f) 1 else -1; foreArmed = false; lastStepMs = now
                if (stepFore < 0) stepsB++ else stepsF++
            }
        }
    }

    /** The clock: how fast the world runs this frame, 0..1, given the design's floor. */
    fun timeScale(floor: Float): Float = floor + (1f - floor) * motion

    /** One line for logcat at a few Hz — the whole point of the first standing-up test. */
    fun trace(): String = "MOTION [$tag] m=%.2f w=%.2f a=%.2f lat=%+.2f fore=%+.2f vert=%+.2f vlat=%+.2f vfore=%+.2f roll=%+.0f pitchG=%+.0f rr=%+.2f steps L%d R%d F%d B%d peakW=%.2f peakA=%.2f".format(
        motion, angSpeed, accelMag, lateral, fore, vertical, vLat, vFore, roll * 57.2958f, pitchG * 57.2958f, rollRate, stepsL, stepsR, stepsF, stepsB, peakW, peakA)

    /** The raw samples, for a 20 Hz log: what the recogniser is actually looking at. */
    fun raw(): String = "RAW [%s] ax=%+.2f ay=%+.2f az=%+.2f wx=%+.2f wy=%+.2f wz=%+.2f vlat=%+.2f vfore=%+.2f roll=%+.0f pitchG=%+.0f m=%.2f".format(
        tag, rawAx, rawAy, rawAz, rawWx, rawWy, rawWz, vLat, vFore, roll * 57.2958f, pitchG * 57.2958f, motion)
}
