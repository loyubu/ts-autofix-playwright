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
function route(scored, prior = false) {
  const nodes = {
    "Get config": cfg,
    "Read input": { prior_negative: prior },
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
  ["Neutral but severe", ok({ polarity: 0, severity: 5, confidence: 0.8 }), ["needs_review", "escalated"]],
  ["Positive overall but severe (\"great staff, but a wheel came off\")", ok({ polarity: 0.2, severity: 5, confidence: 0.8 }), ["escalated", "needs_review"]],
  ["Positive, customer has an earlier complaint", ok({ polarity: 0.8, severity: 0, confidence: 0.9 }), ["ready_to_post"], true],
  ["Mild complaint, earlier complaint on record", ok({ polarity: -0.2, severity: 1, confidence: 0.7 }), ["escalated"], true],
];

let pass = 0;
for (const [name, scored, accept, prior] of cases) {
  let got;
  try {
    got = route(scored, prior);
  } catch (e) {
    got = { route: "CRASH", route_reason: e.message };
  }
  const good = accept.includes(got.route);
  pass += good;
  console.log(`${good ? "PASS" : "FAIL"}  ${name}\n      -> ${got.route}: ${got.route_reason}`);
}
console.log(`\n${pass}/${cases.length} passed`);
