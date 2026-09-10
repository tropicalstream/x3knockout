package com.x3knockout.engine

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * POSE STRIPS — the boxer is a sprite, and a sprite is a list of drawings.
 *
 * The 1984 cabinet had no 3D: its artists drew every frame of every wind-up, every hit, every
 * fall by hand, and the board scaled ONE big sprite instead of moving a camera. This file is that
 * idea in GL clothing (DESIGN.md §7.3, §12.2). A [StripSet] is every frame of every strip the
 * Blender artist authored (`blender/assets/boxer.py`, 12 fps, CONSTANT interpolation, sampled by
 * `export_strips()` in `blender/export_strokes.py`), each frame a COMPLETE drawing — a wind-up
 * squashes the head, a hit stretches it, a mouth changes shape, a glove grows 1.8× as it comes at
 * you; shape changes that no per-part transform could make. A [StripPlayer] holds one strip and
 * advances it on WORLD time, so at the floor the telegraph frame hangs on the glass. Nothing is
 * blended: sprites cut, they do not tween, and switching strips mid-frame is legal and instant.
 *
 * WHY FRAMES HELD AT 12 fps DO NOT JUDDER: the camera is head-tracked at 60 Hz and the sprite's
 * placement — the billboard yaw, the idle sway, the stagger wobble, the hit squash — is a model
 * matrix evaluated every frame by the renderer (§7.3's secondary motion). Only the drawing inside
 * the sprite steps; the figure never reads as frozen on a held frame.
 *
 * THE FORMAT (DESIGN.md §12.6). Two files in `assets/models/`:
 *
 *  - `boxer.json`, the MANIFEST: `{"name", "fps": 12, "fwd": "-z", "parts": [{"name", "cls":
 *    outline|detail|hatch, "flash": bool, "pivot": [x,y,z], "color": [r,g,b]}, …], "markers":
 *    ["glove_L", …], "strips": [{"name", "first", "count", "loop", "events": {"strike": 6, …}},
 *    …], "frames": N}`. Part order is the tint table's order ([SpriteMaterial], 32 slots).
 *  - `boxer.x3s`, the FRAMES, little-endian binary: `"X3S1"`, `u32 nParts nMarkers nFrames`, then
 *    per frame `u32 segCount[nParts]`, `f32 × 6 × Σseg` (x0 y0 z0 x1 y1 z1, metres, in part
 *    order), `f32 × 3 × nMarkers`. ≈ 122 frames × ≈ 620 segments ≈ 1.8 MB; as JSON it would be
 *    six times that and a two-second parse. A frame over [StripSet.MAX_SEGS] fails the export.
 *
 * A manifest WITHOUT `strips` is a plain `StrokeModel` export (the same `parts` + `segs` file
 * `export()` has always written), and [StripSet.parse] turns it into a one-frame `idle` so the
 * first build — before a single strip is authored — still draws the figure. That is the path the
 * skeleton `boxer.py` exercises.
 *
 * THE FORWARD AXIS. The sprite is authored facing Blender +Y, which the exporter maps to engine
 * **−Z** (`"fwd": "-z"`, the convention `StrokeModel.kt`'s KDoc pins and `program.py` learned the
 * hard way). The renderer yaws the billboard to face the camera with [StripSet.headingTo], which
 * knows the axis, so a figure placed with the camera's own yaw convention is never mirrored. In
 * WORLD space after that placement the camera is at +Z of the boxer, so "nearer the camera" is
 * LARGER world Z — which is exactly what `StripSetTest` asserts about `glove_R` on `wing_r`'s
 * strike frame, through [StripSet.walkFrame] and the shipped asset, never through the Python.
 *
 * This file's Android surface is [StripSet.load] and the log; everything else is pure so the
 * tests (`StripSetTest`, `StripPlayerTest`) run on the JVM over the shipping files.
 */
class StripSet private constructor(
    val name: String,
    val parts: List<PartInfo>,
    val markers: List<String>,
    val strips: Map<String, Strip>,
    val fps: Int,
    /** +1 or −1: which model-space Z axis is the figure's front. −1 ships (see the class note). */
    val fwdZ: Int,
    val frameCount: Int,
    /** `[frame * nParts + part]` → segments of that part in that frame. */
    private val counts: IntArray,
    /** `[frame * nParts + part]` → index of the first of those segments in [segs]. */
    private val starts: IntArray,
    /** Six floats a segment: every frame, parts in order. */
    private val segs: FloatArray,
    /** `[(frame * nMarkers + marker) * 3]` → the marker's model-space position in that frame. */
    private val markerXyz: FloatArray,
) {
    /** What a part IS on the glass (DESIGN.md §7.3, §12.7): the outline never LODs, detail goes beyond 3.4 m, hatch never flashes. */
    enum class Cls { OUTLINE, DETAIL, HATCH }

    class PartInfo(val name: String, val cls: Cls, val flash: Boolean, val pivot: FloatArray, val color: FloatArray)

    /** One authored strip: [count] frames from [first], its loop flag and its named event frames (frame-local). */
    class Strip(val name: String, val first: Int, val count: Int, val loop: Boolean, val events: Map<String, Int>) {
        val last: Int get() = first + count - 1
        /** The local frame of a named event, or −1. BOXER.md §8 names them: telegraph, stamp, crow, strike, cut, done, flash, open, down, up, ko. */
        fun event(name: String): Int = events[name] ?: -1
        /** Seconds at rate 1, for the tables that check the authored durations against BOXER.md. */
        fun duration(fps: Int): Float = count.toFloat() / fps
    }

    val frameT: Float get() = 1f / fps
    val nParts: Int get() = parts.size
    val nMarkers: Int get() = markers.size

    fun part(name: String): Int = parts.indexOfFirst { it.name == name }
    fun marker(name: String): Int = markers.indexOf(name)
    fun strip(name: String): Strip? = strips[name]

    fun segCount(frame: Int, part: Int): Int =
        if (frame !in 0 until frameCount || part !in 0 until nParts) 0 else counts[frame * nParts + part]

    /** Every part's segments in [frame] — the per-frame budget the `fps=` line reports as `boxer=`. */
    fun frameSegCount(frame: Int): Int {
        if (frame !in 0 until frameCount) return 0
        var n = 0
        for (p in 0 until nParts) n += counts[frame * nParts + p]
        return n
    }

    /** The marker's MODEL-space position in [frame] into [out] (x, y, z); false if either index is out of range. */
    fun marker(frame: Int, index: Int, out: FloatArray): Boolean {
        if (frame !in 0 until frameCount || index !in 0 until nMarkers) return false
        val at = (frame * nMarkers + index) * 3
        out[0] = markerXyz[at]; out[1] = markerXyz[at + 1]; out[2] = markerXyz[at + 2]
        return true
    }

    /** The marker's WORLD position in [frame] under [place] — the arc's origin, the hit spark, your punch's target. */
    fun markerWorld(frame: Int, index: Int, place: StrokeModel.Place, out: FloatArray): Boolean {
        if (!marker(frame, index, out)) return false
        transform(place, out)
        return true
    }

    /**
     * The yaw that turns this figure's front toward `(tx, tz)` from `(px, tz)` — the billboard's
     * heading, in whichever axis convention the manifest declared. For the shipping `-z`:
     * `atan2(−(tx − px), −(tz − pz))`, the formula `StrokeModel.kt` pins; for `+z` the sign flips.
     */
    fun headingTo(px: Float, pz: Float, tx: Float, tz: Float): Float =
        if (fwdZ < 0) atan2(-(tx - px), -(tz - pz)) else atan2(tx - px, tz - pz)

    /**
     * Emit every segment of [frame] in world space to [out] — `(x0,y0,z0,x1,y1,z1, partIndex)`.
     * The model transform is `StrokeModel.walk`'s: scale, then roll about Z, pitch about X, yaw
     * about Y, then translate — which is the order a body tips, bends and turns. There is no
     * per-part pose: a sprite's parts were posed by the artist, per frame. Per-part COLOUR is the
     * caller's lookup on the part index ([SpriteMaterial]).
     */
    fun walkFrame(frame: Int, place: StrokeModel.Place, out: (Float, Float, Float, Float, Float, Float, Int) -> Unit) {
        if (frame !in 0 until frameCount) return
        val cy = cos(place.yaw); val sy = sin(place.yaw)
        val cp = cos(place.pitch); val sp = sin(place.pitch)
        val cr = cos(place.roll); val sr = sin(place.roll)
        val s = place.scale
        for (pi in 0 until nParts) {
            val n = counts[frame * nParts + pi]
            var i = starts[frame * nParts + pi] * 6
            for (k in 0 until n) {
                var x0 = segs[i] * s; var y0 = segs[i + 1] * s; var z0 = segs[i + 2] * s
                var x1 = segs[i + 3] * s; var y1 = segs[i + 4] * s; var z1 = segs[i + 5] * s
                var t = x0 * cr - y0 * sr; y0 = x0 * sr + y0 * cr; x0 = t
                t = x1 * cr - y1 * sr; y1 = x1 * sr + y1 * cr; x1 = t
                t = y0 * cp - z0 * sp; z0 = y0 * sp + z0 * cp; y0 = t
                t = y1 * cp - z1 * sp; z1 = y1 * sp + z1 * cp; y1 = t
                t = x0 * cy + z0 * sy; z0 = -x0 * sy + z0 * cy; x0 = t
                t = x1 * cy + z1 * sy; z1 = -x1 * sy + z1 * cy; x1 = t
                out(x0 + place.x, y0 + place.y, z0 + place.z, x1 + place.x, y1 + place.y, z1 + place.z, pi)
                i += 6
            }
        }
    }

    /** [place] applied to one model-space point, in place. */
    private fun transform(place: StrokeModel.Place, p: FloatArray) {
        val cy = cos(place.yaw); val sy = sin(place.yaw)
        val cp = cos(place.pitch); val sp = sin(place.pitch)
        val cr = cos(place.roll); val sr = sin(place.roll)
        var x = p[0] * place.scale; var y = p[1] * place.scale; var z = p[2] * place.scale
        var t = x * cr - y * sr; y = x * sr + y * cr; x = t
        t = y * cp - z * sp; z = y * sp + z * cp; y = t
        t = x * cy + z * sy; z = -x * sy + z * cy; x = t
        p[0] = x + place.x; p[1] = y + place.y; p[2] = z + place.z
    }

    companion object {
        private const val TAG = "X3Knockout"
        const val MAGIC = "X3S1"
        /** A frame over this many segments fails the export (`sys.exit(3)`), and is refused here too. */
        const val MAX_SEGS = 700
        /** The strips' authored rate (BOXER.md §8). The manifest carries it; this is the default. */
        const val FPS = 12
        /** The markers every frame carries, in the manifest's order (BOXER.md §8). */
        val MARKERS = listOf("glove_L", "glove_R", "chin", "body", "eye_L", "eye_R", "crown")
        private val cache = HashMap<String, StripSet>()

        /**
         * Loaded once per process from `assets/models/<name>.json` (+ `<name>.x3s` when the
         * manifest has strips). A missing or broken asset logs and yields an EMPTY set — no
         * strips, no frames — because a missing figure is a better failure on the glass than a
         * crash mid-round; the renderer draws nothing for an empty set and says so in `VERIFY`.
         */
        @Synchronized fun load(ctx: Context, name: String): StripSet {
            cache[name]?.let { return it }
            val set = runCatching {
                val manifest = ctx.assets.open("models/$name.json").bufferedReader().use { it.readText() }
                val bin = runCatching { ctx.assets.open("models/$name.x3s").use { it.readBytes() } }.getOrNull()
                parse(manifest, bin, name)
            }.getOrElse { Log.e(TAG, "strips $name", it); empty(name) }
            cache[name] = set
            Log.i(TAG, "strips $name: ${set.nParts} parts, ${set.strips.size} strips, ${set.frameCount} frames, ${set.frameSegCount(0)} segs on frame 0")
            return set
        }

        /**
         * THE LOADER WITHOUT ANDROID. Accepts either a strip manifest (with [x3s] beside it) or a
         * plain `StrokeModel` export (then [x3s] is ignored and the result is [fromModel]).
         * Malformed input throws; [load] catches. Every number in the manifest arrives as a
         * `Double` from `StrokeModel.Json`, which is why the reader lives there and not in
         * `org.json` (whose unit-test stub returns defaults for everything).
         */
        fun parse(manifest: String, x3s: ByteArray?, fallbackName: String = "?"): StripSet {
            val doc = StrokeModel.Json(manifest).value() as? Map<*, *> ?: throw IllegalArgumentException("manifest is not an object")
            val stripsRaw = doc["strips"] as? List<*>
            if (stripsRaw == null) return fromModel(StrokeModel.parse(manifest, fallbackName))
            val name = doc["name"] as? String ?: fallbackName
            val fps = ((doc["fps"] as? Double) ?: FPS.toDouble()).toInt().coerceAtLeast(1)
            val fwdZ = if ((doc["fwd"] as? String ?: "-z").trim().lowercase() == "+z") 1 else -1
            val parts = ArrayList<PartInfo>()
            for (entry in doc["parts"] as? List<*> ?: emptyList<Any?>()) {
                val p = entry as? Map<*, *> ?: continue
                val pn = p["name"] as? String ?: continue
                val cls = when ((p["cls"] as? String ?: "outline").lowercase()) {
                    "detail" -> Cls.DETAIL
                    "hatch" -> Cls.HATCH
                    else -> Cls.OUTLINE
                }
                parts.add(PartInfo(pn, cls, p["flash"] == true, vec3(p["pivot"]), vec3(p["color"], 1.0, 1.0, 1.0)))
            }
            val markers = (doc["markers"] as? List<*> ?: emptyList<Any?>()).mapNotNull { it as? String }
            val strips = LinkedHashMap<String, Strip>()
            for (entry in stripsRaw) {
                val s = entry as? Map<*, *> ?: continue
                val sn = s["name"] as? String ?: continue
                val events = LinkedHashMap<String, Int>()
                for ((k, v) in (s["events"] as? Map<*, *> ?: emptyMap<Any?, Any?>())) {
                    if (k is String && v is Double) events[k] = v.toInt()
                }
                strips[sn] = Strip(sn, ((s["first"] as? Double) ?: 0.0).toInt(), ((s["count"] as? Double) ?: 1.0).toInt().coerceAtLeast(1), s["loop"] == true, events)
            }
            val bytes = x3s ?: throw IllegalArgumentException("manifest has strips but no .x3s")
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }.toString(Charsets.US_ASCII)
            require(magic == MAGIC) { "bad magic $magic" }
            val nParts = b.int; val nMarkers = b.int; val nFrames = b.int
            require(nParts == parts.size) { "x3s has $nParts parts, manifest ${parts.size}" }
            require(nMarkers == markers.size) { "x3s has $nMarkers markers, manifest ${markers.size}" }
            require(nFrames >= 0 && nFrames < 100_000) { "x3s frame count $nFrames" }
            val counts = IntArray(nFrames * nParts)
            val starts = IntArray(nFrames * nParts)
            val segList = ArrayList<FloatArray>(nFrames)
            val markerXyz = FloatArray(nFrames * nMarkers * 3)
            var total = 0
            for (f in 0 until nFrames) {
                var frameSegs = 0
                for (p in 0 until nParts) {
                    val c = b.int
                    require(c >= 0) { "negative segment count" }
                    counts[f * nParts + p] = c; starts[f * nParts + p] = total + frameSegs
                    frameSegs += c
                }
                require(frameSegs <= MAX_SEGS) { "frame $f has $frameSegs segments, cap $MAX_SEGS" }
                val fa = FloatArray(frameSegs * 6)
                for (i in fa.indices) fa[i] = b.float
                segList.add(fa)
                total += frameSegs
                for (i in 0 until nMarkers * 3) markerXyz[f * nMarkers * 3 + i] = b.float
            }
            val segs = FloatArray(total * 6)
            var at = 0
            for (fa in segList) { fa.copyInto(segs, at); at += fa.size }
            return StripSet(name, parts, markers, strips, fps, fwdZ, nFrames, counts, starts, segs, markerXyz)
        }

        /**
         * A one-frame `idle` from a plain parts export, so the figure draws before any strip
         * exists. Every part becomes OUTLINE unless its name starts with `hatch_` (HATCH) or is
         * one of the small things (DETAIL); there are no markers, so the arc and the trails have
         * nothing to hang on and the renderer must cope with `marker()` returning false.
         */
        fun fromModel(model: StrokeModel): StripSet {
            val parts = model.parts.map { p ->
                val cls = when {
                    p.name.startsWith("hatch_") -> Cls.HATCH
                    p.name in DETAIL_NAMES -> Cls.DETAIL
                    else -> Cls.OUTLINE
                }
                PartInfo(p.name, cls, p.name.startsWith("glove_") || p.name.startsWith("eye_"), p.pivot.copyOf(), p.color.copyOf())
            }
            val n = parts.size
            val counts = IntArray(n); val starts = IntArray(n)
            var total = 0
            for (i in 0 until n) { counts[i] = model.parts[i].count; starts[i] = total; total += counts[i] }
            val segs = FloatArray(total * 6)
            var at = 0
            for (p in model.parts) { p.segs.copyInto(segs, at); at += p.segs.size }
            val strips = linkedMapOf("idle" to Strip("idle", 0, 1, true, emptyMap()))
            return StripSet(model.name, parts, emptyList(), strips, FPS, -1, 1, counts, starts, segs, FloatArray(0))
        }

        /** No parts, no frames, no strips: what a missing asset loads as. */
        fun empty(name: String): StripSet =
            StripSet(name, emptyList(), emptyList(), emptyMap(), FPS, -1, 0, IntArray(0), IntArray(0), FloatArray(0), FloatArray(0))

        private val DETAIL_NAMES = setOf("sweat", "teeth", "spiral_L", "spiral_R", "nose", "jaw_hatch")

        private fun vec3(v: Any?, dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0): FloatArray {
            val a = v as? List<*> ?: return floatArrayOf(dx.toFloat(), dy.toFloat(), dz.toFloat())
            fun f(i: Int, d: Double) = ((a.getOrNull(i) as? Double) ?: d).toFloat()
            return floatArrayOf(f(0, dx), f(1, dy), f(2, dz))
        }
    }
}

/**
 * ONE STRIP, PLAYING. Holds the current strip and frame, advances on the seconds it is handed —
 * WORLD seconds for everything hostile (the tells, the recovers, the feints), REAL seconds for the
 * count's `down` / `getup` where the fight says so — fires each frame's events exactly once as the
 * frame is ENTERED (frame 0's on [play]), loops or holds on the last frame, and cuts instantly on
 * the next [play]. A frozen `wdt` holds the frame: that is the whole telegraph at the floor.
 *
 * [rate] is a playback multiplier for the caller's escalation — the boxer plays a tell segment
 * at 1 / 0.75 in round 2 so the frames and his own timers agree, and the strike segment always at
 * 1× (BOXER.md §6). `StripPlayerTest` proves the hold and the fire-once.
 */
class StripPlayer(set: StripSet? = null) {
    var set: StripSet? = set
    var strip: StripSet.Strip? = null; private set
    /** The frame within the strip, 0-based. */
    var local = 0; private set
    /** The absolute frame, for [StripSet.walkFrame] and the markers. */
    val frame: Int get() = (strip?.first ?: 0) + local
    val name: String get() = strip?.name ?: ""
    /** Playback multiplier: 1 = the authored 12 fps. */
    var rate = 1f
    /** True once a non-looping strip has shown its last frame; it holds there until the next [play]. */
    var done = false; private set
    private var acc = 0f
    /** Fired with the event's name as its frame is entered — `strike`, `stamp`, `crow`, `done`, … */
    var onEvent: ((String) -> Unit)? = null

    /** 0..1 through the strip (frames plus the fraction of the current one), for the lab's `STRIP` row. */
    val frac: Float
        get() {
            val s = strip ?: return 0f
            val ft = set?.frameT ?: return 0f
            return ((local + acc / ft) / s.count).coerceIn(0f, 1f)
        }

    /** Cut to [name] at its first frame; false (and nothing changes) if the set has no such strip. */
    fun play(name: String, rate: Float = 1f): Boolean {
        val s = set?.strips?.get(name) ?: return false
        strip = s; local = 0; acc = 0f; done = false; this.rate = rate
        fire(0)
        return true
    }

    /** Advance by [seconds] × [rate]. Never called with the wrong clock: the caller decides which. */
    fun update(seconds: Float) {
        val s = strip ?: return
        val ft = set?.frameT ?: return
        if (done || seconds <= 0f || !seconds.isFinite()) return
        acc += seconds * rate
        while (acc >= ft) {
            acc -= ft
            if (local + 1 >= s.count) {
                if (s.loop) { local = 0; fire(0) } else { done = true; acc = 0f; return }
            } else {
                local++; fire(local)
            }
        }
    }

    /** The named marker's MODEL-space position on the current frame. */
    fun marker(name: String, out: FloatArray): Boolean {
        val st = set ?: return false
        return st.marker(frame, st.marker(name), out)
    }

    /** Has the current strip's named event frame been reached (entered) yet? */
    fun reached(event: String): Boolean {
        val f = strip?.event(event) ?: return false
        return f in 0..local
    }

    private fun fire(localFrame: Int) {
        val s = strip ?: return
        val cb = onEvent ?: return
        for ((k, v) in s.events) if (v == localFrame) cb(k)
    }
}

/**
 * THE TINT TABLE — the cabinet's palette flash (DESIGN.md §12.2 #2, §12.4). Colour is per PART
 * and set per frame from this small table: the telegraph is `set(glove_L, 1, 1, 1, 1.5)`, the hit
 * flash is the head outline at WHITE α 1.5 for two frames, hiding a variant (a mouth, the sweat,
 * the spirals) is gain 0, the crest's hue is one write, LOD dimming is `gain(hatch, 0.35 ×
 * √(2.6 / d))`. The DRAWING never changes; the fight writes the table and the renderer's emitter
 * reads it per segment on the CPU path (and as `uTint[32]` on the reserve VBO path — the same
 * table).
 *
 * WHY A TINT AND NOT ALPHA (§7.1): the fragment writes premultiplied `(rgb·a, a)` and the 8-bit
 * buffer clamps both, so MAGENTA at α 1.4 is hotter magenta, never white, and a zero channel
 * stays zero. A stroke that must read WHITE is written WHITE here.
 */
class SpriteMaterial {
    /** `(r, g, b, gain)` × [MAX_PARTS]. A gain of 0 hides the part; above 1 is the white-hot core. */
    val tint = FloatArray(MAX_PARTS * 4)

    /** Every part back to its authored colour at gain 1. Parts past [MAX_PARTS] share the last slot. */
    fun reset(set: StripSet?) {
        for (i in 0 until MAX_PARTS) { tint[i * 4] = 1f; tint[i * 4 + 1] = 1f; tint[i * 4 + 2] = 1f; tint[i * 4 + 3] = 1f }
        val parts = set?.parts ?: return
        for (i in parts.indices) {
            val c = parts[i].color
            val s = slot(i) * 4
            tint[s] = c[0]; tint[s + 1] = c[1]; tint[s + 2] = c[2]; tint[s + 3] = 1f
        }
    }

    fun set(part: Int, r: Float, g: Float, b: Float, gain: Float) {
        if (part < 0) return
        val s = slot(part) * 4
        tint[s] = r; tint[s + 1] = g; tint[s + 2] = b; tint[s + 3] = gain
    }

    fun set(part: Int, rgb: FloatArray, gain: Float) = set(part, rgb[0], rgb[1], rgb[2], gain)

    fun gain(part: Int, gain: Float) { if (part >= 0) tint[slot(part) * 4 + 3] = gain }

    fun r(part: Int) = tint[slot(part) * 4]
    fun g(part: Int) = tint[slot(part) * 4 + 1]
    fun b(part: Int) = tint[slot(part) * 4 + 2]
    fun gain(part: Int) = tint[slot(part) * 4 + 3]

    private fun slot(part: Int) = part.coerceIn(0, MAX_PARTS - 1)

    companion object {
        /** 32 slots: BOXER.md §8's 34 parts fit because `brow_L/R` share one and `sweat` rides on `detail`. */
        const val MAX_PARTS = 32
    }
}

/**
 * THE DETAIL LOD, WITH HYSTERESIS (DESIGN.md §12.7). Parts flagged [StripSet.Cls.DETAIL] — laces,
 * the thumb bump, the sweat, the teeth, the spirals — are drawn inside [onBelow] metres and hidden
 * beyond [offAbove], and BETWEEN the two the previous decision stands. The camera only ever moves
 * by the lean (±0.55 m) and the step around a figure 2.6 m away, so its distance hovers around
 * 2.6–2.9 m and would cross a single threshold at 3.0 m back and forth on every slip; a band with
 * 0.4 m of slack means a lace never flickers on and off. The renderer asks [update] once a frame
 * with the eye's distance to the figure and hands the answer to the tint pass as gain 1 or 0
 * (`SpriteMaterial`); the outline never LODs, the silhouette is the read.
 *
 * A new instance starts ON because every fight opens at 2.6 m; [reset] re-decides without
 * hysteresis for a cut (the round card, a restart) so a figure never comes back hidden.
 */
class DetailLod(val onBelow: Float = 3.0f, val offAbove: Float = 3.4f) {
    var on = true; private set

    /** The decision for this frame at [distance] metres: ON inside [onBelow], OFF beyond [offAbove], else unchanged. */
    fun update(distance: Float): Boolean {
        if (on && distance > offAbove) on = false
        else if (!on && distance < onBelow) on = true
        return on
    }

    /** Decide afresh, without the band: ON unless already beyond [offAbove]. */
    fun reset(distance: Float) { on = distance <= offAbove }
}
