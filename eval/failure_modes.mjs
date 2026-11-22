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
function route(scored, prior = false, text = "") {
  const nodes = {
    "Get config": cfg,
    "Read input": { prior_negative: prior, raw_text: text },
    "Score reply": scored,
  };
  return decide((name) => ({ first: () => ({ json: nodes[name] }) }))[0].json;
}

const ok = (o) => ({ output: { is_feedback: true, themes: [], summary: "", ...o } });
const cases = [
  // [name, what n8n hands Decide route, acceptable routes]
  ["AI call errors (timeout, quota, network)", { error: { message: "Request timed out" } }, ["needs_review"]],
  ["Output parser rejects the answer", { error: "Failed to parse. Text: Sure! Here is..." }, ["needs_review"]],
  ["Empty output", { output: {} }, ["needs_review"]],
  ["No output at all", {}, ["needs_review"]],
  ["Polarity as text", ok({ polarity: "-0.5", severity: 2, confidence: 0.8 }), ["needs_review"]],
  ["Confidence missing", ok({ polarity: -0.5, severity: 2 }), ["needs_review"]],
  ["Polarity NaN", ok({ polarity: NaN, severity: 2, confidence: 0.8 }), ["needs_review"]],
  ["Confidence just under the floor", ok({ polarity: -0.5, severity: 5, confidence: 0.59 }), ["needs_review"]],
  ["Confidence exactly at the floor", ok({ polarity: -0.5, severity: 2, confidence: 0.6 }), ["private_queue"]],
  ["Severity out of range (9)", ok({ polarity: -0.5, severity: 9, confidence: 0.8 }), ["escalated", "needs_review"]],
  ["Neutral but severe", ok({ polarity: 0, severity: 5, confidence: 0.8 }), ["escalated"]],
  ["Positive overall but severe (\"great staff, but a wheel came off\")", ok({ polarity: 0.2, severity: 5, confidence: 0.8 }), ["escalated"]],
  ["Positive, with a phone number", ok({ polarity: 0.6, severity: 0, confidence: 0.9 }), ["needs_review"], false, "Great job! Call me on 0803 123 4567."],
  ["Positive, with an email address", ok({ polarity: 0.6, severity: 0, confidence: 0.9 }), ["needs_review"], false, "Lovely service, write to ada@example.com"],
  ["Positive, a price but no contact details", ok({ polarity: 0.6, severity: 0, confidence: 0.9 }), ["ready_to_post"], false, "Great value at 45,000 naira, done in 2 hours."],
  ["Positive, masked card only", ok({ polarity: 0.6, severity: 0, confidence: 0.9 }), ["ready_to_post"], false, "Thanks! [card number removed, ending 1111]"],
  ["Positive but names a problem (sarcasm, severity 2)", ok({ polarity: 0.2, severity: 2, confidence: 0.7 }), ["needs_review"]],
  ["Positive with a minor niggle (severity 1)", ok({ polarity: 0.5, severity: 1, confidence: 0.8 }), ["ready_to_post"]],
  ["Positive, customer has an earlier complaint", ok({ polarity: 0.8, severity: 0, confidence: 0.9 }), ["ready_to_post"], true],
  ["Mild complaint, earlier complaint on record", ok({ polarity: -0.2, severity: 1, confidence: 0.7 }), ["escalated"], true],
];

let pass = 0;
for (const [name, scored, accept, prior, text] of cases) {
  let got;
  try {
    got = route(scored, prior, text);
  } catch (e) {
    got = { route: "CRASH", route_reason: e.message };
  }
  const good = accept.includes(got.route);
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
