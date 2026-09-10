#!/usr/bin/env python3
"""Render every voice line in tools/lines.json, each speaker through its own voice and chain.

    tools/render_voices.py [--only SPEAKER[,SPEAKER]] [--force] [--dry-run] [--list] [--prune]

SEVEN VOICES, ONE RENDERER, because the thing that must not drift is the SEPARATION between them.
They are mixed on one bus and the player has to know instantly who is talking, so the casting and
the chains live here beside each other where a change to one can be heard against the others.

    ANNOUNCER  fish.audio, one model, in a big room   the showman on the house PA
    REFEREE    fish.audio, one model, one short slap  in the ring with you, no microphone
    CORNER     macOS Grandpa, close, no room at all   the old trainer at your ear
    BOXER      fish.audio, FIVE DIFFERENT MODELS      five men, five actual voices
    CROWD      six macOS voices detuned and summed    a crowd is many people, not one voice

THE CASTING IS THE OWNER'S, BY URL (2026-09-10). The first cast was macOS's 1980s formant synths
and he was right about why that failed -- "this is an emotional sport not a reobot competition".
The second was one fish model pitch-shifted five ways, which is a costume, not a cast: a flyweight
and a heavyweight are not the same performance at different speeds. Now the announcer, the referee
and each of the five men are separate fish.audio models chosen by the owner, and THE PITCH SHIFTS
ARE GONE -- shifting a cast voice would undo the casting.

The corner is still macOS Grandpa, because no model was named for him. He is the one voice that is
never in the room with the crowd (he is 30 cm from your ear, dry, no reverb at all), which is what
keeps him legible next to five human voices.

Writes assets/voice/<id>.m4a (ANNOUNCER, REFEREE, CORNER, CROWD), assets/voice_hero/<id>.mp3
(BOXER), plus a manifest.json of clip durations in ms per directory -- the introduction sequence
and every after() beat are timed off those numbers, never off a stopwatch.
"""
import json, os, subprocess, sys, time, urllib.request, urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LINES = os.path.join(ROOT, "tools", "lines.json")
CONFIG = os.path.join(ROOT, "tools", "fish.config")
BASE = os.path.join(ROOT, "app/src/main/assets")
API = "https://api.fish.audio/v1/tts"
FREE_MODEL = "s2.1-pro-free"

# ---------------------------------------------------------------- THE CAST (see the module note)
#
# WHY EACH CHAIN IS WHAT IT IS. These are real recorded voices now, so the chain's whole job is
# WHERE THE VOICE IS STANDING, and nothing else. Band-limiting and heavy compression is what made
# the first cast sound like machinery; none of that is here.
#
#   the announcer is on a PA in a hall        -> a long double echo, gentle compression
#   the referee is three feet away, shouting  -> one short slap off the canvas, firmer compression
#   the corner is at your ear                 -> no room at all, just level
#   the man in the ring is at arm's length    -> the shortest slap there is
ANNOUNCER_CHAIN = ("aecho=0.85:0.5:150|260:0.28|0.16,acompressor=threshold=-18dB:ratio=2.5,"
                   "loudnorm=I=-16:TP=-1.5")
REFEREE_CHAIN = ("aecho=0.95:0.2:38:0.14,acompressor=threshold=-16dB:ratio=3,loudnorm=I=-15:TP=-1.5")
CORNER_CHAIN = ("acompressor=threshold=-18dB:ratio=3:attack=4,loudnorm=I=-15:TP=-1.5")
BOXER_CHAIN = ("aecho=0.96:0.15:16:0.09,loudnorm=I=-16:TP=-1.5")
CHAINS = {"ANNOUNCER": ANNOUNCER_CHAIN, "REFEREE": REFEREE_CHAIN, "CORNER": CORNER_CHAIN,
          "BOXER": BOXER_CHAIN}

# The fish.audio models the owner chose, by reference id. A speaker in here is rendered by fish;
# anything else falls through to macOS `say` with the voice named in VOICES.
FISH_VOICE = {
    "ANNOUNCER": "ac192aa6102d4d669e1af4e4351cf89d",
    "REFEREE": "1443bdae8a9546d6bb451cc4816cfdfd",
}
# THE FIVE MEN, five models, in card order. The id suffix on a clip picks the man: `that_all_anvil`
# is Duke Odell's own voice, and a BOXER clip with no suffix is the fallback the engine reaches for
# when a man has no line of his own, so it is rendered in the first man's voice and never anyone
# else's (VOICE.md section 4: a missing crow is a silent fairness bug, not silence).
BOXER_VOICE = {
    "rooster": "1bf2dee1ca2848b5bc0580a4d9301341",
    "sardine": "97050f3ee6dd49f8b2b58de51ed21269",
    "anvil": "44db4aafb5ff45a7b268beaeead5dec7",
    "silk": "a5f60dc6887548c2bec5190c95d26dee",
    "metronome": "40943e1f497c4256b23d7bc29b0e26f6",
}
FALLBACK_BOXER = "rooster"
# The announcer names the men too, so an `intro_anvil` is the ANNOUNCER's model, not the Anvil's.
# That is why the fighter suffix is only ever read for the BOXER speaker.
VOICES = {"CORNER": ("Grandpa", 176)}
# A crowd is MANY PEOPLE. Six different voices at slightly different rates, detuned and offset.
CROWD_VOICES = [("Eddy", 150), ("Sandy", 146), ("Junior", 156), ("Shelley", 144), ("Flo", 152), ("Karen", 148)]
SPEAKER_DIR = {"ANNOUNCER": "voice", "REFEREE": "voice", "CORNER": "voice", "CROWD": "voice",
               "BOXER": "voice_hero"}
SPEAKER_EXT = {"voice": "m4a", "voice_hero": "mp3"}

def sh(*a):
    subprocess.run(a, check=True, capture_output=True)

def dur_ms(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                          "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip()
    return int(round(float(out) * 1000)) if out else 0

def say(text, voice, rate, aiff):
    sh("say", "-v", voice, "-r", str(rate), "-o", aiff, text)

def render_say(sp, text, out, tmp, lid):
    voice, rate = VOICES[sp]
    aiff = os.path.join(tmp, lid + ".aiff"); say(text, voice, rate, aiff)
    sh("ffmpeg", "-y", "-v", "error", "-i", aiff, "-af", CHAINS[sp],
       "-ac", "1", "-ar", "24000", "-c:a", "aac", "-b:a", "64k", out)

def fish_id(sp, lid):
    """Which fish model says this line: the speaker's, or -- for a BOXER -- the man's."""
    if sp != "BOXER":
        return FISH_VOICE[sp]
    for k, v in BOXER_VOICE.items():
        if lid == k or lid.endswith("_" + k):
            return v
    return BOXER_VOICE[FALLBACK_BOXER]

def fish_tts(text, reference_id, cfg, raw, tries=4):
    """One call to fish.audio, retried on a transient failure — a 100-clip run must not die on one."""
    body = json.dumps({"text": text, "reference_id": reference_id,
                       "format": "mp3", "mp3_bitrate": 128}).encode()
    last = None
    for n in range(tries):
        req = urllib.request.Request(API, data=body, headers={
            "Authorization": "Bearer " + cfg["FISH_API_KEY"], "Content-Type": "application/json",
            "model": os.environ.get("FISH_MODEL", FREE_MODEL)})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
            if len(data) < 512:
                raise RuntimeError(f"{len(data)} bytes back — that is not audio")
            open(raw, "wb").write(data)
            return
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code} {e.read()[:160]!r}"
            if e.code not in (408, 429, 500, 502, 503, 504):
                raise RuntimeError(last)
        except Exception as e:                       # timeouts, resets, short reads
            last = str(e)[:160]
        time.sleep(1.5 * (n + 1))
    raise RuntimeError(f"fish.audio failed {tries}x: {last}")

def render_fish(sp, text, out, tmp, lid, cfg):
    """The three cast speakers. Output stays mp3 for the hero track, m4a for the system track."""
    raw = os.path.join(tmp, lid + "_raw.mp3")
    fish_tts(text, fish_id(sp, lid), cfg, raw)
    codec = ["-c:a", "libmp3lame"] if SPEAKER_DIR[sp] == "voice_hero" else ["-c:a", "aac"]
    sh("ffmpeg", "-y", "-v", "error", "-i", raw, "-af", CHAINS[sp],
       "-ac", "1", "-ar", "24000", *codec, "-b:a", "64k", out)

def render_crowd(text, out, tmp, lid, voices=6):
    """One word, six times, detuned and offset — a crowd is not a voice, it is a spread."""
    parts = []
    for i in range(voices):
        cv, cr = CROWD_VOICES[i % len(CROWD_VOICES)]
        aiff = os.path.join(tmp, f"{lid}_{i}.aiff"); say(text, cv, cr, aiff)
        wav = os.path.join(tmp, f"{lid}_{i}.wav")
        cents = (i - 2.5) / 2.5 * 40.0
        ratio = 2 ** (cents / 1200.0)
        delay = 30 + i * 12
        sh("ffmpeg", "-y", "-v", "error", "-i", aiff,
           "-af", f"asetrate=44100*{ratio:.6f},aresample=44100,adelay={delay}|{delay}", wav)
        parts.append(wav)
    args = ["ffmpeg", "-y", "-v", "error"]
    for p in parts: args += ["-i", p]
    args += ["-filter_complex", f"amix=inputs={len(parts)}:duration=longest:normalize=0,"
             "highpass=f=200,lowpass=f=3400,volume=1.6,alimiter=limit=0.95",
             "-ac", "1", "-ar", "22050", "-c:a", "aac", "-b:a", "56k", out]
    sh(*args)

def fish_config():
    cfg = {}
    if os.path.exists(CONFIG):
        for l in open(CONFIG):
            l = l.strip()
            if "=" in l and not l.startswith("#"):
                k, v = l.split("=", 1); cfg[k.strip()] = v.strip()
    return cfg

def main():
    args = sys.argv[1:]
    only = None
    if "--only" in args:
        only = set(args[args.index("--only") + 1].upper().split(","))
    force, dry, listing, prune = "--force" in args, "--dry-run" in args, "--list" in args, "--prune" in args
    data = json.load(open(LINES))
    lines = data["lines"]
    if only: lines = [l for l in lines if l["speaker"] in only]
    if listing:
        for l in lines: print(f"{l['speaker']:9s} {l['id']:24s} {l['text']}")
        return 0
    tmp = os.path.join("/tmp", "x3knockout_voice"); os.makedirs(tmp, exist_ok=True)
    cfg = fish_config()
    done = {"rendered": 0, "recycled": 0, "skipped": 0, "failed": []}
    for d in set(SPEAKER_DIR.values()): os.makedirs(os.path.join(BASE, d), exist_ok=True)
    for l in lines:
        lid, sp, text = l["id"], l["speaker"], l["text"]
        d = SPEAKER_DIR[sp]; ext = SPEAKER_EXT[d]
        out = os.path.join(BASE, d, f"{lid}.{ext}")
        if os.path.exists(out) and not force:
            done["skipped"] += 1; continue
        if dry:
            print(f"  would render {sp:9s} {lid}: {text[:60]}"); continue
        try:
            if sp == "CROWD": render_crowd(text, out, tmp, lid)
            elif sp in FISH_VOICE or sp == "BOXER": render_fish(sp, text, out, tmp, lid, cfg)
            else: render_say(sp, text, out, tmp, lid)
            done["rendered"] += 1
            print(f"  {sp:9s} {lid:24s} {dur_ms(out):5d} ms  {text[:48]}")
        except Exception as ex:
            done["failed"].append((lid, sp, str(ex)[:120]))
            print(f"  FAILED  {sp:9s} {lid}: {str(ex)[:120]}")
    if prune and not dry:
        want = {l["id"] for l in data["lines"]}
        for d in sorted(set(SPEAKER_DIR.values())):
            dd = os.path.join(BASE, d)
            if not os.path.isdir(dd): continue
            for f in sorted(os.listdir(dd)):
                if f.endswith((".m4a", ".mp3")) and os.path.splitext(f)[0] not in want:
                    os.remove(os.path.join(dd, f)); print(f"  pruned {d}/{f}")
    # manifests: every directory gets one, covering whatever is actually on disk
    if not dry:
        for d in sorted(set(SPEAKER_DIR.values())):
            dd = os.path.join(BASE, d)
            man = {}
            for f in sorted(os.listdir(dd)):
                if f.endswith((".m4a", ".mp3")):
                    man[os.path.splitext(f)[0]] = dur_ms(os.path.join(dd, f))
            json.dump(man, open(os.path.join(dd, "manifest.json"), "w"), indent=1)
            print(f"{d}/manifest.json: {len(man)} clips")
    print(f"rendered {done['rendered']}, recycled {done['recycled']}, already present {done['skipped']}, failed {len(done['failed'])}")
    for lid, sp, e in done["failed"]: print(f"  ! {sp} {lid}: {e}")
    return 1 if done["failed"] else 0

if __name__ == "__main__":
    sys.exit(main())
