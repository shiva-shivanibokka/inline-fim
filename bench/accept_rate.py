#!/usr/bin/env python3
"""Summarise suggestions.jsonl: accept rate, latency, and why we stayed quiet.

The plugin appends one JSON object per suggestion (and per deliberate silence) to
inline-fim/suggestions.jsonl in the IDE log directory. This reads that file and
prints the numbers the README quotes, so they are computed rather than recalled.

    python bench/accept_rate.py path/to/suggestions.jsonl

No dependencies; stdlib only.
"""

import argparse
import collections
import json
import sys


def pct(values, p):
    if not values:
        return float("nan")
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, int(round(p / 100 * len(ordered) + 0.5)) - 1))
    return ordered[k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--by-language", action="store_true")
    args = ap.parse_args()

    shown, silent = [], []
    try:
        with open(args.path, encoding="utf-8") as fh:
            for n, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    print(f"  (skipping malformed line {n})", file=sys.stderr)
                    continue
                (silent if rec.get("event") == "silent" else shown).append(rec)
    except FileNotFoundError:
        sys.exit(f"no telemetry at {args.path}\n"
                 "Use the plugin in the sandbox IDE first, then re-run.")

    if not shown and not silent:
        sys.exit("telemetry file is empty")

    accepted = [r for r in shown if r.get("event") == "accepted"]
    dismissed = [r for r in shown if r.get("event") == "dismissed"]

    print(f"suggestions shown : {len(shown)}")
    print(f"  accepted        : {len(accepted)}")
    print(f"  dismissed       : {len(dismissed)}")
    if shown:
        print(f"  ACCEPT RATE     : {100 * len(accepted) / len(shown):.1f}%")
    print(f"stayed silent     : {len(silent)}")

    if shown:
        offered = len(shown) + len(silent)
        print(f"  silence rate    : {100 * len(silent) / offered:.1f}% of opportunities")

    if silent:
        print("\nwhy we stayed quiet")
        for reason, count in collections.Counter(
            r.get("reason", "?") for r in silent
        ).most_common():
            print(f"  {reason:16s} {count:5d}  ({100 * count / len(silent):.1f}%)")

    live = [r for r in shown if not r.get("cached")]
    if live:
        ttft = [r["ttft_ms"] for r in live if "ttft_ms" in r]
        total = [r["total_ms"] for r in live if "total_ms" in r]
        print(f"\nlatency, in-editor, {len(live)} uncached suggestions")
        print("                      p50        p95        max")
        if ttft:
            print(f"  time to first tok  {pct(ttft, 50):7.0f}ms {pct(ttft, 95):7.0f}ms {max(ttft):7.0f}ms")
        if total:
            print(f"  total              {pct(total, 50):7.0f}ms {pct(total, 95):7.0f}ms {max(total):7.0f}ms")

    cached = [r for r in shown if r.get("cached")]
    if shown:
        print(f"\ncache hits        : {len(cached)} / {len(shown)} shown "
              f"({100 * len(cached) / len(shown):.1f}%)")

    trunc = [r for r in shown if r.get("truncated")]
    if shown:
        print(f"truncated to cap  : {len(trunc)} / {len(shown)} "
              f"({100 * len(trunc) / len(shown):.1f}%)")

    if shown:
        lines = [r.get("suggestion_lines", 0) for r in shown]
        print(f"suggestion lines  : p50={pct(lines, 50):.0f}  p95={pct(lines, 95):.0f}")

    if shown and dismissed:
        print("\ndismissal reasons")
        for ft, count in collections.Counter(
            r.get("finish_type", "?") for r in dismissed
        ).most_common():
            print(f"  {ft:20s} {count:5d}")

    if args.by_language and shown:
        print("\nby language")
        langs = collections.defaultdict(lambda: [0, 0])
        for r in shown:
            bucket = langs[r.get("language", "?")]
            bucket[0] += 1
            bucket[1] += r.get("event") == "accepted"
        for lang, (n, acc) in sorted(langs.items(), key=lambda kv: -kv[1][0]):
            print(f"  {lang:16s} {acc:4d}/{n:<4d}  {100 * acc / n:5.1f}%")


if __name__ == "__main__":
    main()
