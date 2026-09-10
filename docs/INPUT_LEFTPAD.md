# The left temple pad — measured on the owner, 2026-09-09

Every earlier X3 app dropped the LEFT pad (kernel device `cyttsp6_mt`) as "the system volume pad".
The owner tested it for this game and reports:

- **A single tap on the left pad registers** and triggers nothing system-side.
- **A double-tap and a triple-tap on the left pad are SYSTEM MEDIA CONTROLS** (play/pause, next
  track) — RayNeo's gesture service classifies them and injects media keys.
- Left-pad **swipes** change the system volume (already known).

## What this means for a game whose left punch is a left tap

A boxer jabs twice in a row. On this hardware that is a system play/pause. So:

1. **Attribution stays with the touch stream.** `dispatchTouchEvent` carries `ev.device.name`;
   `cyttsp6` = LEFT, `cyttsp5` = RIGHT. Every left punch is counted from a cyttsp6 finger-lift, the
   same way right punches are counted from cyttsp5. Keys never decide a punch (they carry no device).
2. **The game must own the media buttons while a fight is on.** Hold an active
   `android.media.session.MediaSession` (playback state PLAYING while the app is in the foreground;
   we do play music) so `MediaSessionManager` routes `KEYCODE_MEDIA_PLAY_PAUSE` / `MEDIA_NEXT` /
   `MEDIA_PREVIOUS` / `HEADSETHOOK` to us, and swallow them in `onMediaButtonEvent` — otherwise a
   jab-jab pauses whatever the owner was listening to on another app. Also drop any `KEYCODE_MEDIA_*`
   that reaches `dispatchKeyEvent`.
3. **The media keys are at most corroboration, never a count.** They arrive late (the classifier
   must see the second tap first), so a play/pause key within the echo window of two counted left
   taps is dropped as an echo — exactly the rule the right pad already lives by with BUTTON_A/BACK.
4. **Menus never use the left pad.** Double-tap-for-settings is the RIGHT pad's BACK; the burst that
   settles menu taps only ever counts cyttsp5 lifts. A left double-tap in a menu does nothing.
5. **The lab plate shows the last pad event with its device name and any media key received**, so
   the owner can see attribution live on the first standing test.

This file is the authority on the left pad until the standing test says otherwise.
