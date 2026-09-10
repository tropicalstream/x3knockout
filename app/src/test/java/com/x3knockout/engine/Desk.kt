package com.x3knockout.engine

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.x3knockout.SettingsStore
import org.junit.Assert.assertEquals

/**
 * THE DESK HARNESS — the real `Fight`, the real `Boxer`, the real `Clock`, a fed body and no
 * Android. Lifted out of `TwoClocksTest`, which owned it privately, when a second suite
 * ([CareerTest]) needed to drive a whole card: two copies of a harness is two harnesses that can
 * disagree about what the engine does, which is the one thing a harness may not do.
 *
 * `SettingsStore` wants a `Context` for two preference files; the stub `android.jar` under
 * `isReturnDefaultValues` lets a `ContextWrapper(null)` be built and hand back [FakePrefs] from
 * the one method that matters. Nothing else in `Fight` touches Android but `Log`, which the stub
 * swallows.
 */
object Desk {
    const val DT = 1f / 60f

    /** A `SharedPreferences` over a map: what `SettingsStore` reads and writes, without a disk or a device. */
    class FakePrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, def: String?): String? = map[key] as? String ?: def
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, def: MutableSet<String>?): MutableSet<String>? = (map[key] as? MutableSet<String>) ?: def
        override fun getInt(key: String?, def: Int): Int = map[key] as? Int ?: def
        override fun getLong(key: String?, def: Long): Long = map[key] as? Long ?: def
        override fun getFloat(key: String?, def: Float): Float = map[key] as? Float ?: def
        override fun getBoolean(key: String?, def: Boolean): Boolean = map[key] as? Boolean ?: def
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Ed()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        inner class Ed : SharedPreferences.Editor {
            override fun putString(k: String?, v: String?): SharedPreferences.Editor { map[k!!] = v; return this }
            override fun putStringSet(k: String?, v: MutableSet<String>?): SharedPreferences.Editor { map[k!!] = v; return this }
            override fun putInt(k: String?, v: Int): SharedPreferences.Editor { map[k!!] = v; return this }
            override fun putLong(k: String?, v: Long): SharedPreferences.Editor { map[k!!] = v; return this }
            override fun putFloat(k: String?, v: Float): SharedPreferences.Editor { map[k!!] = v; return this }
            override fun putBoolean(k: String?, v: Boolean): SharedPreferences.Editor { map[k!!] = v; return this }
            override fun remove(k: String?): SharedPreferences.Editor { map.remove(k); return this }
            override fun clear(): SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    class FakeContext : ContextWrapper(null) {
        private val files = HashMap<String, SharedPreferences>()
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = files.getOrPut(name ?: "") { FakePrefs() }
    }

    /** The device, as a list of what the fight asked of it. */
    class RecordingHost : GameHost {
        val said = ArrayList<String>()
        val sounds = ArrayList<Int>()
        val tracks = ArrayList<String>()
        override fun sfx(id: Int, pitch: Float, vol: Float) { sounds.add(id) }
        override fun crowd(level: Float, rate: Float) {}
        override fun say(id: String, urgent: Boolean, patienceMs: Long) { said.add(id) }
        override fun sayAll(ids: List<String>) { said.addAll(ids) }
        override fun stopVoice() {}
        override fun hero(id: String, urgent: Boolean, patienceMs: Long) { said.add(id) }
        override fun stopHero() {}
        override fun musicEnabled(on: Boolean) {}
        override fun voiceEnabled(on: Boolean) {}
        override fun recentreHead() {}
        override fun recentreYaw() {}
        override fun applyVolume(v0to10: Int) {}
        override fun voiceDurationMs(id: String): Int = 0
        override fun heroDurationMs(id: String): Int = 0
        override fun voiceBusy(): Boolean = false
        override fun quitGame() {}
        override fun music(track: String) { tracks.add(track) }
    }

    /** One fight on the desk: a fed body, sixty frames a second, the harness launch. */
    class Rig {
        val host = RecordingHost()
        val store = SettingsStore(FakeContext())
        val fight = Fight(store, host)
        var motion = 0f; var roll = 0f; var pitchG = 0f
        fun frame() { fight.feedBody(motion, roll, pitchG); fight.update(DT, 0f, 0f, true) }
        fun run(seconds: Float) { var t = 0f; while (t < seconds - 1e-4f) { frame(); t += DT } }
        /** `am start … --ef floor F --es drill peck_l`: the card, then the bell. The drill keeps him from throwing for 2.5 world seconds and takes no damage either way. */
        fun toFight(floor: Float = 0f, drill: String? = "peck_l") {
            fight.boot()
            fight.debugStart(1, floor, 0, drill, null)
            runToFight()
        }

        /** The same launch with a chosen man and a chosen health, for a card that has to be walked. */
        fun toBout(bout: Int, hp: Int, floor: Float = 0f) {
            fight.boot()
            fight.setBout(bout)
            fight.debugStart(1, floor, hp, null, null)
            runToFight()
        }

        /**
         * THE CEREMONY PATH: a coin on the title, which is the only road that runs `enterIntro`.
         * `debugStart` deliberately skips it and drops straight on the round card, so a test that
         * wants to hear the announcer has to come in the way a player does.
         */
        fun toCeremony(bout: Int) {
            store.recordsEnabled = false
            fight.setBout(bout)
            fight.boot()
            var guard = 0
            while (fight.state != State.TITLE && guard++ < 600) frame()
            fight.tap()
            frame()
            assertEquals("the coin buys the ceremony", State.INTRO, fight.state)
        }

        fun runToFight() {
            var guard = 0
            while (fight.state != State.FIGHT && guard++ < 2400) frame()
            assertEquals("the harness reaches the bell", State.FIGHT, fight.state)
        }

        /** Run until [pred] or [max] seconds; returns whether it happened, so a test can say so. */
        fun until(max: Float, pred: () -> Boolean): Boolean {
            var t = 0f
            while (t < max) { if (pred()) return true; frame(); t += DT }
            return pred()
        }
    }

}
