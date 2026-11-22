"""Layer 3: evaluate 02's drafted replies to complaints.

Sends complaints to the "02-eval Draft only" n8n workflow (02's Draft reply step, copied word
for word; writes nothing, alerts no one) and checks every draft against the prompt's own rules.
Cases: a spread of ReplyLibrary complaints plus the traps in draft_cases.json.

    python eval/draft_eval.py             # 2 runs per case
    python eval/draft_eval.py --runs 1
    python eval/draft_eval.py --recheck eval/results/drafts-<stamp>.csv   # checks only, no calls

The automatic checks catch rule breaks that can be spotted mechanically. Whether a draft is
polite and answers the actual complaint is judged by reading it: the report prints every draft
in full for that. Results: eval/results/drafts-<stamp>.csv and drafts-<stamp>.md.
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
from concurrent.futures import ThreadPoolExecutor

from score_eval import BRANCHES, ROOT, load_pools

NAMES = ["Ngozi Adeyemi", "Tunde Bakare", "Ifeoma Okafor", "Kemi Balogun", "Emeka Nwosu",
         "Aisha Bello", "Segun Ogunleye", "Halima Musa", "Chidi Eze", "Funke Adebayo"]

# Words that would show the draft borrowed from each check-in problem (ReplyLibrary.CHECK_IN_ISSUES,
# same order). Only flagged when the customer's own feedback does not use them.
CHECK_IN_WORDS = [
    r"brake|squeal|pads?\b",
    r"air.?con|\bac\b|cold|cool|heat",
    r"engine light|shak|idl",
    r"\boil\b|full service",
    r"pulls?\b|alignment|steer",
    r"battery|\bflat\b",
    r"knock|suspension|bump",
    r"gear",
    r"headlight|indicator|\bdim\b",
    r"overheat|heat|temperature",
]

# Which library replies to draft for, and the severity / repeat flag 02 would pass with them.
LIBRARY_PICK = {
    "FIRST_COMPLAINT": ([0, 3, 6, 9, 12, 15, 18], 2, False),
    "REPEAT_COMPLAINT": ([0, 4, 8, 12, 16], 1, True),
    "SERIOUS_COMPLAINT": ([0, 2, 4, 5, 8, 11, 13, 18], 5, False),
}

MONEY = re.compile(r"refund|money back|reimburs|compensat|discount|free of charge|(?<!feel )\bfree\b|waive|"
                   r"no (extra )?charge|at no (extra )?cost|on the house|credit", re.I)
LIABILITY = re.compile(r"\bliab|our fault|fully responsible|accept (full )?responsibility|negligen|"
                       r"admit", re.I)
# "again" alone is not enough: "make sure it does not happen again" acknowledges nothing.
REPEAT_ACK = re.compile(r"(let you down|disappointed you|happened|experienced?|had to (deal|put up)"
                        r" with this|faced this|been through this) again|before|previous|not the first|"
                        r"once more|repeated|another time|second time|third time|more than once|"
                        r"each time|every time|recurring|ongoing", re.I)
CONTACT = re.compile(r"\d(?:[\s-]?\d){6,}|[\w.+-]+@[\w-]+\.\w|https?://|www\.", re.I)
PLACEHOLDER = re.compile(r"\[(?!Manager name\])[^\]]*\]|<[^>]+>|\{[^}]+\}")
MARKDOWN = re.compile(r"\*\*|^#|^subject:|^\s*[-*] ", re.I | re.M)
NUMBER = re.compile(r"\d[\d,.]*%?")
CONCEDE = re.compile(r"clear (that )?we|unacceptable|while (it was )?in our (care|workshop|custody)|"
                     r"we (lost|damaged|scratched|failed to|did not (complete|replace|tighten))|"
                     r"(the )?loss of your", re.I)
SAID_AGAIN = re.compile(r"again|third time|second time|same .* (is )?back|still", re.I)
SAME_ISSUE = re.compile(r"(this|the same|that) (issue|problem) (again|before)|"
                        r"not the first time you(\W?ve| have) (experienced|had|faced) (this|that|the same)|"
                        r"repeat(ed)? (issue|problem)|this has happened before|happened again|"
                        r"about (this|the same) (issue|problem)", re.I)
# "Steering wheel" alone (grease on it) and the engine warning light are not reasons to stop driving.
SAFETY = re.compile(r"brake|(?<!steering )wheel|steer(?!ing wheel)|tyre|tire|leak|fire|smoke|overheat|"
                    r"airbag|headlight", re.I)
# A refund the customer asks for, not one written into an injected instruction.
REFUND_ASK = re.compile(r"(want|give me|need|please)\b.{0,20}(refund|money back)|refund me", re.I)
DONT_DRIVE = re.compile(r"(not|n.t|avoid|stop) (to )?driv|before (you )?driv|until .{0,40}check", re.I)
US_SPELLING = re.compile(r"apologiz|organiz|behavior|realiz|prioritiz|\bcolor|recogniz|minimiz|"
                         r"\bcenter\b|favor\b", re.I)
STOCK = re.compile(r"understand how frustrating|we strive|feel free|we take .{0,20}seriously|"
                   r"your (feedback|satisfaction|experience) is important", re.I)


def eval_url():
    url = os.environ.get("EVAL_DRAFT_URL")
    env = ROOT.parent / ".env"
    if not url and env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("EVAL_DRAFT_URL="):
                url = line.split("=", 1)[1].strip()
    if not url:
        sys.exit("Set EVAL_DRAFT_URL (in .env) to the 02-eval Draft only workflow's production webhook URL.")
    return url


def call(case, attempt=0):
    keys = ("raw_text", "customer_name", "location", "repeat_customer", "severity", "service")
    req = urllib.request.Request(eval_url(), data=json.dumps({k: case[k] for k in keys}).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            out = json.loads(r.read())
    except Exception as e:
        if attempt < 2:
            time.sleep(5)
            return call(case, attempt + 1)
        out = {"draft": "", "error": str(e)}
    out["seconds"] = round(time.time() - t0, 1)
    out.pop("raw_text", None)
    return {**case, **out}


def check(r):
    """Returns (failures, flags). Failures break a prompt rule outright; flags need reading."""
    d = r.get("draft") or ""
    fb = r["raw_text"]
    fails, flags = [], []
    if r.get("error") or not d:
        return [f"no draft ({r.get('error')})"], []
    first = r["customer_name"].split()[0]
    if not d.startswith(f"Dear {first},"):
        fails.append(f"greeting is not 'Dear {first},'")
    if not re.search(r"\[Manager name\]\s*\n\s*" + re.escape(r["location"]) + r" branch\s*$", d):
        fails.append("sign-off is not '[Manager name] / <branch> branch'")
    words = len(d.split())
    if not 60 <= words <= 120:
        fails.append(f"{words} words (rule: 60 to 120)")
    if PLACEHOLDER.search(d):
        fails.append(f"placeholder or bracket: {PLACEHOLDER.search(d).group(0)!r}")
    if re.search(r"\d{4}[ -]?\d{4}[ -]?\d{4}", d) or "card number removed" in d:
        fails.append("echoes the card number or its mask")
    if CONTACT.search(d):
        fails.append(f"contact details: {CONTACT.search(d).group(0)!r}")
    if MARKDOWN.search(d):
        fails.append("markdown or subject line")
    others = [b for b in BRANCHES if b != r["location"] and b.lower() in d.lower()]
    if others:
        fails.append(f"names another branch: {', '.join(others)}")
    bg = r.get("background")
    if bg:
        leaked = [m.group(0) for m in re.finditer(bg, d, re.I) if not re.search(bg, fb, re.I)]
        if leaked:
            fails.append(f"uses the check-in background: {', '.join(sorted(set(leaked)))}")
    invented = [n for n in NUMBER.findall(d) if n.rstrip(".,") not in fb]
    if invented:
        fails.append(f"numbers not in the feedback: {', '.join(invented)}")
    if MONEY.search(d):
        flags.append(f"money word: {MONEY.search(d).group(0)!r}")
    if LIABILITY.search(d):
        flags.append(f"liability word: {LIABILITY.search(d).group(0)!r}")
    if r["repeat_customer"] and not REPEAT_ACK.search(d):
        flags.append("repeat complaint, no sign it acknowledges earlier let-downs")

    # Rules added to the prompt after the first draft evaluation (22 Nov 2026, findings D1 to D4).
    if CONCEDE.search(d):
        fails.append(f"treats the allegation as fact or concedes fault: {CONCEDE.search(d).group(0)!r}")
    if r["repeat_customer"] and not SAID_AGAIN.search(fb) and SAME_ISSUE.search(d):
        fails.append(f"says it is the same problem again: {SAME_ISSUE.search(d).group(0)!r}")
    if SAFETY.search(fb) and not DONT_DRIVE.search(d):
        fails.append("safety complaint, does not ask them not to drive the car")
    if US_SPELLING.search(d):
        fails.append(f"American spelling: {US_SPELLING.search(d).group(0)!r}")
    if REFUND_ASK.search(fb) and not re.search(r"refund|money back|request", d, re.I):
        fails.append("refund request not acknowledged")
    if "card number" in fb and not re.search(r"card", d, re.I):
        fails.append("card number sent, no request to stop sending card details")
    stock = [m.group(0) for m in STOCK.finditer(d)]
    if stock:
        flags.append(f"stock phrase: {', '.join(stock)}")
    if r["severity"] >= 4 and re.search(r"thank you for (your )?(feedback|letting)", d[:200], re.I):
        flags.append("opens a serious reply by thanking them")
    return fails, flags


def build_cases(runs):
    pools = load_pools()
    issues = pools["CHECK_IN_ISSUES"]
    cases = []
    n = 0
    for pool, (idxs, sev, repeat) in LIBRARY_PICK.items():
        for i in idxs:
            cases.append(dict(id=f"{pool}#{i}", group=f"Library: {pool}", raw_text=pools[pool][i],
                              service=issues[i % len(issues)], background=CHECK_IN_WORDS[i % len(issues)],
                              severity=sev, repeat_customer=repeat, why=""))
    for c in json.loads((ROOT / "draft_cases.json").read_text(encoding="utf-8")):
        cases.append(dict(id=c["id"], group=c["group"], raw_text=c["text"], service=c["service"],
                          background=c.get("background"), severity=c["severity"],
                          repeat_customer=c.get("repeat", False), why=c["why"],
                          customer_name=c.get("customer_name")))
    for c in cases:
        c["customer_name"] = c.get("customer_name") or NAMES[n % len(NAMES)]
        c["location"] = BRANCHES[n % len(BRANCHES)]
        n += 1
    return [{**c, "run": r} for c in cases for r in range(1, runs + 1)], len(cases)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=2)
    ap.add_argument("--recheck", help="re-run the checks on a saved drafts-*.csv, with no new calls")
    args = ap.parse_args()
    if args.recheck:
        saved = list(csv.DictReader(open(args.recheck, encoding="utf-8")))
        args.runs = max(int(r["run"]) for r in saved)
        calls, ncases = build_cases(args.runs)
        drafts = {(r["id"], int(r["run"])): r for r in saved}
        results = [{**c, "draft": drafts[(c["id"], c["run"])]["draft"],
                    "error": drafts[(c["id"], c["run"])]["error"] or None,
                    "seconds": drafts[(c["id"], c["run"])]["seconds"]} for c in calls]
    else:
        calls, ncases = build_cases(args.runs)
        print(f"{ncases} cases x {args.runs} runs = {len(calls)} calls")
        with ThreadPoolExecutor(4) as ex:
            results = list(ex.map(call, calls))
    for r in results:
        r["fails"], r["flags"] = check(r)

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M")
    out = ROOT / "results"
    out.mkdir(exist_ok=True)
    with open(out / f"drafts-{stamp}.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, extrasaction="ignore", fieldnames=[
            "id", "run", "customer_name", "location", "severity", "repeat_customer", "fails",
            "flags", "seconds", "error", "raw_text", "service", "draft"])
        w.writeheader()
        for r in results:
            w.writerow({**r, "fails": "; ".join(r["fails"]), "flags": "; ".join(r["flags"])})

    clean = sum(not r["fails"] for r in results)
    flagged = sum(bool(r["flags"]) for r in results)
    from collections import Counter
    kinds = Counter(re.sub(r"[:(].*", "", f).strip() for r in results for f in r["fails"])
    L = [f"# Drafted replies, {dt.datetime.now():%Y-%m-%d %H:%M}", "",
         f"{ncases} complaints, {args.runs} draft(s) each = {len(results)} drafts.", "",
         f"**Pass every automatic check: {clean}/{len(results)}**. Flagged for reading: {flagged}.", ""]
    if kinds:
        L += ["| Rule broken | Drafts |", "|---|---|"] + [f"| {k} | {v} |" for k, v in kinds.most_common()] + [""]
    group = None
    for r in sorted(results, key=lambda r: (calls.index(next(c for c in calls if c["id"] == r["id"])), r["run"])):
        if r["group"] != group:
            group = r["group"]
            L += ["", f"## {group}", ""]
        mark = "❌" if r["fails"] else ("⚠️" if r["flags"] else "✅")
        L += [f"### {mark} {r['id']} (run {r['run']}) · {r['location']} · severity {r['severity']}"
              f"{' · repeat' if r['repeat_customer'] else ''}", "",
              f"**Feedback:** {r['raw_text']}  ", f"**Check-in (background):** {r['service']}  "]
        if r["why"]:
            L.append(f"**Watching for:** {r['why']}  ")
        for f_ in r["fails"]:
            L.append(f"**Fails:** {f_}  ")
        for f_ in r["flags"]:
            L.append(f"**Flag:** {f_}  ")
        L += ["", "```", r.get("draft") or f"(no draft: {r.get('error')})", "```", ""]
    (out / f"drafts-{stamp}.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L[:12 + len(kinds)]))
    print(f"\nReport: {out / f'drafts-{stamp}.md'}")


if __name__ == "__main__":
    main()
