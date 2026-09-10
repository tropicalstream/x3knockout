package com.x3knockout

import android.content.Context
import android.os.Build

/**
 * Persistent settings, records and story flags. RayNeo detection follows guide gotcha #24 (never
 * `Build.MODEL` alone).
 *
 * THREE PREFS FILES' WORTH OF DATA, TWO OF THEM IN ONE FILE AND ONE DELIBERATELY OUTSIDE IT.
 * Settings and records share `x3knockout`; the story flags live in `x3knockout_story` because
 * `RESET SETTINGS` must be a thing the player can reach for without wondering whether it will
 * un-remember what they did to a disarmed program in Level 2 (DESIGN.md §11, STORY.md §6). The
 * separation is enforced by [resetSettings] naming its keys one at a time rather than calling
 * `clear()` — a `clear()` here would take the high score with it, and a `clear()` on the story file
 * is what the second prefs file exists to make impossible to write by accident.
 */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("x3knockout", Context.MODE_PRIVATE)
    /** The flags STORY.md §6 keeps across runs. Never touched by [resetSettings]. */
    private val story = context.getSharedPreferences("x3knockout_story", Context.MODE_PRIVATE)

    private val deviceText = listOf(Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT)
        .joinToString(" ").lowercase()
    val isRayNeoX3 = "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText
    val sbs get() = isRayNeoX3

    /**
     * RECORDS ARE REFUSED FROM A DEBUG LAUNCH. `am start … --ei level 5` puts the player halfway up
     * the ladder with three lives and no history, and a score set from there is not a score
     * (TEST_PLAN.md §1). `MainActivity` sets this before the game boots; nothing else may.
     */
    @Volatile var recordsEnabled = true

    var highScore: Int
        get() = p.getInt("hi", 0)
        set(v) { if (recordsEnabled && v > highScore) p.edit().putInt("hi", v).apply() }
    /** The highest level ever CLEARED on this device. */
    var bestLevel: Int
        get() = p.getInt("bestLevel", 0)
        set(v) { if (recordsEnabled && v > bestLevel) p.edit().putInt("bestLevel", v).apply() }
    var games: Int
        get() = p.getInt("games", 0)
        set(v) { if (recordsEnabled) p.edit().putInt("games", v).apply() }

    var music: Boolean
        get() = p.getBoolean("music", true)
        set(v) = p.edit().putBoolean("music", v).apply()
    /** 0..10 */
    var volume: Int
        get() = p.getInt("volume", 7)
        set(v) = p.edit().putInt("volume", v.coerceIn(0, 10)).apply()
    var voice: Boolean
        get() = p.getBoolean("voice", true)
        set(v) = p.edit().putBoolean("voice", v).apply()
    /**
     * The MOTION LAB plate: the body signals drawn live on the glass — the rate, the action charge,
     * the pad-blank window, the posture dial, the step counts. It exists so the owner can stand up
     * and SEE what the glasses feel, and it is the instrument every test in TEST_PLAN.md Block A is
     * read on. Default ON in the prototype (BUILD_PLAN §1), OFF at ship.
     */
    var lab: Boolean
        get() = p.getBoolean("lab", true)
        set(v) = p.edit().putBoolean("lab", v).apply()
    /**
     * THE LAB'S FLOOR OVERRIDE, 0..4 → 3 / 5 / 8 / 12 / 20 % (DESIGN.md §11).
     *
     * It only has any effect while [lab] is on: with the lab off the floor follows DIFFICULTY
     * (3 / 5 / 8 %), which is the shipping rule, and this row is not even shown. That is why there
     * is no sixth "AUTO" value — turning the lab off IS how the override is turned off, and one
     * switch that means one thing beats two switches that have to agree.
     */
    var timeFloor: Int
        get() = p.getInt("floor", 1)
        set(v) = p.edit().putInt("floor", v.coerceIn(0, 4)).apply()
    /** 0 = LOW, 1 = MEDIUM, 2 = HIGH: how hard a sidestep has to push before it counts. */
    var stepSense: Int
        get() = p.getInt("stepSense", 1)
        set(v) = p.edit().putInt("stepSense", v.coerceIn(0, 2)).apply()
    /** 0 = EASY, 1 = NORMAL, 2 = HARD. EASY until Level 1 has been cleared (DESIGN.md §8.4). */
    var difficulty: Int
        get() = p.getInt("diff", 0)
        set(v) = p.edit().putInt("diff", v.coerceIn(0, 2)).apply()
    /**
     * THE COMFORT FALLBACK, and it ships in the same build as the thing it disables (DESIGN.md
     * §5, §14.4). OFF fixes the player's platform and pays +2 deflector charges for the escape it
     * takes away, so the guaranteed pad answer to every lane survives without the vection.
     */
    var hop: Boolean
        get() = p.getBoolean("hop", true)
        set(v) = p.edit().putBoolean("hop", v).apply()
    /**
     * 0 = KNEE A (`W_REF` 1.2, γ 1.3), 1 = KNEE B (1.9 / 1.8). B ships: MOTION.md measured an
     * ordinary scan at 0.20 under B against 0.53 under A, and being taxed for reading the room is
     * the one thing this design exists to prevent. The row is lab-only and debug-only, and it goes
     * once test A2 has the owner's ruling on it.
     */
    var knee: Int
        get() = p.getInt("knee", 1)
        set(v) = p.edit().putInt("knee", v.coerceIn(0, 1)).apply()
    /**
     * The hop's duration, an index into `Clock.HOP_T_CHOICES` (0.28 / 0.35 / 0.45 / 0.60 s). Lab
     * only: test B4 picks one and then this row goes too.
     */
    var hopT: Int
        get() = p.getInt("hopT", 1)
        set(v) = p.edit().putInt("hopT", v.coerceIn(0, 3)).apply()

    // ------------------------------------------------------------------ story flags (separate file)
    /**
     * Level 2's ruling: true if the player stood still and let the referee do it, false if they
     * threw. It changes one word on the tally, the pilot's silence for the rest of the level, and
     * one line of the machine's in Level 11 — and nothing else, because the machine kills either
     * way and the game does not grade the choice (STORY.md §3, DESIGN.md §8.3).
     */
    var mercy: Boolean
        get() = story.getBoolean("mercy", false)
        set(v) = story.edit().putBoolean("mercy", v).apply()
    /** The furthest level a STORY run has reached, so the title can resume it. */
    var levelReached: Int
        get() = story.getInt("level", 1)
        set(v) { if (recordsEnabled && v > levelReached) story.edit().putInt("level", v).apply() }
    /** How many times the round has restarted on this device — the machine reads it aloud from L5. */
    var restarts: Int
        get() = story.getInt("restarts", 0)
        set(v) { if (recordsEnabled) story.edit().putInt("restarts", v).apply() }

    /** Settings only. Records are kept; the story flags are in another file entirely and untouched. */
    fun resetSettings() {
        p.edit()
            .remove("music").remove("volume").remove("voice").remove("lab").remove("floor")
            .remove("stepSense").remove("diff").remove("hop").remove("knee").remove("hopT")
            .apply()
    }
}
