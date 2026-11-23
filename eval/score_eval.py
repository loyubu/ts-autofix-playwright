"""Layer 2: evaluate the AI scorer against ReplyLibrary's 100 labelled replies.

Sends every reply to the "02-eval Score only" n8n workflow (scoring + routing rules of
02 Reply & Route, no writes, no alerts) RUNS times, then reports accuracy per route, a
confusion table, consistency across runs and the severity scores near the escalation cut-off.

    python eval/score_eval.py              # full run
    python eval/score_eval.py --limit 2    # smoke test: 2 replies per pool, 1 run

Results: eval/results/scores-<stamp>.csv (one row per call) and summary-<stamp>.md.
"""
import argparse
import csv
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LIBRARY = ROOT.parent / "src/test/java/utils/ReplyLibrary.java"
BRANCHES = ["Ikoyi", "Gbagada", "Maitama", "Effurun"]

# Pool -> route the scorer alone should give. REPEAT_COMPLAINT replies are mild on purpose:
# without history they belong in the private queue; with history they must escalate.
EXPECTED = {
    "POSITIVE": "ready_to_post",
    "FIRST_COMPLAINT": "private_queue",
    "REPEAT_COMPLAINT": "private_queue",
    "SERIOUS_COMPLAINT": "escalated",
    "UNREADABLE": "needs_review",
}
ROUTES = ["ready_to_post", "private_queue", "escalated", "needs_review"]


def eval_url():
    """The eval webhook's address. It is unguessable on purpose (anyone who knows it can spend
    the OpenAI credit), so it lives in the gitignored .env as EVAL_SCORE_URL, never in this
    public repository."""
    url = os.environ.get("EVAL_SCORE_URL")
    env = ROOT.parent / ".env"
    if not url and env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("EVAL_SCORE_URL="):
                url = line.split("=", 1)[1].strip()
    if not url:
        sys.exit("Set EVAL_SCORE_URL (in .env) to the 02-eval workflow's production webhook URL.")
    return url


def load_pools():
    src = LIBRARY.read_text(encoding="utf-8")
    pools = {}
    for m in re.finditer(r"List<String> (\w+) = List\.of\((.*?)\);", src, re.S):
        body = m.group(2)
        # Java string literals, joined across "+" continuations.
        items, current = [], ""
        for token in re.finditer(r'"((?:[^"\\]|\\.)*)"|(,)', body):
            if token.group(2):
                items.append(current)
                current = ""
            else:
                current += bytes(token.group(1), "utf-8").decode("unicode_escape")
        if current:
            items.append(current)
        pools[m.group(1)] = items
    return pools


def call(case, attempt=0):
    body = json.dumps({k: case[k] for k in ("raw_text", "location", "service", "prior_negative")}).encode()
    req = urllib.request.Request(eval_url(), data=body, headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            out = json.loads(r.read())
    except Exception as e:  # network or n8n error: retry twice, then record it
        if attempt < 2:
            time.sleep(5)
            return call(case, attempt + 1)
        out = {"route": "ERROR", "error": str(e)}
    out["seconds"] = round(time.time() - t0, 1)
    return {**case, **graded(out)}


def graded(out):
    """An AI that could not score is an ERROR, never a correct answer. 02 sends such a reply to
    needs_review, which is also the right route for some replies, so without this an outage
    would look like a pass."""
    if out.get("failed") or out.get("route") not in ROUTES:
        return {**out, "route": "ERROR", "error": out.get("error") or "the AI did not score this reply"}
    return out


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--limit", type=int, default=0, help="replies per pool (smoke test)")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()
    if args.limit:
        args.runs = 1

    pools = load_pools()
    issues, long_issues = pools["CHECK_IN_ISSUES"], pools["LONG_NEGATIVE_CHECK_IN_ISSUES"]
    cases = []
    for pool, expected in EXPECTED.items():
        replies = pools[pool][: args.limit or None]
        for i, reply in enumerate(replies):
            # Fixed context per reply, so differences between runs come from the model alone.
            service = long_issues[i % len(long_issues)] if pool == "UNREADABLE" else issues[i % len(issues)]
            base = dict(pool=pool, idx=i, raw_text=reply, location=BRANCHES[i % 4], service=service)
            for run in range(1, args.runs + 1):
                cases.append({**base, "run": run, "prior_negative": False, "expected": expected})
            if pool == "REPEAT_COMPLAINT":  # history check, once per reply
                cases.append({**base, "run": 1, "prior_negative": True, "expected": "escalated"})

    print(f"{len(cases)} calls")
    with ThreadPoolExecutor(args.workers) as ex:
        results = []
        for n, r in enumerate(ex.map(call, cases), 1):
            results.append(r)
            if n % 25 == 0 or n == len(cases):
                print(f"  {n}/{len(cases)}", flush=True)

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M")
    out_dir = ROOT / "results"
    out_dir.mkdir(exist_ok=True)
    fields = ["pool", "idx", "run", "prior_negative", "expected", "route", "polarity", "severity",
              "confidence", "is_feedback", "themes", "summary", "route_reason", "failed", "error",
              "seconds", "location", "service", "raw_text"]
    csv_path = out_dir / f"scores-{stamp}.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(sorted(results, key=lambda r: (r["pool"], r["idx"], r["prior_negative"], r["run"])))

    summary = summarise(results, args.runs)
    md_path = out_dir / f"summary-{stamp}.md"
    md_path.write_text(summary, encoding="utf-8")
    print(summary)
    print(f"\nCSV: {csv_path}\nSummary: {md_path}")
    wrong = sum(r["route"] != r["expected"] for r in results)
    print(f"{wrong} of {len(results)} wrong or not scored" if wrong else "All correct")
    return 1 if wrong else 0


def summarise(results, runs):
    main_rows = [r for r in results if not r["prior_negative"]]
    hist_rows = [r for r in results if r["prior_negative"]]
    L = [f"# Scorer evaluation, {dt.datetime.now():%Y-%m-%d %H:%M}", "",
         f"{len(results)} calls; {len(main_rows)} without history ({runs} run(s) per reply), "
         f"{len(hist_rows)} with simulated history.", ""]

    L += ["## Accuracy per pool (no history)", "", "| Pool | Expected | Correct | Accuracy |", "|---|---|---|---|"]
    for pool, exp in EXPECTED.items():
        rows = [r for r in main_rows if r["pool"] == pool]
        ok = sum(r["route"] == exp for r in rows)
        if rows:
            L.append(f"| {pool} | {exp} | {ok}/{len(rows)} | {ok / len(rows):.0%} |")
    ok = sum(r["route"] == r["expected"] for r in main_rows)
    L += [f"| **All** | | **{ok}/{len(main_rows)}** | **{ok / max(len(main_rows), 1):.0%}** |", ""]

    L += ["## Confusion table (rows: expected pool, columns: actual route)", "",
          "| Pool | " + " | ".join(ROUTES + ["ERROR"]) + " |", "|---" * (len(ROUTES) + 2) + "|"]
    for pool in EXPECTED:
        c = Counter(r["route"] for r in main_rows if r["pool"] == pool)
        L.append(f"| {pool} | " + " | ".join(str(c.get(x, 0)) for x in ROUTES + ["ERROR"]) + " |")
    L.append("")

    if hist_rows:
        ok = sum(r["route"] == "escalated" for r in hist_rows)
        L += ["## History override (REPEAT_COMPLAINT with an earlier complaint)", "",
              f"Escalated: {ok}/{len(hist_rows)}.", ""]
        for r in hist_rows:
            if r["route"] != "escalated":
                L.append(f"- not escalated ({r['route']}, polarity {r.get('polarity')}): \"{r['raw_text']}\"")
        L.append("")

    by_reply = defaultdict(list)
    for r in main_rows:
        by_reply[(r["pool"], r["idx"])].append(r)
    if runs > 1:
        unstable = {k: v for k, v in by_reply.items() if len({r["route"] for r in v}) > 1}
        sev_moves = {k: v for k, v in by_reply.items() if len({r.get("severity") for r in v}) > 1}
        L += ["## Consistency across runs", "",
              f"Same route every run: {len(by_reply) - len(unstable)}/{len(by_reply)} replies.",
              f"Same severity every run: {len(by_reply) - len(sev_moves)}/{len(by_reply)} replies.", ""]
        for (pool, idx), v in sorted(unstable.items()):
            L.append(f"- {pool}#{idx}: {[r['route'] for r in sorted(v, key=lambda r: r['run'])]} \"{v[0]['raw_text']}\"")
        L.append("")

    wrong = [r for r in main_rows if r["route"] != r["expected"]]
    if wrong:
        L += ["## Every miss", "", "| Pool | Reply | Got | Pol | Sev | Conf | Reason |", "|---|---|---|---|---|---|---|"]
        for r in sorted(wrong, key=lambda r: (r["pool"], r["idx"], r["run"])):
            L.append(f"| {r['pool']} | {r['raw_text'][:70]} | {r['route']} | {r.get('polarity')} | "
                     f"{r.get('severity')} | {r.get('confidence')} | {r.get('route_reason') or r.get('error')} |")
        L.append("")

    L += ["## Severity near the cut-off (3, 4 or 5 on any complaint)", "",
          "| Pool | Severity counts (all runs) |", "|---|---|"]
    for pool in ("FIRST_COMPLAINT", "REPEAT_COMPLAINT", "SERIOUS_COMPLAINT"):
        c = Counter(r.get("severity") for r in main_rows if r["pool"] == pool)
        L.append(f"| {pool} | " + ", ".join(f"sev {k}: {c[k]}" for k in sorted(c, key=lambda x: (x is None, x))) + " |")
    L.append("")
    near = [r for r in main_rows if r.get("severity") in (3, 4)]
    if near:
        L += ["Replies scored 3 or 4 (one step from the other side of the line):", ""]
        seen = set()
        for r in sorted(near, key=lambda r: (r["pool"], r["idx"])):
            key = (r["pool"], r["idx"])
            if key in seen:
                continue
            seen.add(key)
            sevs = [x.get("severity") for x in by_reply[key]]
            L.append(f"- {r['pool']}#{r['idx']} severities {sevs}: \"{r['raw_text']}\"")
        L.append("")

    errors = [r for r in results if r["route"] == "ERROR"]
    if errors:
        L += ["## Not scored", "", f"{len(errors)} call(s) failed, for example: {errors[0].get('error')}", ""]

    secs = sorted(r["seconds"] for r in results if r["route"] != "ERROR")
    if secs:
        L += ["## Timing", "", f"Per call: median {secs[len(secs) // 2]}s, slowest {secs[-1]}s.", ""]
    return "\n".join(L)


if __name__ == "__main__":
    sys.exit(main())
