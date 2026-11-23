// Layer 3 failure modes: what 02's "Decide route" does when the AI step fails or returns
// something malformed. Runs the routing code from the eval workflow (identical rules and order
// to 02's) with n8n's $ helpers stubbed, so no AI call is needed.
//
//   node eval/failure_modes.mjs
import { readFileSync } from "node:fs";

const wf = JSON.parse(readFileSync(new URL("./02-eval-score-only.json", import.meta.url), "utf8"));
const code = wf.nodes.find((n) => n.name === "Decide route").parameters.jsCode;
const decide = new Function("$", code);

const cfg = { confidence_floor: 0.6, severity_escalate: 4, history_lookback_days: 365 };
const NO_RISK = { output: { what_happened: "none", safety_risk: false } };
function route(scored, prior = false, text = "", check = NO_RISK) {
  const nodes = {
    "Get config": cfg,
    "Read input": { prior_negative: prior, raw_text: text },
    "Score reply": scored,
    "Safety check": check,
  };
  return decide((name) => ({ first: () => ({ json: nodes[name] }) }))[0].json;
}

const ok = (o) => ({ output: { is_feedback: true, themes: [], summary: "", ...o } });
const FAILED = /^The AI could not score this reply/;
const PROBLEM = /^Positive, but it mentions a problem/;
const CONTACT = /^Positive, but it contains a phone number or email/;
const praise = ok({ polarity: 0.6, severity: 0, confidence: 0.9 });
const cases = [
  // [name, what n8n hands Decide route, the route, its reason, earlier complaint?, the words]
  // The reason is checked too: several rules share a route, so the route alone can be luck.
  ["AI call errors (timeout, quota, network)", { error: { message: "Request timed out" } }, "needs_review", FAILED],
  ["AI error that still carries scores", { error: "boom", ...ok({ polarity: 1, severity: 0, confidence: 1 }) }, "needs_review", FAILED],
  ["Output parser rejects the answer", { error: "Failed to parse. Text: Sure! Here is..." }, "needs_review", FAILED],
  ["Empty output", { output: {} }, "needs_review", FAILED],
  ["No output at all", {}, "needs_review", FAILED],
  ["Polarity as text", ok({ polarity: "-0.5", severity: 2, confidence: 0.8 }), "needs_review", FAILED],
  ["Severity as text", ok({ polarity: -0.5, severity: "5", confidence: 0.8 }), "needs_review", FAILED],
  ["Severity missing", ok({ polarity: 0.8, confidence: 0.9 }), "needs_review", FAILED],
  ["Confidence missing", ok({ polarity: -0.5, severity: 2 }), "needs_review", FAILED],
  ["Polarity NaN", ok({ polarity: NaN, severity: 2, confidence: 0.8 }), "needs_review", /^Neither/],
  ["Not feedback, however sure the scores", { output: { is_feedback: false, polarity: -0.9, severity: 5, confidence: 0.9 } }, "needs_review", /^Reply unclear/],
  ["Confidence just under the floor", ok({ polarity: -0.5, severity: 5, confidence: 0.59 }), "needs_review", /^The AI was unsure/],
  ["Confidence exactly at the floor", ok({ polarity: -0.5, severity: 2, confidence: 0.6 }), "private_queue", /^First negative/],
  ["Severity 3, just below the cut-off", ok({ polarity: -0.5, severity: 3, confidence: 0.8 }), "private_queue", /^First negative, severity 3/],
  ["Severity 4, exactly at the cut-off", ok({ polarity: -0.5, severity: 4, confidence: 0.8 }), "escalated", /^Severity 4/],
  ["Safety danger the model scored 2", ok({ polarity: -0.2, severity: 2, confidence: 0.8 }), "escalated", /^Severity 5/, false, "", { output: { safety_risk: true } }],
  ["Safety danger wrapped in praise, scored 0", ok({ polarity: 0.8, severity: 0, confidence: 0.8 }), "escalated", /^Severity 5/, false, "", { output: { safety_risk: true } }],
  ["Safety danger, earlier complaint on record (history first)", ok({ polarity: -0.5, severity: 4, confidence: 0.8 }), "escalated", /^Repeat complaint/, true, "", { output: { safety_risk: true } }],
  ["No safety danger leaves severity alone", ok({ polarity: -0.5, severity: 3, confidence: 0.8 }), "private_queue", /^First negative, severity 3/, false, "", { output: { safety_risk: false } }],
  ["safety_risk as text is ignored", ok({ polarity: -0.5, severity: 2, confidence: 0.8 }), "private_queue", /^First negative, severity 2 below 4 \(the safety check did not run\)$/, false, "", { output: { safety_risk: "true" } }],
  ["Safety danger, severity missing", ok({ polarity: -0.5, confidence: 0.8 }), "needs_review", FAILED, false, "", { output: { safety_risk: true } }],
  ["Safety danger, unsure", ok({ polarity: -0.5, severity: 4, confidence: 0.4 }), "needs_review", /^The AI was unsure/, false, "", { output: { safety_risk: true } }],
  ["Safety check errors: severity stands and the reason says so", ok({ polarity: -0.5, severity: 4, confidence: 0.8 }), "escalated", /^Severity 4 at or above 4 \(the safety check did not run\)$/, false, "", { error: { message: "timeout" } }],
  ["Safety check errors but still carries a yes: ignored", ok({ polarity: -0.5, severity: 2, confidence: 0.8 }), "private_queue", /did not run/, false, "", { error: "boom", output: { safety_risk: true } }],
  ["Unreadable reply, safety check says yes", { output: { is_feedback: false, polarity: 0, severity: 0, confidence: 0.1 } }, "needs_review", /^Reply unclear/, false, "", { output: { safety_risk: true } }],
  ["Severity out of range (9)", ok({ polarity: -0.5, severity: 9, confidence: 0.8 }), "escalated", /^Severity 9/],
  ["Neutral but severe", ok({ polarity: 0, severity: 5, confidence: 0.8 }), "escalated", /^Severity 5/],
  ["Neutral and harmless", ok({ polarity: 0, severity: 0, confidence: 0.8 }), "needs_review", /^Neither/],
  ["Positive overall but severe (\"great staff, but a wheel came off\")", ok({ polarity: 0.2, severity: 5, confidence: 0.8 }), "escalated", /^Severity 5/],
  ["Positive, with a phone number", praise, "needs_review", CONTACT, false, "Great job! Call me on 0803 123 4567."],
  ["Positive, with an email address", praise, "needs_review", CONTACT, false, "Lovely service, write to ada@example.com"],
  ["Positive, a price but no contact details", praise, "ready_to_post", /^Positive$/, false, "Great value at 45,000 naira, done in 2 hours."],
  ["Positive, masked card only", praise, "ready_to_post", /^Positive$/, false, "Thanks! [card number removed, ending 1111]"],
  ["Positive but names a problem (sarcasm, severity 2)", ok({ polarity: 0.2, severity: 2, confidence: 0.7 }), "needs_review", PROBLEM],
  ["Positive with a minor niggle (severity 1)", ok({ polarity: 0.5, severity: 1, confidence: 0.8 }), "ready_to_post", /^Positive$/],
  ["Positive, customer has an earlier complaint", ok({ polarity: 0.8, severity: 0, confidence: 0.9 }), "ready_to_post", /^Positive$/, true],
  ["Mild complaint, earlier complaint on record", ok({ polarity: -0.2, severity: 1, confidence: 0.7 }), "escalated", /^Repeat complaint/, true],
  ["Serious complaint, earlier complaint on record (history first)", ok({ polarity: -0.9, severity: 5, confidence: 0.9 }), "escalated", /^Repeat complaint/, true],
];

let pass = 0;
for (const [name, scored, expected, reason, prior, text, check] of cases) {
  let got;
  try {
    got = route(scored, prior, text, check);
  } catch (e) {
    got = { route: "CRASH", route_reason: e.message };
  }
  const good = got.route === expected && reason.test(got.route_reason);
  pass += good;
  console.log(`${good ? "PASS" : "FAIL"}  ${name}\n      -> ${got.route}: ${got.route_reason}`);
}
console.log(`\n${pass}/${cases.length} passed`);

// Card masking, as done in Read reply / Read input before scoring.
const readCode = wf.nodes.find((n) => n.name === "Read input").parameters.jsCode;
const mask = new Function(readCode.split("\n// Layer 2")[0] + "\nreturn maskCards;")();
const maskCases = [
  ["4111 1111 1111 1111, refund me", true],
  ["card 5500-0000-0000-0004 was charged twice", true],
  ["Call 0803 123 4567", false],
  ["Call +234 803 123 4567", false],
  ["I paid 250000 naira", false],
  ["Order 1234 5678 9012 3456 is wrong (not a valid card number)", false],
  ["Job code AF-261121-3J3M5CY4", false],
];
let mpass = 0;
for (const [t, shouldMask] of maskCases) {
  const out = mask(t);
  const good = out.includes("[card number removed") === shouldMask;
  mpass += good;
  console.log(`${good ? "PASS" : "FAIL"}  mask "${t}" -> "${out}"`);
}
console.log(`${mpass}/${maskCases.length} masking checks passed`);

// A failed check fails the run, so GitHub shows it.
if (pass < cases.length || mpass < maskCases.length) process.exitCode = 1;
