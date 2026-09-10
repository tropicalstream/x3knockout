#!/usr/bin/env python3
"""Render every voice line in tools/lines.json, each speaker through its own chain.

    tools/render_voices.py [--only SPEAKER[,SPEAKER]] [--force] [--dry-run] [--list] [--prune]

Six speakers, one renderer, because the thing that must not drift is the SEPARATION between them:
they are mixed together on one bus and the player has to know instantly who is talking. The chains
are the characterisation, so they live here beside each other where a change to one can be heard
against the others, rather than in six scripts nobody diffs.

    SYSTEM     macOS Zarvox → ring-mod / echo / bit-crush     the Protocol: a machine reading policy
    ANNOUNCER  macOS Zarvox slower → stadium band-pass + long echo, NO crusher   the institution
    CROWD      six Zarvox renders detuned ±40 cents, offset 30–90 ms, summed     the gallery
    PILOT      fish.audio (free tier header) → loudnorm −17                      the only living voice
    BUILD      the PILOT's own render → pitch −3 st, 17 Hz tremolo, chorus, crush the pilot made flat
    STRAY      a clean macOS voice, NO chain at all                              not owned by anything
    USER       a clean human voice unlike any other here → the beam chain              a person outside

Recycled ids (♻ in STORY.md) are COPIED from X3Paranoids rather than re-rendered, so the suite's
continuity is a file copy and not an impersonation.

--prune deletes clips on disk that the script no longer names. A clip nobody can reach is worse
than a missing one: it ships, it is credited, and it silently proves nothing was checked.

Writes assets/voice/<id>.m4a (SYSTEM, ANNOUNCER, CROWD), assets/voice_hero/<id>.mp3 (PILOT, BUILD),
assets/voice_stray/<id>.m4a (STRAY), plus a manifest.json of clip durations in ms per directory —
the attract sequence and every `after()` beat are timed off those numbers, never off a stopwatch.
"""
import json, os, subprocess, sys, urllib.request, urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LINES = os.path.join(ROOT, "tools", "lines.json")
CONFIG = os.path.join(ROOT, "tools", "fish.config")
BASE = os.path.join(ROOT, "app/src/main/assets")
PARANOIDS = os.path.expanduser("~/Projects/X3Paranoids/app/src/main/assets")
API = "https://api.fish.audio/v1/tts"
FREE_MODEL = "s2.1-pro-free"

SYSTEM_CHAIN = ("highpass=f=160,lowpass=f=3900,tremolo=f=34:d=0.45,aecho=0.85:0.55:11:0.28,"
                "acrusher=bits=10:mode=log:aa=1,volume=2.2,alimiter=limit=0.95")
# The announcer is the same synthesiser in a stadium, not a different machine: the SAME voice with
# the crusher taken OFF and a long room put on. That is what makes the games feel like an
# institution the Protocol merely owns, rather than the Protocol itself with a reverb.
ANNOUNCER_CHAIN = ("highpass=f=300,lowpass=f=3000,aecho=0.8:0.9:180|320:0.4|0.25,"
                   "acompressor=threshold=-16dB:ratio=3,volume=2.0,alimiter=limit=0.95")
# BUILD is the pilot compiled: the same performance, pitch-shifted down and flattened until the
# life is gone but the identity is not. If it stops sounding like the pilot, it has failed.
BUILD_CHAIN = ("asetrate=44100*0.8409,aresample=44100,atempo=1.1892,"      # -3 semitones, length kept
               "tremolo=f=17:d=0.35,chorus=0.6:0.9:50|60:0.4|0.32:0.25|0.4:2|2.3,"
               "acrusher=bits=12:mode=log:aa=1,alimiter=limit=0.95")
STRAY_VOICE, STRAY_RATE = "Ava", 168          # clean, unprocessed — the point of the character
# THE USER was written to be the owner's own voice; he declined, and he was right that it is an odd
# thing to ask of a player. It does not actually matter who it is: the USER has to read as A PERSON
# rather than a program, and the beam chain — the sound of a voice arriving through a lit tower from
# outside the system — supplies everything else. Daniel is chosen for MAXIMUM SEPARATION from the
# stray (the game's other clean human register): a different sex and a different accent, so the one
# voice that is not owned by anything can never be confused with the one voice from outside.
USER_VOICE, USER_RATE = "Daniel", 160
# Highpassed and lowpassed to a carrier band, phased and echoed as if it has travelled, and given a
# slow tremolo so it never sits perfectly still. Quiet (-19) because it arrives from far away.
USER_CHAIN = ("highpass=f=300,lowpass=f=2800,aphaser=type=t:speed=0.4:decay=0.35,"
              "aecho=0.9:0.4:60:0.22,tremolo=f=6:d=0.15,loudnorm=I=-19:TP=-2.0")
SPEAKER_DIR = {"SYSTEM": "voice", "ANNOUNCER": "voice", "CROWD": "voice",
               "PILOT": "voice_hero", "BUILD": "voice_hero", "STRAY": "voice_stray",
               "USER": "voice_user"}
SPEAKER_EXT = {"voice": "m4a", "voice_hero": "mp3", "voice_stray": "m4a", "voice_user": "m4a"}

def sh(*a):
    subprocess.run(a, check=True, capture_output=True)

def dur_ms(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                          "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip()
    return int(round(float(out) * 1000)) if out else 0

def say(text, voice, rate, aiff):
    sh("say", "-v", voice, "-r", str(rate), "-o", aiff, text)

def render_system(text, out, tmp, lid, rate=150):
    aiff = os.path.join(tmp, lid + ".aiff"); say(text, "Zarvox", rate, aiff)
    sh("ffmpeg", "-y", "-v", "error", "-i", aiff, "-af", SYSTEM_CHAIN,
       "-ac", "1", "-ar", "22050", "-c:a", "aac", "-b:a", "56k", out)

def render_announcer(text, out, tmp, lid):
    aiff = os.path.join(tmp, lid + ".aiff"); say(text, "Zarvox", 130, aiff)
    sh("ffmpeg", "-y", "-v", "error", "-i", aiff, "-af", ANNOUNCER_CHAIN,
       "-ac", "1", "-ar", "22050", "-c:a", "aac", "-b:a", "56k", out)

def render_crowd(text, out, tmp, lid, voices=6):
    """One word, six times, detuned and offset — a crowd is not a voice, it is a spread."""
    parts = []
    for i in range(voices):
        aiff = os.path.join(tmp, f"{lid}_{i}.aiff"); say(text, "Zarvox", 140 + (i - 3) * 4, aiff)
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

def render_stray(text, out, tmp, lid):
    aiff = os.path.join(tmp, lid + ".aiff"); say(text, STRAY_VOICE, STRAY_RATE, aiff)
    sh("ffmpeg", "-y", "-v", "error", "-i", aiff, "-af", "loudnorm=I=-18:TP=-1.5",
       "-ac", "1", "-ar", "24000", "-c:a", "aac", "-b:a", "64k", out)

def render_user(text, out, tmp, lid):
    aiff = os.path.join(tmp, lid + ".aiff"); say(text, USER_VOICE, USER_RATE, aiff)
    sh("ffmpeg", "-y", "-v", "error", "-i", aiff, "-af", USER_CHAIN,
       "-ac", "1", "-ar", "24000", "-c:a", "aac", "-b:a", "64k", out)

def fish_config():
    cfg = {}
    if os.path.exists(CONFIG):
        for l in open(CONFIG):
            l = l.strip()
            if "=" in l and not l.startswith("#"):
                k, v = l.split("=", 1); cfg[k.strip()] = v.strip()
    return cfg

def render_pilot(text, out, tmp, lid, cfg, build=False):
    body = json.dumps({"text": text, "reference_id": cfg["HERO_VOICE_MODEL_ID"],
                       "format": "mp3", "mp3_bitrate": 128}).encode()
    req = urllib.request.Request(API, data=body, headers={
        "Authorization": "Bearer " + cfg["FISH_API_KEY"], "Content-Type": "application/json",
        "model": os.environ.get("FISH_MODEL", FREE_MODEL)})
    raw = os.path.join(tmp, lid + "_raw.mp3")
    with urllib.request.urlopen(req, timeout=120) as r:
        open(raw, "wb").write(r.read())
    chain = (BUILD_CHAIN + ",loudnorm=I=-17:TP=-1.5") if build else "loudnorm=I=-17:TP=-1.5"
    sh("ffmpeg", "-y", "-v", "error", "-i", raw, "-af", chain,
       "-ac", "1", "-ar", "24000", "-c:a", "libmp3lame", "-b:a", "64k", out)

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
        if l["recycled"]:
            src = os.path.join(PARANOIDS, d, f"{lid}.{ext}")
            if os.path.exists(src):
                if not dry: sh("cp", src, out)
                done["recycled"] += 1
                print(f"  recycle {sp:9s} {lid}")
                continue
            print(f"  (no source for recycled {lid}; rendering)")
        if dry:
            print(f"  would render {sp:9s} {lid}: {text[:60]}"); continue
        try:
            if sp == "SYSTEM":      render_system(text, out, tmp, lid)
            elif sp == "ANNOUNCER": render_announcer(text, out, tmp, lid)
            elif sp == "CROWD":     render_crowd(text, out, tmp, lid, voices=int(lid[-1]) if lid[-1].isdigit() else 6)
            elif sp == "STRAY":     render_stray(text, out, tmp, lid)
            elif sp == "USER":      render_user(text, out, tmp, lid)
            elif sp in ("PILOT", "BUILD"): render_pilot(text, out, tmp, lid, cfg, build=(sp == "BUILD"))
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
