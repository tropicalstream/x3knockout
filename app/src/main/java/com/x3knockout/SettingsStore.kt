package com.x3knockout

import android.content.Context
import android.os.Build

/**
 * Persistent settings and records. RayNeo detection follows guide gotcha #24 (never
 * `Build.MODEL` alone).
 *
 * TWO PREFS FILES. Settings and records share `x3knockout`; the champion flag lives in
 * `x3knockout_story` because `RESET SETTINGS` must be a thing the player can reach for without
 * wondering whether it will un-remember the win (DESIGN.md §11: "the story prefs file is kept for
 * the champion flag"). The separation is enforced by [resetSettings] naming its keys one at a time
 * rather than calling `clear()` — a `clear()` here would take the high score with it.
 *
 * THE ROWS ARE DESIGN.md §11, in its order: MUSIC · VOLUME · VOICE · DIFFICULTY · DODGE SENSE ·
 * STEP SENSE · LEFT PAD · CAPTIONS · MOTION LAB · (lab) TIME FLOOR · HANG · PITCH COMP · DRILL ·
 * (lab, debug) KNEE · CREDITS · RESET SETTINGS · QUIT. `HOP` and `HOP T` from x3discs are gone:
 * the step is the swipe and the body, and its duration ladder lives in `Clock.STEP_T_CHOICES` for
 * the lab alone.
 */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("x3knockout", Context.MODE_PRIVATE)
    /** The champion flag, and nothing else. Never touched by [resetSettings]. */
    private val story = context.getSharedPreferences("x3knockout_story", Context.MODE_PRIVATE)

    private val deviceText = listOf(Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT)
        .joinToString(" ").lowercase()
    val isRayNeoX3 = "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText
    val sbs get() = isRayNeoX3

    /**
     * RECORDS ARE REFUSED FROM A DEBUG LAUNCH. `am start … --ei round 3 --ei hp 20` puts the
     * player two rounds in against a boxer on his last legs, and a score set from there is not a
     * score (TEST.md §1, "Records discipline"). `MainActivity` sets this before the fight boots;
     * nothing else may.
     */
    @Volatile var recordsEnabled = true

    // ------------------------------------------------------------------ records (kept by RESET)
    var highScore: Int
        get() = p.getInt("hi", 0)
        set(v) { if (recordsEnabled && v > highScore) p.edit().putInt("hi", v).apply() }
    /**
     * THE FASTEST KNOCKOUT, real milliseconds from the first bell; 0 = none yet. `BEST KO 1:23` on
     * the title's records line (DESIGN.md §5.4) — the one place REAL time is judged, and the
     * record the score chase is really about.
     */
    var bestKoMs: Int
        get() = p.getInt("bestKo", 0)
        set(v) { if (recordsEnabled && v > 0 && (bestKoMs == 0 || v < bestKoMs)) p.edit().putInt("bestKo", v).apply() }
    /**
     * THE FURTHEST RUNG BEATEN, so the card is where the player left it next launch. It lives with
     * the RECORDS rather than the settings: RESET SETTINGS must not quietly demote somebody to the
     * Rooster, and a debug launch must not promote them (the store refuses records from one).
     */
    var boutReached: Int
        get() = p.getInt("bout", 0)
        set(v) { if (recordsEnabled && v > boutReached) p.edit().putInt("bout", v).apply() }
    var fights: Int
        get() = p.getInt("fights", 0)
        set(v) { if (recordsEnabled) p.edit().putInt("fights", v).apply() }
    /**
     * Has a knockdown ever been scored on this device? DIFFICULTY defaults to EASY until it has
     * (DESIGN.md §5.6, the suite's "EASY until the first clear"), then to NORMAL — unless the
     * player has set the row by hand, in which case their choice stands either way.
     */
    var knockdownScored: Boolean
        get() = p.getBoolean("kd1", false)
        set(v) { if (recordsEnabled && v) p.edit().putBoolean("kd1", true).apply() }

    // ------------------------------------------------------------------ settings
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
    /** 0 = EASY, 1 = NORMAL, 2 = HARD (DESIGN.md §5.6). See [knockdownScored] for the default. */
    var difficulty: Int
        get() = p.getInt("diff", if (knockdownScored) 1 else 0)
        set(v) = p.edit().putInt("diff", v.coerceIn(0, 2)).apply()
    /**
     * 0 = LOW, 1 = MEDIUM, 2 = HIGH: the nod and the tilt that count as a full duck and a full
     * slip — 29° / 22° (the on-disk numbers), 22° / 16° (ships), 16° / 12°. A boxing round asks
     * for a dodge every few seconds, not once a level, and the neck decides this row standing up
     * (DESIGN.md §1.3, TEST.md A2 / D3).
     */
    var dodgeSense: Int
        get() = p.getInt("dodgeSense", 1)
        set(v) = p.edit().putInt("dodgeSense", v.coerceIn(0, 2)).apply()
    /** 0 = LOW, 1 = MEDIUM, 2 = HIGH: how hard a sidestep has to push before it counts. */
    var stepSense: Int
        get() = p.getInt("stepSense", 1)
        set(v) = p.edit().putInt("stepSense", v.coerceIn(0, 2)).apply()
    /**
     * THE FALLBACK SHIPS IN THE SAME BUILD (DESIGN.md §1.5). OFF = right-pad taps alternate hands
     * by rhythm and swipe UP is the special; the fight is fully playable one-handed and only the
     * "both temples" beat is lost. It goes OFF if TEST.md L1–L3 fail the left pad.
     *
     * What OFF means at the pad (`MainActivity.leftPad`): the left temple is still READ — every
     * event still logs its `PAD dev=cyttsp6_mt` line, still blanks the step recogniser, and a tap
     * still stamps the key-echo clock so a phantom key the service injects for it is still
     * dropped (that phantom is one of the three failures OFF exists for) — but it throws nothing
     * and it pairs with nothing. ON is the default because INPUT_LEFTPAD.md measured a single
     * left tap reaching the app with nothing system-side following it.
     */
    var leftPad: Boolean
        get() = p.getBoolean("leftPad", true)
        set(v) = p.edit().putBoolean("leftPad", v).apply()
    /** 0 = AUTO (the answer word for the first two of each attack), 1 = ON (always), 2 = OFF. */
    var captions: Int
        get() = p.getInt("captions", 0)
        set(v) = p.edit().putInt("captions", v.coerceIn(0, 2)).apply()
    /**
     * The MOTION LAB plate: the body signals drawn live on the glass, the lab rows below, and
     * the DRILL instrument. It exists so the owner can stand up and SEE what the glasses feel,
     * and it is the instrument every test in TEST.md §2 is read on. Default ON in the prototype,
     * OFF at ship.
     */
    var lab: Boolean
        get() = p.getBoolean("lab", true)
        set(v) = p.edit().putBoolean("lab", v).apply()
    /**
     * THE LAB'S FLOOR OVERRIDE, 0..4 → 3 / 5 / 8 / 12 / 20 % — the BASE floor only (the title,
     * the menus, and what the fight's own floor table falls back to). It only has any effect
     * while [lab] is on; there is no sixth "AUTO" value because turning the lab off IS how the
     * override is turned off.
     */
    var timeFloor: Int
        get() = p.getInt("floor", 1)
        set(v) = p.edit().putInt("floor", v.coerceIn(0, 4)).apply()
    /** Lab only: `HANG_T` 0 = 0.5 s, 1 = 0.8 s, 2 = 1.2 s — overrides the difficulty's hang (DESIGN.md §2.2, TEST.md T2). */
    var hang: Int
        get() = p.getInt("hang", 1)
        set(v) = p.edit().putInt("hang", v.coerceIn(0, 2)).apply()
    /**
     * Lab only: how much of a nod the view un-pitches during a duck, 0 = 0 / 1 = 0.5 / 2 = 0.7
     * (DESIGN.md §14.3). Ships at 0 — a view partly head-locked in pitch is a vestibular
     * mismatch nobody has tested here — and the owner rules on it (TEST.md V5).
     */
    var pitchComp: Int
        get() = p.getInt("pitchComp", 0)
        set(v) = p.edit().putInt("pitchComp", v.coerceIn(0, 2)).apply()
    /**
     * 0 = KNEE A (`W_REF` 1.2, γ 1.3), 1 = KNEE B (1.9 / 1.8). B ships: MOTION.md measured an
     * ordinary scan at 0.20 under B against 0.53 under A. The row is lab-only and debug-only, and
     * it goes once test A2 has the owner's ruling on it.
     */
    var knee: Int
        get() = p.getInt("knee", 1)
        set(v) = p.edit().putInt("knee", v.coerceIn(0, 1)).apply()
    /**
     * THE DRILL — lab only, and deliberately NOT persisted: OFF / PECK L / PECK R / WING R /
     * WING L / SUNRISE / ALL (the indices of `Boxer.Drill`). He throws only that attack every
     * 2.5 world seconds with no damage either way — the standing test's instrument (TEST.md
     * Blocks T, V, F). A drill that survived a relaunch would put the next session's first fight
     * into a harness nobody asked for, so it lives for one process and the `--es drill` launch.
     */
    @Volatile var drill = 0

    // ------------------------------------------------------------------ the champion (separate file)
    /** Won by knockout at least once: `YOU` becomes `CHAMPION` on the plate (DESIGN.md §8). */
    var champion: Boolean
        get() = story.getBoolean("champion", false)
        set(v) { if (recordsEnabled && v) story.edit().putBoolean("champion", true).apply() }

    /** Settings only. Records are kept; the champion flag is in another file entirely and untouched. */
    fun resetSettings() {
        p.edit()
            .remove("music").remove("volume").remove("voice").remove("diff").remove("dodgeSense")
            .remove("stepSense").remove("leftPad").remove("captions").remove("lab").remove("floor")
            .remove("hang").remove("pitchComp").remove("knee")
            .apply()
        drill = 0
    }
}
