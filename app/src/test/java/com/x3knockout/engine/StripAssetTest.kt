package com.x3knockout.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * THE AUTHORED STRIPS AGAINST BOXER.md §8 — the shipped `boxer.json` + `boxer.x3s` read by the
 * shipping `StripSet`, checked against the table the fight was designed to: every strip by name
 * with its frame count, its loop flag and its events on the frames the engine keys its phases
 * to (`strike` is where `Forced.STRIKE` begins; `done` is where the recover ends; `down` is the
 * frame the count starts on). `StripSetTest` proves the FORMAT; this proves the CONTENT, so that
 * a re-export of `blender/assets/boxer.py` that drops a strip, shifts an event or shortens a tell
 * fails here and not on the glass.
 *
 * The geometry assertions are the ones a screenshot cannot make reliably: every attack's glove
 * comes TOWARD the camera on its strike frames (the forward-axis trap, per attack); the low hook's
 * glove really goes under the plate's bottom edge during its tell; the Sunrise really goes over
 * the crown; the mirror of `peck_l` is exact; the falls end on the canvas and the rise ends
 * upright. Every test skips (returns) when the asset is absent, as `StripSetTest` does, so a
 * checkout without models still builds — but never when the asset is present and wrong.
 */
class StripAssetTest {

    private class Row(val name: String, val count: Int, val loop: Boolean, val events: Map<String, Int>)

    /** BOXER.md §8, verbatim. */
    private val table = listOf(
        Row("idle", 8, true, emptyMap()),
        Row("guard", 4, true, emptyMap()),
        Row("peck_l", 15, false, mapOf("telegraph" to 0, "strike" to 6, "done" to 14)),
        Row("peck_r", 15, false, mapOf("telegraph" to 0, "strike" to 6, "done" to 14)),
        Row("wing_r", 22, false, mapOf("telegraph" to 0, "stamp" to 2, "strike" to 8, "done" to 21)),
        Row("wing_l", 19, false, mapOf("telegraph" to 0, "stamp" to 2, "strike" to 8, "done" to 18)),
        Row("sunrise", 28, false, mapOf("telegraph" to 0, "crow" to 0, "strike" to 10, "done" to 27)),
        Row("feint_peck", 4, false, mapOf("telegraph" to 0, "done" to 3)),
        Row("feint_crow", 5, false, mapOf("telegraph" to 0, "cut" to 3, "done" to 4)),
        Row("half_stamp", 3, false, mapOf("stamp" to 0, "done" to 2)),
        Row("hit_head", 3, false, mapOf("flash" to 0)),
        Row("hit_body", 3, false, mapOf("flash" to 0, "open" to 0)),
        Row("stagger", 8, true, mapOf("open" to 0)),
        Row("stun", 4, true, mapOf("open" to 0)),
        Row("knockdown", 13, false, mapOf("down" to 12)),
        Row("down", 4, true, emptyMap()),
        Row("getup", 8, false, mapOf("up" to 7)),
        Row("ko", 12, false, mapOf("ko" to 11)),
        Row("taunt", 10, false, emptyMap()),
        Row("win", 8, true, emptyMap()),
    )

    private fun shipped(): StripSet? = StripSetTest.shipped()?.takeIf { it.frameCount > 1 }

    /** The fight's placement: the figure at (0, 0, −2.6) facing the eye at the origin. */
    private fun place(set: StripSet) = StrokeModel.Place(x = 0f, y = 0f, z = -2.6f, yaw = set.headingTo(0f, -2.6f, 0f, 0f))

    private fun world(set: StripSet, frame: Int, marker: String): FloatArray {
        val out = FloatArray(3)
        assertTrue("marker $marker on frame $frame", set.markerWorld(frame, set.marker(marker), place(set), out))
        return out
    }

    private fun model(set: StripSet, frame: Int, marker: String): FloatArray {
        val out = FloatArray(3)
        assertTrue("marker $marker on frame $frame", set.marker(frame, set.marker(marker), out))
        return out
    }

    @Test
    fun everyStripOfTheTableShipsWithItsFramesLoopAndEvents() {
        val set = shipped() ?: return
        for (row in table) {
            val s = set.strip(row.name)
            assertTrue("strip ${row.name} exists", s != null)
            assertEquals("${row.name} frames", row.count, s!!.count)
            assertEquals("${row.name} loop", row.loop, s.loop)
            assertEquals("${row.name} events", row.events, s.events)
            assertTrue("${row.name} fits the file", s.last < set.frameCount)
        }
        assertEquals("the table is the whole set", table.map { it.name }.toSet(), set.strips.keys)
        assertEquals("BOXER.md §8's frame total", table.sumOf { it.count }, set.frameCount)
        assertEquals(12, set.fps)
    }

    @Test
    fun everyFrameIsInsideTheBudgetAndDrawsSomething() {
        val set = shipped() ?: return
        for (f in 0 until set.frameCount) {
            val n = set.frameSegCount(f)
            assertTrue("frame $f: $n segments", n in 300..StripSet.MAX_SEGS)
        }
    }

    @Test
    fun theRestFrameIsTheFigureTheDesignDescribes() {
        val set = shipped() ?: return
        val chin = model(set, 0, "chin"); val crown = model(set, 0, "crown"); val body = model(set, 0, "body")
        assertEquals("chin at 1.20", 1.20f, chin[1], 0.02f)
        assertEquals("crest tip at 2.12", 2.12f, crown[1], 0.03f)
        assertEquals("body marker at 0.92", 0.92f, body[1], 0.03f)
        val gl = model(set, 0, "glove_L"); val gr = model(set, 0, "glove_R")
        assertTrue("his left glove is at model −x (his right is +x)", gl[0] < -0.2f && gr[0] > 0.2f)
        assertEquals("the guard is symmetric", gl[1], gr[1], 0.03f)
        assertTrue("the guard covers the jaw (chin 1.20, gloves r 0.24)", abs(gl[1] - chin[1]) < 0.24f + 0.02f)
        val el = model(set, 0, "eye_L"); val er = model(set, 0, "eye_R")
        assertEquals(-0.12f, el[0], 0.01f); assertEquals(0.12f, er[0], 0.01f); assertEquals(1.60f, el[1], 0.02f)
        // his left (model −x) lands on the viewer's RIGHT (world +x) after the billboard turn
        assertTrue(world(set, 0, "glove_L")[0] > 0.2f && world(set, 0, "glove_R")[0] < -0.2f)
    }

    @Test
    fun everyAttacksGloveComesToTheCameraOnItsStrikeFrames() {
        val set = shipped() ?: return
        for ((name, glove) in listOf("peck_l" to "glove_L", "peck_r" to "glove_R", "wing_r" to "glove_R", "wing_l" to "glove_L", "sunrise" to "glove_R")) {
            val s = set.strip(name)!!
            val strike = s.event("strike")
            val tell = world(set, s.first, glove)[2]
            val hit = world(set, s.first + strike + 1, glove)[2]
            assertTrue("$name: $glove nearer the camera (+z) on strike+1 ($hit) than on the tell ($tell)", hit > tell + 0.3f)
            val home = world(set, s.last, glove)
            assertEquals("$name: the glove is home on `done`", tell, home[2], 0.05f)
        }
    }

    @Test
    fun theLowHookGoesUnderThePlateAndTheSunriseOverTheCrown() {
        val set = shipped() ?: return
        val wl = set.strip("wing_l")!!
        for (f in wl.event("stamp")..wl.event("strike") - 1)
            assertTrue("wing_l tell frame $f: the left glove is below the plate's bottom edge (0.09 m at 2.6 m)", model(set, wl.first + f, "glove_L")[1] < 0.0f)
        val ribs = model(set, wl.first + wl.event("strike") + 1, "glove_L")[1]
        assertTrue("wing_l lands at the ribs (band 1.00–1.35)", ribs in 0.95f..1.4f)
        val su = set.strip("sunrise")!!
        val crown = model(set, 0, "crown")[1]
        assertTrue("the Sunrise's glove is below the canvas mid-tell", model(set, su.first + 4, "glove_R")[1] < 0.0f)
        assertTrue("…and sky-high two frames into the strike", model(set, su.first + su.event("strike") + 2, "glove_R")[1] > crown - 0.15f)
        val pk = set.strip("peck_l")!!
        val head = model(set, pk.first + pk.event("strike") + 1, "glove_L")[1]
        assertTrue("a peck lands at the head (band 1.50–1.80)", head in 1.45f..1.85f)
    }

    @Test
    fun peckRIsTheExactMirrorOfPeckL() {
        val set = shipped() ?: return
        val l = set.strip("peck_l")!!; val r = set.strip("peck_r")!!
        for (k in 0 until l.count) {
            val gl = model(set, l.first + k, "glove_L"); val gr = model(set, r.first + k, "glove_R")
            assertEquals("frame $k x", -gl[0], gr[0], 1e-3f); assertEquals("frame $k y", gl[1], gr[1], 1e-3f); assertEquals("frame $k z", gl[2], gr[2], 1e-3f)
            val cl = model(set, l.first + k, "chin"); val cr = model(set, r.first + k, "chin")
            assertEquals("frame $k chin x", -cl[0], cr[0], 1e-3f)
            assertEquals("frame $k segments", set.frameSegCount(l.first + k), set.frameSegCount(r.first + k))
        }
    }

    @Test
    fun theFallsEndOnTheCanvasAndTheRiseEndsUpright() {
        val set = shipped() ?: return
        val kd = set.strip("knockdown")!!
        // the first frame is the hit: the head is snapped 25° and squashed, so its crown sits at ≈ 1.88 m — still upright, never lying
        assertTrue("knockdown: standing on its first frame", model(set, kd.first, "crown")[1] > 1.7f)
        val downCrown = model(set, kd.first + kd.event("down"), "crown")
        assertTrue("knockdown: lying on `down` (crown under 0.8 m)", downCrown[1] < 0.8f && downCrown[1] > 0.0f)
        assertTrue("knockdown: the head is off to one side when lying", abs(downCrown[0]) > 0.6f)
        val dn = set.strip("down")!!
        for (f in dn.first..dn.last) assertTrue("down frame $f lying", model(set, f, "crown")[1] < 0.8f)
        val ko = set.strip("ko")!!
        assertTrue("ko: lying on `ko`", model(set, ko.first + ko.event("ko"), "crown")[1] < 0.8f)
        val up = set.strip("getup")!!
        assertTrue("getup: lying on its first frame", model(set, up.first, "crown")[1] < 0.8f)
        assertTrue("getup: upright on `up`", model(set, up.first + up.event("up"), "crown")[1] > 1.7f)
        for (f in kd.first..kd.last) assertTrue("knockdown frame $f never goes under the canvas", model(set, f, "chin")[1] > -0.05f)
    }

    @Test
    fun theDetailLodHasABandNotAThreshold() {
        val lod = DetailLod()
        assertTrue(lod.on)
        assertTrue(lod.update(2.6f)); assertTrue(lod.update(3.2f))          // inside the band: still on
        assertTrue(!lod.update(3.5f)); assertTrue(!lod.update(3.2f))        // off beyond 3.4, and 3.2 does not bring it back
        assertTrue(lod.update(2.9f)); assertTrue(lod.update(3.3f))          // on again under 3.0, and holds through the band
        lod.reset(3.6f); assertTrue(!lod.on); lod.reset(2.6f); assertTrue(lod.on)
    }
}
