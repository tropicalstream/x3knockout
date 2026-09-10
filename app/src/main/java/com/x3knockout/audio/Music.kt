package com.x3knockout.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/** The IO Tower cover from x3cycles, looping under the title and the maze. Never touched from the GL thread. */
class Music(private val context: Context) {
    companion object {
        private const val TAG = "X3Knockout"
        /**
         * THE CABINET'S TRACKS. The disc game's IO Tower cover came across with the scaffold and was
         * simply wrong here — a synthwave Tron theme under a boxing cartoon. These are brass and
         * drums, which is what an arcade prizefight sounds like, and each one is doing a job:
         *
         *   TITLE  a ska horn section: the gym, the swagger, the joke
         *   FIGHT  trumpet and trombone over a driving kit
         *   FIGHT3 the same brass turned aggressive for the last round
         *   COUNT  44 bpm, dark and sparse — everything drops away but the referee
         *   WIN    a fanfare slightly too pleased with itself
         *
         * Opus in an Ogg container: it loops GAPLESSLY, where an MP3's encoder padding puts a click
         * at every loop point, and it costs about a third of the source MP3. Verified decoding on
         * these glasses (`c2.android.opus.decoder`); Android has supported it in .ogg since API 29,
         * which is this project's minSdk exactly, so it was tested rather than assumed.
         *
         * Every track is Kevin MacLeod, CC BY 4.0, and the licence strings are on the CREDITS page
         * verbatim — see docs/MUSIC.md. Attribution is a condition of use, not a courtesy.
         */
        const val TITLE = "music/title.ogg"
        const val FIGHT = "music/fight.ogg"
        const val FIGHT3 = "music/fight3.ogg"
        const val COUNT = "music/count.ogg"
        const val WIN = "music/win.ogg"
        private const val TRACK = TITLE
        /**
         * HOW FAR THE TRACK DROPS UNDER A VOICE. It was 0.42 — a fifty-eight percent cut, which
         * against a default volume of 0.5 left io_tower.mp3 a rumour. The round-one note asked for
         * the duck to be softened and it never was; the track is the arena's own pulse and it
         * should step aside for a line, not leave the room for one. At 0.62 the two voices still
         * sit clearly on top of it and the music is audibly still playing underneath them, which
         * is the whole point of a duck rather than a mute.
         */
        private const val DUCK = 0.62f
    }

    @Volatile var volume = 0.5f
        set(v) { field = v; applyGain() }
    /**
     * Held down while either voice has the floor. The suite's sound effects already duck under
     * speech (Sfx.duckProvider); the music was the one thing that did not, and it is the loudest
     * continuous source in the mix — with two voices now trading lines, a track running at full
     * level under them is what turns a conversation into a wash.
     */
    @Volatile var duck = false
        set(v) { if (field != v) { field = v; applyGain() } }
    private fun applyGain() {
        val g = volume * (if (duck) DUCK else 1f)
        handler?.post { runCatching { player?.setVolume(g, g) } }
    }
    @Volatile var enabled = true
        set(v) { field = v; handler?.post { if (v) startOnThread() else stopOnThread() } }
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var player: MediaPlayer? = null
    private var afd: AssetFileDescriptor? = null

    fun load() {
        thread = HandlerThread("x3knockout-music").apply { start() }
        handler = Handler(thread!!.looper)
    }

    /**
     * Switch tracks. A boxing cabinet changes music at hard cuts — the bell, the knockdown, the
     * win — so there is no crossfade: a fade between two brass charts is mud, and the cut IS the
     * punctuation. [play] on the same track that is already playing is a no-op, so a state that
     * re-asserts its track every frame costs nothing.
     */
    fun play(asset: String) { handler?.post { if (asset != track) { track = asset; stopOnThread(); if (enabled) startOnThread() } else if (enabled) startOnThread() } }
    @Volatile private var track = TRACK

    fun play() { handler?.post { if (enabled) startOnThread() } }
    fun pause() { handler?.post { runCatching { player?.pause() } } }
    fun resume() { handler?.post { if (enabled) runCatching { player?.start() } } }

    private fun startOnThread() {
        if (player != null) { runCatching { if (player?.isPlaying == false) player?.start() }; return }
        runCatching {
            val fd = context.assets.openFd(track); afd = fd
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            val g = volume * (if (duck) DUCK else 1f)
            mp.isLooping = true; mp.setVolume(g, g); mp.prepare(); mp.start()
            player = mp
        }.onFailure { Log.w(TAG, "music start", it) }
    }

    private fun stopOnThread() {
        player?.let { runCatching { it.stop(); it.release() } }; player = null
        afd?.let { runCatching { it.close() } }; afd = null
    }

    fun release() { handler?.post { stopOnThread() }; thread?.quitSafely(); thread = null; handler = null }
}
