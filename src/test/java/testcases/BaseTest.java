package testcases;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import pageobjects.CheckInPage;
import pageobjects.CloseJobPage;
import pageobjects.FeedbackFormPage;
import pageobjects.JobSheetPage;
import pageobjects.ManagerDashboardPage;
import pageobjects.ManagerLoginPage;
import pageobjects.Queue;
import utils.Constants;
import utils.CustomerData;

import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Opens a fresh browser for every test, so each test stands alone, and keeps a Playwright trace
 * of any test that fails (target/traces) to replay step by step.
 *
 * Also holds the customer journey the tests share: check in, close the job, reply to the
 * feedback form, and the manager's sign-in.
 */
public class BaseTest {

    protected Playwright playwright;
    protected Browser browser;
    protected BrowserContext context;
    protected Page page;

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(Constants.headless()));
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1366, 900));
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        page = context.newPage();
        page.setDefaultTimeout(30_000);
        PlaywrightAssertions.setDefaultAssertionTimeout(30_000);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown(ITestResult result) {
        try {
            if (result.isSuccess()) {
                context.tracing().stop();
            } else {
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                context.tracing().stop(new Tracing.StopOptions()
                        .setPath(Paths.get("target", "traces", result.getName() + "-" + stamp + ".zip")));
            }
        } finally {
            playwright.close();
        }
    }

    /**
     * The customer checks the car in, presses Done and closes the job. Asserts the job sheet
     * shows what was entered and the job ends up completed. Returns the job code, which is also
     * the visit_id n8n uses.
     */
    protected String checkInAndCloseJob(CustomerData customer, String issue) {
        JobSheetPage jobSheet = new CheckInPage(page)
                .open(Constants.baseUrl())
                .selectBranch(customer.getBranch())
                .enterFullName(customer.getFullName())
                .enterPhoneNumber(customer.getPhone())
                .enterCarMake(customer.getCarMake())
                .enterCarModel(customer.getCarModel())
                .enterEmail(customer.getEmail())
                .enterIssue(issue)
                .submit();

        String jobCode = jobSheet.getJobCode();
        Assert.assertTrue(JobSheetPage.JOB_CODE.matcher(jobCode).matches(), "Unexpected job code: " + jobCode);
        assertThat(jobSheet.details()).containsText(customer.getFullName());
        assertThat(jobSheet.details()).containsText(customer.getBranch());
        assertThat(jobSheet.details()).containsText(issue);

        CloseJobPage closeJob = jobSheet.pressDone();
        JobSheetPage closed = closeJob.enterAmountInNaira(customer.getAmountNaira()).confirmAndClose();
        assertThat(closed.completedMessage()).isVisible();

        System.out.println("Checked in and closed " + jobCode + " for " + customer);
        return jobCode;
    }

    /** Replies on the n8n feedback form, as the customer would from the email link, without stars. */
    protected void submitFeedback(String visitId, String reply) {
        submitFeedback(visitId, reply, null);
    }

    /** The same, choosing a star rating such as "4 - Good". Null leaves the rating blank. */
    protected void submitFeedback(String visitId, String reply, String rating) {
        FeedbackFormPage form = new FeedbackFormPage(page)
                .open(Constants.feedbackFormUrl(), visitId)
                .enterFeedback(reply);
        if (rating != null) {
            form.chooseRating(rating);
        }
        form.submit();
        assertThat(form.confirmationHeader()).isVisible();
        assertThat(form.confirmationHeader()).hasText(Constants.FORM_SUBMITTED_TEXT);
        System.out.println("Replied for " + visitId + ": \"" + reply + "\"");
    }

    protected ManagerDashboardPage signInAsManager() {
        return new ManagerLoginPage(page)
                .open(Constants.baseUrl())
                .signIn(Constants.managerPasscode());
    }

    /**
     * Waits until the visit reaches the dashboard and asserts it is in the expected queue.
     * On a miss, the message names the queue it went to instead.
     */
    protected void assertRoutedTo(ManagerDashboardPage dashboard, String visitId, Queue expected) {
        Queue actual = dashboard.waitForVisit(visitId, Constants.routingTimeout())
                .orElseThrow(() -> new AssertionError(visitId + " did not reach the manager dashboard within "
                        + Constants.routingTimeout().toSeconds() + "s. Check n8n's 02 Reply & Route executions."));
        Assert.assertEquals(actual, expected, visitId + " was routed to the wrong queue.");
    }

    /**
     * Waits for the open row's drafted reply, then asserts it is addressed to this customer by
     * first name and signed off for their branch, two fixed rules of n8n's draft prompt. Wording,
     * length and tone are judged by eval/draft_eval.py, not here.
     */
    protected void assertDraftedFor(ManagerDashboardPage dashboard, CustomerData customer) {
        // n8n saves the drafted reply a few seconds after the row; the page picks it up on refresh.
        assertThat(dashboard.draftReply()).not().hasValue("",
                new LocatorAssertions.HasValueOptions().setTimeout(90_000));
        String draft = dashboard.draftReply().inputValue().strip();
        Assert.assertTrue(draft.startsWith("Dear " + customer.getFirstName() + ","),
                "The draft is not addressed to " + customer.getFirstName() + ":\n" + draft);
        Pattern signOff = Pattern.compile(
                "\\[Manager name\\]\\s*\\n\\s*" + Pattern.quote(customer.getBranch()) + " branch$");
        Assert.assertTrue(signOff.matcher(draft).find(),
                "The draft is not signed off for the " + customer.getBranch() + " branch:\n" + draft);
    }

    /**
     * Asserts that acting on a visit moved it out of its queue for good. The row disappearing is
     * not enough: it also disappears if the manager is signed out or the page fails to load. So
     * this also checks the dashboard is still there, then reloads it and looks in every queue.
     */
    protected void assertLeftTheQueues(ManagerDashboardPage dashboard, Queue from, String visitId) {
        assertThat(dashboard.row(from, visitId)).hasCount(0);
        assertThat(dashboard.signOutLink()).isVisible();

        page.reload();
        assertThat(dashboard.signOutLink()).isVisible();
        for (Queue queue : Queue.values()) {
            Assert.assertFalse(dashboard.reveal(queue, visitId),
                    visitId + " was acted on but is still in " + queue.title() + " after a reload.");
        }
    }
}
