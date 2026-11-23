"""Layer 3: hard cases for the AI scorer (sarcasm, mixed, Pidgin/Yoruba, very short, prompt
injection, personal data, threats, tone against severity).

Each case in hard_cases.json lists the routes that are acceptable, since several have more than
one fair answer. A case passes only if EVERY run lands in an acceptable route.
A reply the AI failed to score never passes, even where needs_review is acceptable.

    python eval/hard_cases.py            # 3 runs per case
    python eval/hard_cases.py --runs 1
"""
import argparse
import csv
import datetime as dt
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from score_eval import ROOT, call

CHECK_IN = "Full service and oil change."


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3)
    args = ap.parse_args()

    cases = json.loads((ROOT / "hard_cases.json").read_text(encoding="utf-8"))
    calls = [dict(id=c["id"], run=r, raw_text=c["text"], location="Ikoyi", service=CHECK_IN,
                  prior_negative=False) for c in cases for r in range(1, args.runs + 1)]
    print(f"{len(cases)} cases x {args.runs} runs = {len(calls)} calls")
    with ThreadPoolExecutor(4) as ex:
        results = list(ex.map(call, calls))

    by_id = {c["id"]: [r for r in results if r["id"] == c["id"]] for c in cases}
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M")
    out = ROOT / "results"
    out.mkdir(exist_ok=True)
    with open(out / f"hard-cases-{stamp}.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, extrasaction="ignore", fieldnames=[
            "id", "run", "route", "polarity", "severity", "confidence", "is_feedback", "themes",
            "summary", "route_reason", "error", "raw_text"])
        w.writeheader()
        w.writerows(sorted(results, key=lambda r: (r["id"], r["run"])))

    passed = 0
    L = [f"# Hard cases, {dt.datetime.now():%Y-%m-%d %H:%M}", "",
         f"{len(cases)} cases, {args.runs} run(s) each. A case passes only if every run lands in an acceptable route.", ""]
    group = None
    for c in cases:
        if c["group"] != group:
            group = c["group"]
            L += ["", f"## {group}", "", "| | Reply | Routes (each run) | Pol / Sev / Conf | Acceptable |", "|---|---|---|---|---|"]
        rs = sorted(by_id[c["id"]], key=lambda r: r["run"])
        ok = all(r["route"] in c["ok"] for r in rs)
        passed += ok
        scores = "; ".join(f"{r.get('polarity')} / {r.get('severity')} / {r.get('confidence')}" for r in rs)
        text = c["text"] if len(c["text"]) <= 90 else c["text"][:87] + "..."
        L.append(f"| {'✅' if ok else '❌'} | {text} | {', '.join(r['route'] for r in rs)} | {scores} | "
                 f"{', '.join(c['ok'])} |")
    L = L[:4] + [f"**Passed: {passed}/{len(cases)}**", ""] + L[4:]
    fails = [c for c in cases if not all(r["route"] in c["ok"] for r in by_id[c["id"]])]
    if fails:
        L += ["", "## Failures explained", ""]
        for c in fails:
            r = by_id[c["id"]][0]
            L.append(f"- **{c['id']}**: \"{c['text'][:90]}\" got {r['route']} ({r.get('route_reason')}). "
                     f"Summary: \"{r.get('summary')}\". Why it matters: {c['why']}")
    text = "\n".join(L)
    (out / f"hard-cases-{stamp}.md").write_text(text, encoding="utf-8")
    print(text)
    return 0 if passed == len(cases) else 1


if __name__ == "__main__":
    sys.exit(main())
