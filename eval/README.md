# Scorer evaluation (Layers 2 and 3)

The end-to-end suite sends one random reply per route per run: five data points, so a scorer
that is right only most of the time can still pass by luck. This folder measures the AI scorer
properly, against all 100 labelled replies in
[`ReplyLibrary`](../src/test/java/utils/ReplyLibrary.java), several times over.

## What is in here

| File | What it is |
|---|---|
| `02-eval-score-only.json` | The **"02-eval Score only"** n8n workflow, ready to import |
| `score_eval.py` | Sends every reply through that workflow and writes the report |
| `hard_cases.json`, `hard_cases.py` | Layer 3: 58 hard replies (sarcasm, Pidgin/Yoruba, injection, personal data, threats, safety dangers, things that are not dangers), each with the routes, and for some the severities, that are acceptable |
| `failure_modes.mjs` | Layer 3: runs the routing code against simulated AI failures and routing edge cases, and checks card-number masking (`node eval/failure_modes.mjs`) |
| `02-eval-draft-only.json`, `draft_eval.py`, `draft_cases.json` | Layer 3: the **"02-eval Draft only"** workflow (02's *Draft reply* step behind a webhook), the runner, and 21 trap complaints; see *Drafted replies* below |
| `results/` | One CSV (every call) and one Markdown summary per run |
| [`FINDINGS.md`](FINDINGS.md) | **What the evaluation found**, most serious first |

## The "02-eval Score only" workflow

It is the scoring step and routing rules of **02 Reply & Route**, copied word for word, behind a
webhook:

```
Eval request (webhook) → Read input → Get config (00 Config) → Score reply → Safety check → Decide route → Return result
                                                    OpenAI Chat Model + Structured Output Parser ┘
```

- **Same prompt, same model (gpt-4o-mini, temperature 0), same output parser** as 02's
  *Score reply*.
- **Same *Safety check***: a second, separate AI call that answers only whether the car was or may be
  unsafe. A yes raises severity to 5 in *Decide route*. If it fails, *Score reply*'s severity stands
  and the reason says the check did not run; here that counts as an error, never a pass.
- **Same thresholds**: it calls *00 Config*, just as 02 does, so the severity cut-off and
  confidence floor are the live values.
- **Same routing rules, same order** as 02's *Decide route*. The only difference is customer
  history: 02 searches Airtable for an earlier complaint, while this takes a `prior_negative`
  flag in the request, so the history override can be tested on demand.
- **Same card masking** as 02's *Read reply*: card numbers are removed before scoring, so the
  `raw_text` in the response is what 02 would store.
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
  "raw_text": "The car was ready two hours late.",
  "config": { "severity_escalate": 4, "confidence_floor": 0.6, "testimonial_severity_max": 1 } }
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
python eval/hard_cases.py             # Layer 3: 58 hard cases x 3 runs = 174 calls
node eval/failure_modes.mjs           # Layer 3: routing edge cases + card masking, no calls
```

A full run takes about 10 minutes and costs a few cents of OpenAI credit.

Every script exits with an error if any check fails, so a failure cannot look like a success. A
reply the AI failed to score counts as an error, never as a correct `needs_review`. For drafts,
only broken rules count; flags are for reading. `failure_modes.mjs` checks the reason as well as
the route, and GitHub runs it on every push (`.github/workflows/routing-rules.yml`). The others
call the AI, so they are run by hand.

These scripts test copies of 02's steps. If live 02 changes and the copy here does not, the
results describe the old version; only the end-to-end tests exercise 02 itself.

## Drafted replies

`02-eval-draft-only.json` is 02's *Draft reply* step (same prompt, same model and temperature)
behind its own webhook, set up exactly as above: import, replace `REPLACE-WITH-A-RANDOM-STRING`
in the **Eval request** path, check the **Draft model** credential, publish, and put the URL in
`.env` as `EVAL_DRAFT_URL`. It writes nothing and alerts no one. Request: `raw_text`,
`customer_name`, `location`, `repeat_customer`, `severity`, `service`; response: `draft`, `error`.

```bash
python eval/draft_eval.py                                        # 41 complaints x 2 drafts = 82 calls
python eval/draft_eval.py --recheck eval/results/drafts-<stamp>.csv  # re-run the checks, no calls
```

Every draft is checked automatically against the prompt's own rules (greeting, sign-off, 50 to
120 words although the prompt asks for at least 60, no placeholders, contact details, card digits or markdown, no other branch, no number
the customer did not give, no use of the check-in background). Mentions of refunds or liability,
and repeat complaints with no acknowledgement, are flagged for reading rather than failed. The
report prints every draft in full, because politeness and whether a draft answers the actual
complaint can only be judged by reading it. If 02's *Draft reply* prompt changes, rebuild this
workflow from a fresh 02 export.

The prompt ends with a **THIS REPLY MUST** list that n8n works out for each complaint (safety
wording, refund request, card details, repeat complaint), because a fixed list of every rule was
sometimes only partly followed. It is still one AI call.

## How the replies are graded

| Pool | Expected route | Why |
|---|---|---|
| `POSITIVE` | ready_to_post | |
| `FIRST_COMPLAINT` | private_queue | minor problems, severity below 4 |
| `REPEAT_COMPLAINT` | private_queue | mild on purpose: **on its own** it is an ordinary complaint |
| `REPEAT_COMPLAINT` + `prior_negative` | escalated | the history override, run once per reply |
| `SAFETY_COMPLAINT` | escalated | severity must be 5 |
| `SERIOUS_COMPLAINT` | escalated | severity 4 or 5 |
| `UNREADABLE` | needs_review | |

Each reply always gets the same branch and check-in text, so any difference between runs comes
from the model alone. Unreadable replies get a long, angry check-in text, to prove the scorer
judges the reply and not the background.

The summary reports accuracy per pool, a confusion table (expected against actual), how many
replies got the same route and the same severity on every run, every miss with its scores, the
severity scores near the cut-off of 4, and timing.
