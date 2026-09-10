package com.x3knockout.engine

import android.content.Context
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin

/**
 * A MODEL IS A LIST OF STROKES — what blender/export_strokes.py writes and the only kind of
 * geometry this game has. Loaded once from assets/models/<name>.json: named parts, each a flat
 * array of segments (x0,y0,z0,x1,y1,z1 in metres, Y up, +Z toward the viewer at rest) with a
 * pivot the part rotates about and a colour hint the renderer is free to ignore.
 *
 * MODEL-SPACE FRONT IS −Z (ART.md §3.1, BUILD_PLAN §1.3). [walk]'s yaw is an ordinary
 * right-handed rotation about +Y, so it carries model −Z to world `(−sin yaw, −cos yaw)`, and the
 * heading that aims a model at `(tx, tz)` from `(px, pz)` is therefore
 * `atan2(−(tx − px), −(tz − pz))`. That is the NEGATION of the camera's own yaw convention
 * (`Game` builds a look vector as `(sin yaw, −cos yaw)`), which is a genuine trap: a figure placed
 * with a camera-frame yaw is mirrored left-for-right and only looks correct when it happens to be
 * dead ahead. `StrokeModelTest` pins the convention, and anything that places a figure — or offsets
 * a lock glow onto its visor — takes its heading from the same formula.
 *
 * Posing is done by [Pose]: per-part yaw/pitch/roll about the part's pivot plus an offset, then a
 * whole-model transform. That is enough for a throw (the arm sweeps), a duck (the torso pitches),
 * a lean (the torso rolls), tiles turning in the floor, and a derez (parts drift apart) — and it
 * is cheap, because a few hundred segments through a handful of rotations is nothing at 60 Hz.
 */
class StrokeModel private constructor(val name: String, val parts: List<Part>) {

    class Part(val name: String, val color: FloatArray, val pivot: FloatArray, val segs: FloatArray) {
        val count get() = segs.size / 6
    }

    /** Where each part sits this frame. Indexed like [parts]; default is the rest pose. */
    class Pose(n: Int) {
        val yaw = FloatArray(n); val pitch = FloatArray(n); val roll = FloatArray(n)
        val dx = FloatArray(n); val dy = FloatArray(n); val dz = FloatArray(n)
        /** 0..1 brightness per part, so a part can fade, flash, or be hidden (0). */
        val gain = FloatArray(n) { 1f }
        fun reset() {
            for (i in yaw.indices) { yaw[i] = 0f; pitch[i] = 0f; roll[i] = 0f; dx[i] = 0f; dy[i] = 0f; dz[i] = 0f; gain[i] = 1f }
        }
    }

    fun newPose() = Pose(parts.size)
    fun part(name: String): Int = parts.indexOfFirst { it.name == name }

    /** Everything [walk] needs about a placed model: its position, heading, tilt, scale. */
    class Place(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var yaw: Float = 0f, var pitch: Float = 0f, var roll: Float = 0f, var scale: Float = 1f)

    /**
     * Emit every segment in world space to [out] — (x0,y0,z0,x1,y1,z1, partIndex). The part's own
     * rotation is applied about its pivot first, then its offset, then the model's rotation and
     * scale, then its position. Rotation order for both is yaw about Y, then pitch about X, then
     * roll about Z — which is the order a body actually turns, bends and tips.
     */
    fun walk(place: Place, pose: Pose?, out: (Float, Float, Float, Float, Float, Float, Int) -> Unit) {
        val my = place.yaw; val mp = place.pitch; val mr = place.roll
        val cy = cos(my); val sy = sin(my); val cp = cos(mp); val sp = sin(mp); val cr = cos(mr); val sr = sin(mr)
        val s = place.scale
        for ((pi, part) in parts.withIndex()) {
            if (pose != null && pose.gain[pi] <= 0f) continue
            val py = pose?.yaw?.get(pi) ?: 0f; val pp = pose?.pitch?.get(pi) ?: 0f; val pr = pose?.roll?.get(pi) ?: 0f
            val hasLocal = py != 0f || pp != 0f || pr != 0f
            val lcy = cos(py); val lsy = sin(py); val lcp = cos(pp); val lsp = sin(pp); val lcr = cos(pr); val lsr = sin(pr)
            val ox = pose?.dx?.get(pi) ?: 0f; val oy = pose?.dy?.get(pi) ?: 0f; val oz = pose?.dz?.get(pi) ?: 0f
            val px = part.pivot[0]; val pyv = part.pivot[1]; val pz = part.pivot[2]
            val a = part.segs
            var i = 0
            while (i < a.size) {
                var x0 = a[i]; var y0 = a[i + 1]; var z0 = a[i + 2]
                var x1 = a[i + 3]; var y1 = a[i + 4]; var z1 = a[i + 5]
                if (hasLocal) {
                    // about the pivot
                    x0 -= px; y0 -= pyv; z0 -= pz; x1 -= px; y1 -= pyv; z1 -= pz
                    // roll (Z), pitch (X), yaw (Y)
                    var t = x0 * lcr - y0 * lsr; y0 = x0 * lsr + y0 * lcr; x0 = t
                    t = x1 * lcr - y1 * lsr; y1 = x1 * lsr + y1 * lcr; x1 = t
                    t = y0 * lcp - z0 * lsp; z0 = y0 * lsp + z0 * lcp; y0 = t
                    t = y1 * lcp - z1 * lsp; z1 = y1 * lsp + z1 * lcp; y1 = t
                    t = x0 * lcy + z0 * lsy; z0 = -x0 * lsy + z0 * lcy; x0 = t
                    t = x1 * lcy + z1 * lsy; z1 = -x1 * lsy + z1 * lcy; x1 = t
                    x0 += px; y0 += pyv; z0 += pz; x1 += px; y1 += pyv; z1 += pz
                }
                x0 += ox; y0 += oy; z0 += oz; x1 += ox; y1 += oy; z1 += oz
                // model: scale, roll, pitch, yaw, translate
                x0 *= s; y0 *= s; z0 *= s; x1 *= s; y1 *= s; z1 *= s
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

    companion object {
        private const val TAG = "X3Knockout"
        private val cache = HashMap<String, StrokeModel>()

        /** Loaded once per process; safe to call from any thread after the first load. */
        @Synchronized fun load(ctx: Context, name: String): StrokeModel {
            cache[name]?.let { return it }
            val m = runCatching {
                parse(ctx.assets.open("models/$name.json").bufferedReader().use { it.readText() }, name)
            }.getOrElse { Log.e(TAG, "model $name", it); StrokeModel(name, emptyList()) }
            cache[name] = m
            Log.i(TAG, "model $name: ${m.parts.size} parts, ${m.parts.sumOf { it.count }} segs")
            return m
        }

        /**
         * THE LOADER WITHOUT ANDROID — [load] is this plus the asset stream, the cache and the log.
         *
         * It is split out so `StrokeModelTest` can run the SHIPPING code over the SHIPPING
         * `program.json` on a bare JVM. The forward axis is a property of the exported file, not of
         * the exporter script: a test that re-derived the transform from constants of its own would
         * pass happily over an asset nobody re-exported. That requirement also rules out
         * `org.json`, whose unit-test stub returns defaults for every call
         * (`unitTests.isReturnDefaultValues` in app/build.gradle.kts) and would hand the test an
         * empty model that asserts nothing — hence the small reader below rather than a dependency.
         *
         * DUPLICATE PART NAMES ARE MERGED: segments concatenate in file order, and the first
         * occurrence's pivot and colour win. A part is a BONE, not an object — the asset scripts
         * put several objects on one bone on purpose (a forearm and its seam line; the torso, the
         * pelvis and the chest chevron), and [part] resolves a name to exactly one index. Kept
         * unmerged, only the first object of a name could be posed: the arm swung through a throw
         * while its seam hung in the air where the arm used to be. The merge is also why the asset
         * scripts must give every object of a part the same pivot — the others are discarded.
         *
         * Malformed input throws; [load] catches that, logs it and yields an empty model, because a
         * missing figure is a better failure on the glass than a crash mid-round.
         */
        fun parse(text: String, fallbackName: String = "?"): StrokeModel {
            val doc = Json(text).value() as? Map<*, *> ?: throw IllegalArgumentException("model is not an object")
            val raw = doc["parts"] as? List<*> ?: emptyList<Any?>()
            val order = ArrayList<String>(raw.size)
            val colors = HashMap<String, FloatArray>()
            val pivots = HashMap<String, FloatArray>()
            val chunks = HashMap<String, ArrayList<FloatArray>>()
            for (entry in raw) {
                val p = entry as? Map<*, *> ?: continue
                val pn = p["name"] as? String ?: continue
                var into = chunks[pn]
                if (into == null) {
                    into = ArrayList(); chunks[pn] = into; order.add(pn)
                    colors[pn] = vec3(p["color"], 1f, 1f, 1f)
                    pivots[pn] = vec3(p["pivot"], 0f, 0f, 0f)
                }
                into.add(segsOf(p["segs"]))
            }
            val parts = ArrayList<Part>(order.size)
            for (pn in order) {
                val cs = chunks[pn]!!
                val flat = FloatArray(cs.sumOf { it.size })
                var at = 0
                for (c in cs) { c.copyInto(flat, at); at += c.size }
                parts.add(Part(pn, colors[pn]!!, pivots[pn]!!, flat))
            }
            return StrokeModel(doc["name"] as? String ?: fallbackName, parts)
        }

        private fun vec3(v: Any?, dx: Float, dy: Float, dz: Float): FloatArray {
            val a = v as? List<*> ?: return floatArrayOf(dx, dy, dz)
            fun f(i: Int, d: Float) = (a.getOrNull(i) as? Double)?.toFloat() ?: d
            return floatArrayOf(f(0, dx), f(1, dy), f(2, dz))
        }

        /** `[[x0,y0,z0,x1,y1,z1], …]` flattened. A row that is not six numbers long is dropped
         *  whole — a damaged file loses one stroke rather than shifting every later coordinate by
         *  one place — and a non-numeric value inside a row reads as zero. */
        private fun segsOf(v: Any?): FloatArray {
            val rows = v as? List<*> ?: return FloatArray(0)
            val out = FloatArray(rows.size * 6)
            var at = 0
            for (r in rows) {
                val s = r as? List<*> ?: continue
                if (s.size < 6) continue
                for (q in 0 until 6) out[at + q] = ((s[q] as? Double) ?: 0.0).toFloat()
                at += 6
            }
            return if (at == out.size) out else out.copyOf(at)
        }

    }

    /**
     * Sixty lines of JSON reader so the model path owes nothing to the framework (see [parse]).
     * It is a complete reader of the subset `json.dump` emits — objects, arrays, strings with
     * the standard escapes, numbers, the three literals — and every number comes back as a
     * `Double`, which is what [vec3] and [segsOf] expect. Running off the end of a truncated
     * file throws out of `s[i]`, and that is the intended report: the caller wants to know.
     *
     * `internal`, not private: `Poses.kt`'s `StripSet.parse` reads the sprite manifest with
     * the same reader for the same reason this class has one (a JVM test over the shipping
     * asset, with no `org.json` stub handing back an empty document).
     */
    internal class Json(private val s: String) {
        private var i = 0

        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { word("true"); true }
                'f' -> { word("false"); false }
                'n' -> { word("null"); null }
                else -> num()
            }
        }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun expect(c: Char) {
            ws()
            require(i < s.length && s[i] == c) { "expected '$c' at $i" }
            i++
        }

        private fun word(w: String) {
            require(s.startsWith(w, i)) { "expected $w at $i" }
            i += w.length
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                expect(':')
                m[k] = value()
                ws()
                if (s[i] == ',') { i++; continue }
                expect('}'); return m
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                if (s[i] == ',') { i++; continue }
                expect(']'); return l
            }
        }

        private fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c != '\\' -> b.append(c)
                    else -> when (val e = s[i++]) {
                        '"', '\\', '/' -> b.append(e)
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000C')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> throw IllegalArgumentException("bad escape \\$e at $i")
                    }
                }
            }
        }

        private fun num(): Double {
            val from = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            return s.substring(from, i).toDouble()
        }
    }
}
