# TS Autofix — end-to-end tests

*We fix it. You confirm it.*

Automated browser tests for **TS Autofix** (transsahara.com), the website of a four-branch car
repair chain, and the AI feedback engine behind it. Written in Java with **Playwright**,
**TestNG** and **Maven**, using the Page Object Model.

## Why this exists

When a repair is finished, TS Autofix emails the customer a short feedback form. An n8n workflow
scores the reply with an AI model and routes it into one of four queues on the manager
dashboard. The routing is the product: a serious complaint must reach a manager quickly, and a
repeat complainer must be escalated even when the new message is mild.

These tests check that whole journey on the live site, from the customer's check-in to the
manager's dashboard, so a change to the site, the workflows or the AI prompt can't quietly
break it.

## What it covers

Five standalone tests, one per routing scenario. Each one creates its own customer and runs the
full journey:

1. **Check in** a car on the home page, and assert the job sheet shows what was entered.
2. Press **Done**, enter the amount paid, close the job, and assert it shows as completed.
3. **Reply** on the n8n feedback form, the same form the customer's email links to.
4. **Sign in as the manager**, wait for the reply to be scored, and assert which queue it landed
   in and why.
5. **Act on it as the manager**, and assert the dashboard updates.

| Test | Customer's reply | Expected queue | Manager action |
|---|---|---|---|
| `positiveFeedbackIsReadyToPost` | Pleased with the work | **Ready to post**, "Positive" | Mark posted |
| `firstComplaintGoesToPrivateDrafts` | A minor complaint, first time | **Private drafts**, severity below 4, with a drafted reply | Mark sent (own email) |
| `repeatComplaintIsEscalated` | Two visits by the same customer: a first complaint, then a deliberately mild one | **Escalated**, "Repeat complaint" | Mark alert acted on |
| `unreadableReplyNeedsReview` | Gibberish, after a long complaint at check-in | **Needs review**, "Please read it." | Mark resolved |
| `seriousComplaintIsEscalated` | Damage, safety or money lost | **Escalated**, severity 4 or 5 | Mark alert acted on |

Each test picks its reply at random from a pool of **20** (see `ReplyLibrary`), so every run
tests the AI scorer with different wording. Customers are generated with
[Datafaker](https://www.datafaker.net/) using the **Nigerian locale**.

The unreadable-reply test also checks a real bug that was fixed: the scorer used to judge the
fault described at check-in instead of the customer's reply.

### What the tests do not do

- **They never email a customer.** The manager never presses *Send reply*. Private drafts are
  closed with *Mark sent (own email)*, which records the reply without sending anything.
- They don't read the customer's inbox. The feedback form is opened directly with the job code,
  which is exactly the link the email contains.

### Side effects

The tests run against the live system, so every run creates real records:

- jobs in the site's database and rows in Airtable
- a feedback-request email per visit, to a `pw.*@transsahara.com` test address
- for the two escalation tests, **a Slack alert and a manager email**

Test customers are easy to spot: every email address starts with `pw.`. The `no-alert-tests`
suite skips the two escalation tests.

## Schedule

| Workflow | When | Suite |
|---|---|---|
| `weekly-tests.yml` | Mondays at 08:17 Lagos time | `execute-all-tests` |
| `weekly-tests.yml` | On demand (Actions → Run workflow) | your choice of suite |

A full run takes about 2–3 minutes, most of it spent waiting for n8n to score each reply. Reports are
uploaded as an artifact on every run. Traces are **not** uploaded from CI: a trace records every
value typed into the page, the manager passcode included, and this repository is public. To get a
trace for a failing test, run it locally (see below).

## Design

```
src/main/java/pageobjects/   one class per page, with chainable methods
  BasePage                   shared Page and helpers
  CheckInPage                /           the check-in form
  JobSheetPage               /job/{code} the job sheet and Done button
  CloseJobPage               /feedback/{code}  amount paid, close the job
  FeedbackFormPage           the n8n "How did we do?" form
  ManagerLoginPage           /manager    passcode sign-in
  ManagerDashboardPage       /manager    the four queues, rows, reasons and actions
  Queue                      the four queues by their headings
src/test/java/testcases/
  BaseTest                   a fresh browser per test, traces on failure, the shared journey
  FeedbackRoutingTests       the five scenarios
src/test/java/utils/
  Constants                  settings from environment variables or -D properties
  CustomerData               Faker (en-NG) customers and cars
  ReplyLibrary               20 replies per scenario, plus check-in faults
test-runner/                 TestNG suites selected by group
```

How Playwright changes things compared with a Selenium suite:

- **No waits or sleeps.** Playwright waits for each element to be ready before acting, and its
  assertions (`assertThat(locator).hasText(...)`) retry until they pass or time out.
- **No driver management.** Playwright downloads and manages its own browsers.
- **Traces instead of screenshots.** A failed test leaves a trace you can step through in the
  Playwright trace viewer, with a snapshot of the page at every action.

The one deliberate wait is on the manager dashboard. Scoring happens in n8n, outside the
browser, so `ManagerDashboardPage.waitForVisit` reloads the dashboard every 10 seconds until the
visit appears, for up to 3 minutes by default.

## Running locally

You need JDK 17 and Maven. Playwright downloads Chromium the first time it runs.

```bash
# all five scenarios
mvn test -Dmanager.passcode=... -Dfeedback.form.url=https://<your-n8n>/form/feedback

# a single suite
mvn test -DsuiteXmlFile=test-runner/no-alert-tests.xml -Dmanager.passcode=... -Dfeedback.form.url=...

# watch it run in a browser window
mvn test -Dheadless=false -Dmanager.passcode=... -Dfeedback.form.url=...
```

To replay a failed test, open its trace:

```bash
mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="show-trace target/traces/<test>.zip"
```

## Configuration

Each setting is read from an environment variable first, then from a `-D` system property. The
two secrets have no default, and the suite stops with a clear message if either is missing.
They are never committed, because this repository is public.

| Environment variable | `-D` property | Required | Default |
|---|---|---|---|
| `MANAGER_PASSCODE` | `manager.passcode` | yes | — |
| `FEEDBACK_FORM_URL` | `feedback.form.url` | yes | — |
| `BASE_URL` | `base.url` | no | `https://transsahara.com` |
| `ROUTING_TIMEOUT_SECONDS` | `routing.timeout.seconds` | no | `180` |
| `HEADLESS` | `headless` | no | `true` |

For CI, add `MANAGER_PASSCODE` and `FEEDBACK_FORM_URL` as repository secrets (Settings → Secrets
and variables → Actions). `.env.example` lists the same settings for local use.

## Roadmap

- Cover the *Send reply* path against a mailbox the tests can read, to assert the email that
  arrives.
- Tag test rows in Airtable so dashboards can hide them.
- Run the three no-alert scenarios in parallel to shorten the run.
