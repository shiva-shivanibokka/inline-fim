#!/usr/bin/env python3
"""Offline completion quality: can the model reconstruct code that was removed?

WHAT THIS IS NOT
================
This is not an accept rate. Accept rate means a human looked at a suggestion and
decided it was worth keeping, and nothing here can stand in for that. This is an
offline proxy: hold out a piece of real code, ask the model to fill the hole, and
compare the answer to what the file actually said.

Ground truth here is "what this author actually wrote", which is a strict judge.
A completion can be perfectly good and score zero because the author happened to
name the variable differently. So read exact-match as a floor on quality, not an
estimate of usefulness -- the real accept rate should be HIGHER than this.

METHOD
======
For each sampled line:
  1. Pick a caret somewhere inside the line, after the indentation.
  2. Ground truth is the rest of that line.
  3. Prefix is everything before the caret. Suffix starts at the NEXT line, so
     the model cannot see the answer it is being asked for.
  4. Ask for a completion, compare its first line to the held-out remainder.

Reported:
  exact         - first line matches the held-out text character for character
  normalised    - matches once whitespace is collapsed
  prefix        - one is a prefix of the other, i.e. the model was going the
                  right way and was cut off, or overshot correctly
  nonempty      - the model proposed anything at all

    python bench/offline_eval.py --files "src/main/kotlin/dev/inlinefim/*.kt" -n 80

No dependencies; stdlib only.
"""

import argparse
import glob
import json
import random
import re
import sys
import time
import urllib.error
import urllib.request

OLLAMA_URL = "http://127.0.0.1:11434/api/generate"
DEFAULT_MODEL = "qwen2.5-coder:1.5b-base"

PREFIX_CHARS = 3000
SUFFIX_CHARS = 1000
STOP_TOKENS = [
    "<|endoftext|>", "<|fim_pad|>", "<|file_sep|>", "<|repo_name|>",
    "<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>",
    "<|cursor|>", "<|im_start|>", "<|im_end|>",
]


def window(text, caret, suffix_from):
    """Prefix ends at the caret; suffix starts at suffix_from, both line-snapped."""
    start = max(0, caret - PREFIX_CHARS)
    if start > 0:
        nl = text.find("\n", start)
        start = min(caret, start if nl < 0 else nl + 1)

    end = min(len(text), suffix_from + SUFFIX_CHARS)
    if end < len(text):
        nl = text.rfind("\n", 0, end)
        end = max(suffix_from, end if nl < 0 else nl)

    return text[start:caret], text[suffix_from:end]


def complete(prefix, suffix, model, max_tokens):
    payload = json.dumps({
        "model": model, "prompt":
            f"<|fim_prefix|>{prefix}<|fim_suffix|>{suffix}<|fim_middle|>",
        "raw": True, "stream": False, "keep_alive": "30m",
        "options": {"temperature": 0.2, "num_predict": max_tokens, "stop": STOP_TOKENS},
    }).encode()
    req = urllib.request.Request(
        OLLAMA_URL, data=payload, headers={"Content-Type": "application/json"}
    )
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=60) as resp:
        out = json.load(resp).get("response", "")
    return out, (time.perf_counter() - t0) * 1000


def norm(s):
    return re.sub(r"\s+", " ", s).strip()


def sample_cases(text, count, rng):
    """(caret, ground_truth, suffix_from) for lines worth predicting."""
    cases, pos = [], 0
    for line in text.split("\n"):
        line_start, line_end = pos, pos + len(line)
        pos = line_end + 1

        stripped = line.strip()
        # Skip blanks, comment-only lines, and lines too short to be interesting.
        if len(stripped) < 12 or stripped.startswith(("#", "//", "*", "/*")):
            continue

        indent = len(line) - len(line.lstrip())
        # Caret somewhere in the middle of the code on this line, never inside
        # the leading whitespace and never at the very end (nothing to predict).
        lo, hi = line_start + indent + 1, line_end - 1
        if hi <= lo:
            continue
        caret = rng.randint(lo, hi)
        cases.append((caret, text[caret:line_end], line_end))

    rng.shuffle(cases)
    return cases[:count]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--files", nargs="+", required=True)
    ap.add_argument("-n", "--samples", type=int, default=60)
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--max-tokens", type=int, default=64)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--show", type=int, default=8, help="print this many examples")
    args = ap.parse_args()

    paths = [p for pattern in args.files for p in glob.glob(pattern)]
    if not paths:
        sys.exit("no files matched")

    rng = random.Random(args.seed)
    cases = []
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        for caret, truth, suffix_from in sample_cases(
            text, max(1, args.samples // len(paths) + 1), rng
        ):
            cases.append((path, text, caret, truth, suffix_from))
    rng.shuffle(cases)
    cases = cases[: args.samples]
    if not cases:
        sys.exit("no cases sampled")

    stats = dict(exact=0, normalised=0, prefix=0, nonempty=0)
    latencies, shown = [], 0

    print(f"model={args.model}  cases={len(cases)}  files={len(paths)}\n")

    for path, text, caret, truth, suffix_from in cases:
        prefix, suffix = window(text, caret, suffix_from)
        try:
            raw, ms = complete(prefix, suffix, args.model, args.max_tokens)
        except urllib.error.URLError as exc:
            sys.exit(f"cannot reach Ollama at {OLLAMA_URL}: {exc}")
        except Exception as exc:
            print(f"  request failed: {exc}")
            continue

        latencies.append(ms)
        got = raw.split("\n")[0]

        if got.strip():
            stats["nonempty"] += 1
        if got == truth:
            stats["exact"] += 1
        if norm(got) == norm(truth):
            stats["normalised"] += 1
        a, b = norm(got), norm(truth)
        if a and b and (a.startswith(b) or b.startswith(a)):
            stats["prefix"] += 1

        if shown < args.show:
            shown += 1
            mark = "OK  " if norm(got) == norm(truth) else "MISS"
            print(f"  [{mark}] {path.split(chr(92))[-1].split('/')[-1]}")
            print(f"         want: {truth!r}")
            print(f"         got : {got!r}")

    n = len(latencies)
    if not n:
        sys.exit("no successful cases")

    print(f"\nn = {n}")
    for key in ("exact", "normalised", "prefix", "nonempty"):
        print(f"  {key:12s} {stats[key]:4d}  {100 * stats[key] / n:5.1f}%")
    print(f"\n  median latency {sorted(latencies)[n // 2]:.0f}ms")
    print("\nGround truth is the original author's exact text, so these are a floor "
          "on quality,\nnot an estimate of how often a human would press Tab.")


if __name__ == "__main__":
    main()
