# Scorer evaluation (Layer 2)

The end-to-end suite sends one random reply per route per run: five data points, so a scorer
that is right only most of the time can still pass by luck. This folder measures the AI scorer
properly, against all 100 labelled replies in
[`ReplyLibrary`](../src/test/java/utils/ReplyLibrary.java), several times over.

## What is in here

| File | What it is |
|---|---|
| `02-eval-score-only.json` | The **"02-eval Score only"** n8n workflow, ready to import |
| `score_eval.py` | Sends every reply through that workflow and writes the report |
| `hard_cases.json`, `hard_cases.py` | Layer 3: 30 hard replies (sarcasm, Pidgin/Yoruba, injection, personal data, threats, calm safety problems), each with the routes that are acceptable |
| `failure_modes.mjs` | Layer 3: runs the routing code against simulated AI failures (`node eval/failure_modes.mjs`) |
| `results/` | One CSV (every call) and one Markdown summary per run |
| [`FINDINGS.md`](FINDINGS.md) | **What the evaluation found**, most serious first |

## The "02-eval Score only" workflow

It is the scoring step and routing rules of **02 Reply & Route**, copied word for word, behind a
webhook:

```
Eval request (webhook) → Read input → Get config (00 Config) → Score reply → Decide route → Return result
                                                    OpenAI Chat Model + Structured Output Parser ┘
```

- **Same prompt, same model (gpt-4o-mini, temperature 0), same output parser** as 02's
  *Score reply*.
- **Same thresholds**: it calls *00 Config*, just as 02 does, so the severity cut-off and
  confidence floor are the live values.
- **Same routing rules, same order** as 02's *Decide route*. The only difference is customer
  history: 02 searches Airtable for an earlier complaint, while this takes a `prior_negative`
  flag in the request, so the history override can be tested on demand.
- **It writes nothing and alerts no one**: no Airtable, no Google Sheets, no Slack, no email and
  no drafted reply. It returns the scores and the route, and that is all.

Request and response:

```jsonc
// POST <EVAL_SCORE_URL>
{ "raw_text": "The car was ready two hours late.", "location": "Ikoyi",
  "service": "Full service and oil change.", "prior_negative": false }

// response
{ "route": "private_queue", "route_reason": "First negative, severity 2 below 4",
  "polarity": -0.5, "severity": 2, "confidence": 0.8, "is_feedback": true,
  "themes": "wait_time", "summary": "...", "failed": false, "error": null,
  "config": { "severity_escalate": 4, "confidence_floor": 0.6 } }
```

**Keep it in step with 02.** If 02's scoring prompt, model or *Decide route* rules change, copy
the change here too, or the evaluation measures the old version.

## Setting it up

1. In n8n, create a workflow and **Import from file** `02-eval-score-only.json`.
2. Open the **Eval request** node and replace `REPLACE-WITH-A-RANDOM-STRING` in the path with a
   random string (for example `python -c "import secrets;print(secrets.token_hex(8))"`).
   The webhook has no other protection, and every call spends OpenAI credit, so the address is
   the secret.
3. Check the **OpenAI Chat Model** credential and that **Get config** points at *00 Config*.
   Save and publish.
4. Put the production webhook URL in the gitignored `.env` as `EVAL_SCORE_URL`. Never commit it:
   this repository is public.

## Running it

Needs Python 3.9+ and nothing else.

```bash
python eval/score_eval.py --limit 2   # smoke test: 2 replies per pool, one run (10 calls)
python eval/score_eval.py             # full: 100 replies x 3 runs + 20 history checks = 320 calls
python eval/hard_cases.py             # Layer 3: 30 hard cases x 3 runs = 90 calls
node eval/failure_modes.mjs           # Layer 3: routing under simulated AI failures, no calls
```

A full run takes about 10 minutes and costs a few cents of OpenAI credit.

## How the replies are graded

| Pool | Expected route | Why |
|---|---|---|
| `POSITIVE` | ready_to_post | |
| `FIRST_COMPLAINT` | private_queue | minor problems, severity below 4 |
| `REPEAT_COMPLAINT` | private_queue | mild on purpose: **on its own** it is an ordinary complaint |
| `REPEAT_COMPLAINT` + `prior_negative` | escalated | the history override, run once per reply |
| `SERIOUS_COMPLAINT` | escalated | severity 4 or 5 |
| `UNREADABLE` | needs_review | |

Each reply always gets the same branch and check-in text, so any difference between runs comes
from the model alone. Unreadable replies get a long, angry check-in text, to prove the scorer
judges the reply and not the background.

The summary reports accuracy per pool, a confusion table (expected against actual), how many
replies got the same route and the same severity on every run, every miss with its scores, the
severity scores near the cut-off of 4, and timing.
