package com.x3knockout.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs

/**
 * THE SPRITE FORMAT AND THE FORWARD AXIS, PINNED (DESIGN.md §12.6).
 *
 * Two things can go wrong between `blender/export_strokes.py` and `Poses.kt` and neither
 * crashes: the binary layout can drift (a field order, an endianness, a per-frame count) and
 * the figure can face the wrong way (perfectly playable, invisible in a screenshot until someone
 * looks hard — `program.py`'s KDoc tells that story). So this runs the SHIPPING reader over the
 * SHIPPING asset — the same two pieces of code the glasses run — and over a hand-built set whose
 * one segment has a known front and back.
 *
 * The strike-frame assertion TEST.md names (`glove_R` nearer the camera on `wing_r`'s strike
 * frame than on its first) is [wingRStrikeGloveComesToTheCamera]; it is skipped, not faked, while
 * the shipped asset is the one-pose skeleton with no `wing_r` yet — the day the strip is
 * authored the assertion arms itself.
 */
class StripSetTest {

    companion object {
        /** Gradle runs a module's tests from the module directory; a bare JVM run from the repo root. */
        private val MODELS = listOf("src/main/assets/models", "app/src/main/assets/models").map { File(it) }.firstOrNull { it.isDirectory }
        fun shipped(): StripSet? {
            val dir = MODELS ?: return null
            val json = File(dir, "boxer.json"); val bin = File(dir, "boxer.x3s")
            if (!json.isFile) return null
            return StripSet.parse(json.readText(), if (bin.isFile) bin.readBytes() else null, "boxer")
        }
    }

    @Test
    fun theShippedManifestParsesWithItsFrames() {
        val set = shipped() ?: return
        assertEquals("the tint table's 32 slots (boxer.py's PARTS)", 32, set.nParts)
        assertEquals(StripSet.MARKERS, set.markers)
        assertTrue("every strip fits the cap", (0 until set.frameCount).all { set.frameSegCount(it) in 1..StripSet.MAX_SEGS })
        val idle = set.strip("idle")
        assertTrue("an idle strip exists", idle != null && idle.count >= 1 && idle.loop)
        assertEquals(-1, set.fwdZ)
        for (n in listOf("head", "glove_L", "glove_R", "pupil_L", "pupil_R", "crest_0", "crest_4", "hatch_head", "spirals"))
            assertTrue("part $n is in the table", set.part(n) >= 0)
        val chin = FloatArray(3)
        assertTrue(set.marker(0, set.marker("chin"), chin))
        assertEquals("the chin marker is at 1.20 m (BOXER.md §1: chin at 1.20, crown at 1.90+)", 1.20f, chin[1], 0.02f)
        val crown = FloatArray(3)
        assertTrue(set.marker(0, set.marker("crown"), crown))
        assertTrue("the crown is above the chin", crown[1] > chin[1] + 0.6f)
    }

    @Test
    fun wingRStrikeGloveComesToTheCamera() {
        val set = shipped() ?: return
        val wing = set.strip("wing_r") ?: return          // arms itself when the strip is authored
        val strike = wing.event("strike")
        assertTrue("wing_r has a strike event", strike >= 0)
        val place = StrokeModel.Place(x = 0f, y = 0f, z = -2.6f, yaw = set.headingTo(0f, -2.6f, 0f, 0f))
        val a = FloatArray(3); val b = FloatArray(3)
        assertTrue(set.markerWorld(wing.first, set.marker("glove_R"), place, a))
        assertTrue(set.markerWorld(wing.first + strike, set.marker("glove_R"), place, b))
        assertTrue("the strike glove is nearer the camera (+Z) than the tell glove", b[2] > a[2] + 0.05f)
    }

    /** A hand-built set: one part, one frame, one segment from the figure's back to its front. */
    private fun synthetic(fwd: String): StripSet {
        val manifest = """{"name":"t","fps":12,"fwd":"$fwd","parts":[{"name":"p","cls":"outline","flash":false,"pivot":[0,0,0],"color":[1,0,0]}],
            "markers":["m"],"strips":[{"name":"s","first":0,"count":3,"loop":false,"events":{"strike":1,"done":2}}],"frames":3}"""
        val bb = ByteBuffer.allocate(4 + 12 + 3 * (4 + 6 * 4 + 3 * 4)).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("X3S1".toByteArray(Charsets.US_ASCII)); bb.putInt(1); bb.putInt(1); bb.putInt(3)
        for (f in 0 until 3) {
            bb.putInt(1)
            // back (+Z for a -z figure) to front (-Z): the front is what must end up nearer the camera
            bb.putFloat(0f); bb.putFloat(1f); bb.putFloat(0.1f); bb.putFloat(0f); bb.putFloat(1f); bb.putFloat(-0.1f)
            bb.putFloat(0.2f * f); bb.putFloat(1.5f); bb.putFloat(0f)
        }
        return StripSet.parse(manifest, bb.array(), "t")
    }

    @Test
    fun theFrontFacesTheCameraAfterHeadingTo() {
        val set = synthetic("-z")
        assertEquals(-1, set.fwdZ)
        val yaw = set.headingTo(0f, -2.6f, 0f, 0f)
        assertEquals("a -z figure at (0, -2.6) turns by pi to face the eye at the origin", PI.toFloat(), abs(yaw), 1e-3f)
        val place = StrokeModel.Place(z = -2.6f, yaw = yaw)
        var z0 = 0f; var z1 = 0f
        set.walkFrame(0, place) { _, _, a, _, _, b, _ -> z0 = a; z1 = b }
        assertTrue("the model's front (-Z) is nearer the camera (larger world Z) than its back", z1 > z0 + 0.15f)
        val m = FloatArray(3)
        assertTrue(set.markerWorld(2, 0, place, m))
        assertEquals("a marker at model +x lands at world -x after the half turn", -0.4f, m[0], 1e-4f)
    }

    @Test
    fun aPlainModelExportBecomesAOneFrameIdle() {
        val set = StripSet.parse("""{"name":"m","parts":[{"name":"glove_L","color":[1,0,0],"pivot":[0,0,0],"segs":[[0,0,0,1,0,0],[1,0,0,1,1,0]]}]}""", null)
        assertEquals(1, set.frameCount)
        assertEquals(2, set.frameSegCount(0))
        assertTrue(set.strip("idle")?.loop == true)
        assertTrue("a glove flashes", set.parts[0].flash)
    }

    @Test
    fun aFrameOverTheCapIsRefused() {
        val n = StripSet.MAX_SEGS + 1
        val manifest = """{"name":"t","fps":12,"parts":[{"name":"p"}],"markers":[],"strips":[{"name":"s","first":0,"count":1}],"frames":1}"""
        val bb = ByteBuffer.allocate(16 + 4 + n * 24).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("X3S1".toByteArray(Charsets.US_ASCII)); bb.putInt(1); bb.putInt(0); bb.putInt(1); bb.putInt(n)
        repeat(n * 6) { bb.putFloat(0f) }
        val ok = runCatching { StripSet.parse(manifest, bb.array(), "t") }.isSuccess
        assertTrue("701 segments in a frame must throw", !ok)
    }
}

/**
 * A FROZEN `wdt` HOLDS THE FRAME AND AN EVENT FIRES EXACTLY ONCE — the two promises the
 * telegraph at the floor rests on (DESIGN.md §7.3, §12.6).
 */
class StripPlayerTest {

    private fun set(): StripSet {
        val manifest = """{"name":"t","fps":12,"parts":[{"name":"p"}],"markers":[],
            "strips":[{"name":"s","first":0,"count":3,"loop":false,"events":{"strike":1,"done":2}},{"name":"l","first":3,"count":2,"loop":true,"events":{"tick":0}}],"frames":5}"""
        val bb = ByteBuffer.allocate(16 + 5 * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("X3S1".toByteArray(Charsets.US_ASCII)); bb.putInt(1); bb.putInt(0); bb.putInt(5)
        repeat(5) { bb.putInt(0) }
        return StripSet.parse(manifest, bb.array(), "t")
    }

    @Test
    fun aFrozenWorldHoldsTheFrame() {
        val p = StripPlayer(set())
        assertTrue(p.play("s"))
        repeat(600) { p.update(0f) }
        assertEquals("no world time, no frame", 0, p.local)
        assertEquals(0, p.frame)
        p.update(1f / 12f * 0.9f)
        assertEquals("less than a frame's worth still holds", 0, p.local)
    }

    @Test
    fun eventsFireOnceAsTheirFrameIsEntered() {
        val p = StripPlayer(set())
        val fired = ArrayList<String>()
        p.onEvent = { fired.add(it) }
        p.play("s")
        repeat(4) { p.update(1f / 12f * 0.3f) }       // 1.2 frames of world time in four small steps
        assertEquals(listOf("strike"), fired)
        assertEquals(1, p.local)
        p.update(5f)
        assertEquals("the strip holds on its last frame", 2, p.local)
        assertTrue(p.done)
        assertEquals("...and `done` fired exactly once for all that time", listOf("strike", "done"), fired)
        p.update(5f)
        assertEquals(listOf("strike", "done"), fired)
    }

    @Test
    fun aLoopWrapsAndRefiresItsFirstFrame() {
        val p = StripPlayer(set())
        var ticks = 0
        p.onEvent = { if (it == "tick") ticks++ }
        p.play("l")
        assertEquals("frame 0's events fire on play", 1, ticks)
        p.update(2f / 12f + 1e-3f)
        assertEquals("absolute frame 3 again after a full loop", 3, p.frame)
        assertEquals(2, ticks)
        assertTrue(!p.done)
    }

    @Test
    fun theRateScalesTheTellAndACutIsInstant() {
        val p = StripPlayer(set())
        p.play("s", rate = 1f / 0.75f)
        p.update(0.75f / 12f + 1e-4f)
        assertEquals("round 2's tell plays a third faster", 1, p.local)
        assertTrue(p.play("l"))
        assertEquals("sprites cut, they do not blend", 0, p.local)
        assertEquals(3, p.frame)
        assertTrue(!p.play("nope"))
        assertEquals("an unknown strip changes nothing", "l", p.name)
    }
}
