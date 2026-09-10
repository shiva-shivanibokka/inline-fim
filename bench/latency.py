#!/usr/bin/env python3
"""Measure time-to-first-token for the plugin's FIM requests.

Mirrors what the plugin actually sends: same context window, same line snapping,
same FIM tokens, same Ollama options. It measures the model and HTTP portion of
the round trip -- everything except IDE-side context assembly and rendering,
which are microseconds of string slicing against hundreds of milliseconds of
model time.

Caret positions are sampled at end-of-line in real source files, because that is
where a completion is actually requested. Sampling uniformly over characters
would measure a lot of mid-identifier positions the plugin will never ask about.

    python bench/latency.py --files src/main/kotlin/dev/inlinefim/*.kt -n 60

No dependencies; stdlib only.
"""

import argparse
import glob
import json
import random
import statistics
import sys
import time
import urllib.error
import urllib.request

# 127.0.0.1, not localhost: on Windows the latter resolves to ::1 first and costs
# ~2s per new connection failing over to IPv4. Measured, see README.
OLLAMA_URL = "http://127.0.0.1:11434/api/generate"
DEFAULT_MODEL = "qwen2.5-coder:1.5b-base"

# Must match FimEngine.kt.
PREFIX_CHARS = 3000
SUFFIX_CHARS = 1000
STOP_TOKENS = [
    "<|endoftext|>", "<|fim_pad|>", "<|file_sep|>", "<|repo_name|>",
    "<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>",
    "<|cursor|>", "<|im_start|>", "<|im_end|>",
]


def build_context(text, caret):
    """Port of buildContext() in FimEngine.kt. Keep the two in step."""
    start = max(0, caret - PREFIX_CHARS)
    if start > 0:
        nl = text.find("\n", start)
        start = min(caret, start if nl < 0 else nl + 1)

    end = min(len(text), caret + SUFFIX_CHARS)
    if end < len(text):
        nl = text.rfind("\n", 0, end)
        end = max(caret, end if nl < 0 else nl)

    return text[start:caret], text[caret:end]


def fim_prompt(prefix, suffix):
    return f"<|fim_prefix|>{prefix}<|fim_suffix|>{suffix}<|fim_middle|>"


def time_one(prompt, model, max_tokens):
    """Return (ttft_ms, total_ms, chars) for one streamed completion."""
    payload = json.dumps({
        "model": model,
        "prompt": prompt,
        "raw": True,
        "stream": True,
        "keep_alive": "30m",
        "options": {
            "temperature": 0.2,
            "num_predict": max_tokens,
            "stop": STOP_TOKENS,
        },
    }).encode()

    req = urllib.request.Request(
        OLLAMA_URL, data=payload, headers={"Content-Type": "application/json"}
    )

    started = time.perf_counter()
    ttft = None
    chars = 0
    with urllib.request.urlopen(req, timeout=30) as resp:
        for line in resp:
            if not line.strip():
                continue
            obj = json.loads(line)
            chunk = obj.get("response", "")
            if chunk and ttft is None:
                ttft = (time.perf_counter() - started) * 1000
            chars += len(chunk)
            if obj.get("done"):
                break
    total = (time.perf_counter() - started) * 1000
    return ttft, total, chars


def sample_carets(text, count, rng):
    """End-of-line offsets, skipping blank and trivially short lines."""
    offsets, pos = [], 0
    for line in text.split("\n"):
        end = pos + len(line)
        if len(line.strip()) >= 4:
            offsets.append(end)
        pos = end + 1
    if not offsets:
        return []
    rng.shuffle(offsets)
    return offsets[:count]


def pct(values, p):
    """Nearest-rank percentile. No numpy."""
    if not values:
        return float("nan")
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, int(round(p / 100 * len(ordered) + 0.5)) - 1))
    return ordered[k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--files", nargs="+", required=True, help="source files (globs ok)")
    ap.add_argument("-n", "--samples", type=int, default=50)
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--max-tokens", type=int, default=128)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    paths = [p for pattern in args.files for p in glob.glob(pattern)]
    if not paths:
        sys.exit("no files matched")

    rng = random.Random(args.seed)
    cases = []
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        per_file = max(1, args.samples // len(paths) + 1)
        for caret in sample_carets(text, per_file, rng):
            cases.append((path, text, caret))
    rng.shuffle(cases)
    cases = cases[: args.samples]

    if not cases:
        sys.exit("no caret positions sampled")

    # Warm-up, excluded from the stats: the first call pays model load, which is a
    # one-time cost the plugin avoids by pinning keep_alive.
    prefix, suffix = build_context(cases[0][1], cases[0][2])
    try:
        time_one(fim_prompt(prefix, suffix), args.model, 8)
    except urllib.error.URLError as exc:
        sys.exit(f"cannot reach Ollama at {OLLAMA_URL}: {exc}")

    ttfts, totals, sizes, prompt_chars = [], [], [], []
    print(f"model={args.model}  samples={len(cases)}  max_tokens={args.max_tokens}")
    for i, (path, text, caret) in enumerate(cases, 1):
        prefix, suffix = build_context(text, caret)
        prompt = fim_prompt(prefix, suffix)
        try:
            ttft, total, chars = time_one(prompt, args.model, args.max_tokens)
        except Exception as exc:
            print(f"  [{i}] FAILED {path}: {exc}")
            continue
        if ttft is None:
            continue
        ttfts.append(ttft)
        totals.append(total)
        sizes.append(chars)
        prompt_chars.append(len(prefix) + len(suffix))
        print(f"  [{i:3d}] ttft={ttft:7.1f}ms total={total:7.1f}ms out={chars:4d}ch  {path}")

    if not ttfts:
        sys.exit("no successful samples")

    print()
    print(f"n = {len(ttfts)}")
    print("                      p50        p95        max")
    print(f"  time to first tok  {pct(ttfts, 50):7.1f}ms {pct(ttfts, 95):7.1f}ms {max(ttfts):7.1f}ms")
    print(f"  total              {pct(totals, 50):7.1f}ms {pct(totals, 95):7.1f}ms {max(totals):7.1f}ms")
    print(f"  context chars      {pct(prompt_chars, 50):7.0f}   {pct(prompt_chars, 95):7.0f}")
    print(f"  output chars       {statistics.median(sizes):7.0f}")


if __name__ == "__main__":
    main()
