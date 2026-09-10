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

## Measured at the desk, 2026-09-09 (uhid stand-ins; the real pads still need a head)

The pads cannot be driven from `adb` directly — `sendevent` is refused by SELinux on this user
build and `input tap` carries no device name — but the shell holds the `uhid` group, so
`tools/uhidpad.py` registers two virtual HID touch screens NAMED `cyttsp6_mt` / `cyttsp5_mt` and
the InputReader attributes their touches exactly as it attributes the real pads' (`PAD dev=…`).
This proves the app's attribution, not the pad hardware, and RayNeo's gesture service ignores the
stand-ins (no `KEY` follows their taps). What it showed:

1. **Attribution works end to end.** `PAD dev=cyttsp6_mt … act=UP travel=0 tap=true` →
   `PUNCH hand=L`; `cyttsp5_mt` → `PUNCH hand=R`; a left slide logs its `MOVE` lines and
   `tap=false` and throws nothing; a left double-tap is two jabs and no `MENU open`; a right
   double-tap in a fight is two punches and no menu (the clamp); on the title the left pad is
   silent. A non-overlapping R→L pair logs `gap=81 p90=…` and Fight's `PAIR ms=81`.
2. **Android 12's InputDispatcher keeps ONE live touch stream per display.** A second device's
   DOWN cancels the first device's gesture (the app sees `ACTION_CANCEL`) and takes over; the
   first device's lift is then dropped, and if the second is still down at that moment it is
   cancelled too. Every overlap ordering, 40 ms apart: `L↓R↓R↑L↑` → `L DOWN, L CANCEL, R DOWN,
   R UP`; `L↓R↓L↑R↑` → `L DOWN, L CANCEL, R DOWN, R CANCEL`; the same millisecond → two cancels.
   **As delivered, a two-handed slap is no punch at all.** `MainActivity.cutLeft` reads a short,
   still gesture cancelled BY THE OTHER PAD as the tap it was (`PAD … act=CUT cut=DOWN|LIFT`),
   and with that every ordering yields both punches and a `PAIR ms=` of 34–51 ms. The rule needs
   the other pad's event beside the cancel and is inert with one pad.
3. **The media buttons.** With nothing claiming them, `dumpsys media_session` shows the media
   button session is `com.android.bluetooth/BluetoothMediaBrowserService` — the phone's music over
   AVRCP, which is what a left double-tap was pausing. The activity's `MediaSession` (active and
   PLAYING while in front) takes them: `Media button session is com.x3knockout/x3knockout`, and
   `input keyevent 85` through the window is logged `KEY code=85 … echo=false hand=-` and
   dropped. The session path (`via=SESSION`) could not be exercised from the desk (`cmd
   media_session dispatch` prints its usage on this ROM); the standing test's left double-tap is
   what shows which door the real media key comes in by.
4. **Keys.** `input keyevent 96` → `KEY code=96 act=UP devId=-1 dev=Virtual echo=false hand=R`
   and one punch (`KEY→R`); a `BUTTON_A` inside 900 ms of a left tap → `echo=true hand=L` and
   nothing else; a BACK with no touch opens and closes the settings (`MENU open` / `MENU close`).
5. **`LEFT PAD` OFF**, set through the menu: a left tap logs `tap=true leftpad=OFF` and throws
   nothing; ON again, it punches.
6. **A stray `KEY 4 UP -`** (a BACK) appeared on the lab plate once before any of this ran; its
   source was not caught. Watch the `KEY` lines on the standing test for a BACK nobody pressed.
