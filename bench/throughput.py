#!/usr/bin/env python3
"""Is generation memory-bandwidth bound, and what does that imply for big models?

WHY THIS EXISTS
===============
"Use a bigger model" is the first thing anyone says about a 1.5B completion
model, and the README answers it with arithmetic. This script supplies the one
constant that arithmetic rests on, so the answer is not hand-waving.

THE CLAIM
=========
Generating one token requires reading every active weight out of memory exactly
once, so generation speed is bounded by memory bandwidth rather than by compute:

    tokens/sec  ~=  effective bandwidth (GB/s)  /  model size (GB)

If that is really the binding constraint, then tok/s * size should be roughly
CONSTANT across model sizes on the same machine -- that constant being the
effective bandwidth. If it is not constant, the model of the problem is wrong
and the extrapolation in the README should not be trusted.

So this measures three sizes and prints the product. The product is the result;
the individual speeds are just how it is obtained.

Token counts come from Ollama's own eval_count / eval_duration rather than from
wall-clock and character counts, so there is no chars-per-token fudge factor.

Only generation is measured. Prefill is deliberately not reported: it is
compute-bound rather than bandwidth-bound, and Ollama caches prompt prefixes, so
a second run against the same files reports a prompt_eval_count near zero and an
absurd prefill rate. bench/latency.py covers the prefill side honestly, from a
cold prompt. Reporting a number that is only true on a cold cache would be worse
than reporting none.

Sizes are the on-disk quantised sizes reported by `ollama list`, which is what
actually gets streamed from memory.

    python bench/throughput.py --files "src/main/kotlin/dev/inlinefim/*.kt"

No dependencies; stdlib only.
"""

import argparse
import glob
import json
import random
import sys
import urllib.error
import urllib.request

OLLAMA = "http://127.0.0.1:11434"

# Quantised on-disk size in GB, from `ollama list`. Update if you re-pull at a
# different quantisation -- the whole point is size-as-streamed, not parameter
# count.
SIZES_GB = {
    "qwen2.5-coder:0.5b-base": 0.531,
    "qwen2.5-coder:1.5b-base": 0.986,
    "qwen2.5-coder:3b-base": 1.900,
}

PREFIX_CHARS = 3000
SUFFIX_CHARS = 1000


def generate(model, prefix, suffix, max_tokens):
    """One non-streamed completion. Returns Ollama's own token/duration counters."""
    payload = json.dumps({
        "model": model,
        "prompt": f"<|fim_prefix|>{prefix}<|fim_suffix|>{suffix}<|fim_middle|>",
        "raw": True,
        "stream": False,
        "keep_alive": "5m",
        "options": {"temperature": 0.2, "num_predict": max_tokens},
    }).encode()
    req = urllib.request.Request(
        f"{OLLAMA}/api/generate", data=payload,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.load(resp)


def sample_carets(texts, count, rng):
    """End-of-line offsets in real source, the positions completion is asked at."""
    carets = []
    for text in texts:
        pos = 0
        for line in text.split("\n"):
            end = pos + len(line)
            if len(line.strip()) >= 8:
                carets.append((text, end))
            pos = end + 1
    rng.shuffle(carets)
    return carets[:count]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--files", nargs="+", required=True)
    ap.add_argument("-n", "--samples", type=int, default=12)
    ap.add_argument("--models", nargs="+", default=list(SIZES_GB))
    ap.add_argument("--max-tokens", type=int, default=128)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    paths = [p for pattern in args.files for p in glob.glob(pattern)]
    if not paths:
        sys.exit("no files matched")
    texts = [open(p, encoding="utf-8", errors="replace").read() for p in paths]

    carets = sample_carets(texts, args.samples, random.Random(args.seed))
    if not carets:
        sys.exit("no caret positions sampled")

    print(f"{len(carets)} caret positions from {len(paths)} files\n")
    print(f"{'model':28s} {'weights':>9s} {'generation':>12s} {'tok/s x GB':>11s}")
    print("-" * 64)

    rows = []
    for model in args.models:
        gen_tok = gen_s = 0
        for text, caret in carets:
            prefix = text[max(0, caret - PREFIX_CHARS):caret]
            suffix = text[caret:caret + SUFFIX_CHARS]
            try:
                d = generate(model, prefix, suffix, args.max_tokens)
            except urllib.error.URLError as exc:
                sys.exit(f"cannot reach Ollama at {OLLAMA}: {exc}")
            gen_tok += d.get("eval_count", 0)
            gen_s += d.get("eval_duration", 0) / 1e9

        if not gen_s:
            print(f"{model:28s} produced no tokens, skipped")
            continue

        gen_rate = gen_tok / gen_s
        size = SIZES_GB.get(model)
        product = f"{gen_rate * size:8.0f}" if size else "       ?"
        size_s = f"{size:6.2f} GB" if size else "       ?"
        print(f"{model:28s} {size_s:>9s} {gen_rate:8.1f} t/s {product:>11s}")
        if size:
            rows.append(gen_rate * size)

    if len(rows) < 2:
        return

    lo, hi, mean = min(rows), max(rows), sum(rows) / len(rows)
    print("-" * 64)
    print(f"\neffective bandwidth  {mean:.0f} GB/s   (spread {lo:.0f}-{hi:.0f})")
    spread = (hi - lo) / mean
    if spread < 0.35:
        print("\ntok/s x GB is constant to within measurement noise, so generation is")
        print("bandwidth-bound and size trades against latency at a fixed rate.")
        for params, gb in (("13B", 7.5), ("70B", 40.0)):
            rate = mean / gb
            print(f"  extrapolated {params:>3s} @ 4-bit ({gb:.0f} GB): {rate:6.1f} tok/s"
                  f"  -> {30 / rate:6.1f}s for a 30-token completion")
        print("\nThose two lines are arithmetic on the measured constant, not "
              "measurements.\nThey are also optimistic: neither model fits in this "
              "machine's VRAM, so both\nwould spill to system memory and run slower "
              "than the figure shown.")
    else:
        print("\ntok/s x GB is NOT constant here, so something other than bandwidth "
              "dominates.\nDo not extrapolate from these numbers.")


if __name__ == "__main__":
    main()
