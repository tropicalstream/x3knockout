#!/usr/bin/env python3
"""VOICE.md is the script; this makes it the BUILD INPUT.

Every voice line in the game is a row in a markdown table in docs/VOICE.md whose first cell is an
id in backticks and whose second is a speaker. Hand-copying 186 of those into six render scripts is
how a game ends up with a clip nobody can trace to a line — so nothing is hand-copied: this parses
the document and writes tools/lines.json, and the render scripts read only that.

    tools/extract_lines.py [--check]

A leading ♻ on the id means the clip already exists in X3Paranoids and is COPIED, not re-rendered —
the suite's continuity, and 21 fewer API calls.

Output: {"lines": [{id, speaker, text, recycled, section}], "by_speaker": {...counts}}
Ids must be unique; a duplicate id with different text is an error, because two different lines
sharing a filename means one of them is silently unreachable.
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STORY = os.path.join(ROOT, "docs", "VOICE.md")
OUT = os.path.join(ROOT, "tools", "lines.json")

SPEAKERS = {"ANNOUNCER", "REFEREE", "CORNER", "BOXER", "CROWD"}
# id may be prefixed by the recycle mark; ids like `round_1` / `round_2` share one row
ROW = re.compile(r"^\|\s*(♻)?\s*((?:`[a-z0-9_]+`\s*/?\s*)+)\|\s*([A-Z ()/+,–-]+?)\s*\|\s*(.*?)\s*\|")
IDS = re.compile(r"`([a-z0-9_]+)`")

def parse():
    lines, section, errors = [], "", []
    seen = {}
    for raw in open(STORY, encoding="utf-8"):
        if raw.startswith("#"):
            section = raw.lstrip("#").strip()
            continue
        m = ROW.match(raw)
        if not m:
            continue
        recycled, idcell, speaker_cell, text = m.groups()
        ids = IDS.findall(idcell)
        if not ids:
            continue
        # A row whose text is only a section pointer ("(4.2)") is a cross-reference: the same line
        # listed again where it fires. The line is defined once, elsewhere; skip the pointer.
        if re.fullmatch(r"\(\d+(\.\d+)*\)", text.strip()):
            continue
        # `crowd_comply_3` / `_2` / `_1` — a trailing shorthand takes the first id's stem
        stem = re.sub(r"[0-9]+$", "", ids[0])
        ids = [i if not i.startswith("_") else stem + i.lstrip("_") for i in ids]
        # a cell may name several speakers ("SYSTEM (L1-3), ANNOUNCER (L4+)"): the FIRST is the
        # renderer; the rest are the same words in another voice and get their own ids downstream
        sp = next((s for s in SPEAKERS if speaker_cell.startswith(s)), None)
        if sp is None:
            continue
        # a row naming several ids is one line per id, and the text usually carries a matching
        # "/"-separated list; split it when the counts agree, else give every id the whole text
        parts = [p.strip() for p in text.split(" / ")] if len(ids) > 1 else [text]
        if len(parts) != len(ids):
            parts = [text] * len(ids)
        for i, lid in enumerate(ids):
            body = parts[i].strip()
            if not body:
                continue
            if lid in seen and seen[lid] != body:
                errors.append(f"duplicate id `{lid}` with different text:\n    {seen[lid]}\n    {body}")
            seen[lid] = body
            lines.append({"id": lid, "speaker": sp, "text": body,
                          "recycled": bool(recycled), "section": section})
    # dedupe, keeping the first
    out, have = [], set()
    for l in lines:
        if l["id"] in have:
            continue
        have.add(l["id"]); out.append(l)
    return out, errors

def main():
    lines, errors = parse()
    by = {}
    for l in lines:
        by.setdefault(l["speaker"], {"total": 0, "recycled": 0})
        by[l["speaker"]]["total"] += 1
        by[l["speaker"]]["recycled"] += 1 if l["recycled"] else 0
    for e in errors:
        print("ERROR:", e, file=sys.stderr)
    print(f"{len(lines)} lines")
    for sp, c in sorted(by.items(), key=lambda kv: -kv[1]["total"]):
        print(f"  {sp:10s} {c['total']:4d}  ({c['recycled']} recycled)")
    if "--check" not in sys.argv:
        json.dump({"lines": lines, "by_speaker": by}, open(OUT, "w"), indent=1, ensure_ascii=False)
        print("->", OUT)
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main())
