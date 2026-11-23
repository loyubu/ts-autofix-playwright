package testcases;

import org.testng.Assert;
import org.testng.annotations.Test;
import pageobjects.ManagerDashboardPage;
import pageobjects.Queue;
import utils.CustomerData;
import utils.ReplyLibrary;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The five routing scenarios, end to end on the live site. Each test is standalone: it creates
 * its own customer, takes the job from check-in to feedback, then signs in as the manager to
 * confirm where the feedback landed, and acts on it so the queue is left clean.
 *
 * The manager never presses "Send reply", so these tests never email a customer.
 */
public class FeedbackRoutingTests extends BaseTest {

    @Test(priority = 1, groups = {"Regression", "NoAlerts", "Positive"})
    public void positiveFeedbackIsReadyToPost() {
        CustomerData customer = new CustomerData();
        String reply = ReplyLibrary.randomFrom(ReplyLibrary.POSITIVE);

        String visitId = checkInAndCloseJob(customer, ReplyLibrary.randomFrom(ReplyLibrary.CHECK_IN_ISSUES));
        // The only test that chooses stars. The others leave the rating blank, which is allowed.
        submitFeedback(visitId, reply, "4 - Good");

        ManagerDashboardPage dashboard = signInAsManager();
        assertRoutedTo(dashboard, visitId, Queue.READY_TO_POST);
        Assert.assertEquals(dashboard.getReason(Queue.READY_TO_POST, visitId), "Positive");

        // The form's permission box is left unticked, so this review may never be published:
        // the page says so, offers no Publish button, and the test closes it without publishing.
        // The weekly run therefore never puts anything on the public reviews page.
        dashboard.openVisit(Queue.READY_TO_POST, visitId);
        assertThat(dashboard.customerWords(Queue.READY_TO_POST)).hasText(reply);
        assertThat(dashboard.stars(Queue.READY_TO_POST)).hasCount(1);
        assertThat(dashboard.stars(Queue.READY_TO_POST)).hasAttribute("aria-label", "4 out of 5 stars");
        assertThat(dashboard.publishingPermission()).containsText("No");
        assertThat(dashboard.actionButton("Publish on website")).hasCount(0);
        dashboard.clickAction("Close without publishing");
        assertLeftTheQueues(dashboard, Queue.READY_TO_POST, visitId);
    }

    @Test(priority = 2, groups = {"Regression", "NoAlerts", "FirstComplaint"})
    public void firstComplaintGoesToPrivateDrafts() {
        CustomerData customer = new CustomerData();
        String reply = ReplyLibrary.randomFrom(ReplyLibrary.FIRST_COMPLAINT);

        String visitId = checkInAndCloseJob(customer, ReplyLibrary.randomFrom(ReplyLibrary.CHECK_IN_ISSUES));
        submitFeedback(visitId, reply);

        ManagerDashboardPage dashboard = signInAsManager();
        assertRoutedTo(dashboard, visitId, Queue.PRIVATE_DRAFTS);
        Assert.assertTrue(dashboard.getReason(Queue.PRIVATE_DRAFTS, visitId).startsWith("First negative"),
                "Unexpected reason: " + dashboard.getReason(Queue.PRIVATE_DRAFTS, visitId));
        Assert.assertTrue(dashboard.getSeverity(Queue.PRIVATE_DRAFTS, visitId) < 4,
                "A first complaint in Private drafts should be below severity 4.");

        dashboard.openVisit(Queue.PRIVATE_DRAFTS, visitId);
        assertThat(dashboard.customerWords(Queue.PRIVATE_DRAFTS)).hasText(reply);
        // No rating was chosen, so no stars are shown.
        assertThat(dashboard.stars(Queue.PRIVATE_DRAFTS)).hasCount(0);
        assertDraftedFor(dashboard, customer);

        // "Mark sent (own email)" records the reply without the site emailing anyone.
        dashboard.clickAction("Mark sent (own email)");
        assertLeftTheQueues(dashboard, Queue.PRIVATE_DRAFTS, visitId);
    }

    @Test(priority = 3, groups = {"Regression", "Escalation", "RepeatComplaint"})
    public void repeatComplaintIsEscalated() {
        CustomerData customer = new CustomerData();

        // Visit 1: an ordinary first complaint gives this customer a history.
        String firstVisit = checkInAndCloseJob(customer, ReplyLibrary.randomFrom(ReplyLibrary.CHECK_IN_ISSUES));
        submitFeedback(firstVisit, ReplyLibrary.randomFrom(ReplyLibrary.FIRST_COMPLAINT));

        ManagerDashboardPage dashboard = signInAsManager();
        assertRoutedTo(dashboard, firstVisit, Queue.PRIVATE_DRAFTS);
        dashboard.openVisit(Queue.PRIVATE_DRAFTS, firstVisit).clickAction("Mark sent (own email)");
        assertLeftTheQueues(dashboard, Queue.PRIVATE_DRAFTS, firstVisit);

        // Visit 2: the same customer, a deliberately mild complaint. History alone escalates it.
        String reply = ReplyLibrary.randomFrom(ReplyLibrary.REPEAT_COMPLAINT);
        String secondVisit = checkInAndCloseJob(customer, ReplyLibrary.randomFrom(ReplyLibrary.CHECK_IN_ISSUES));
        submitFeedback(secondVisit, reply);

        dashboard = signInAsManager();
        assertRoutedTo(dashboard, secondVisit, Queue.ESCALATED);
        Assert.assertTrue(dashboard.getReason(Queue.ESCALATED, secondVisit).startsWith("Repeat complaint"),
                "Unexpected reason: " + dashboard.getReason(Queue.ESCALATED, secondVisit));
        // Below 4, severity alone would not escalate it, so this proves the history did.
        Assert.assertTrue(dashboard.getSeverity(Queue.ESCALATED, secondVisit) < 4,
                "A mild repeat complaint should be below severity 4, or it would escalate without its history.");

        dashboard.openVisit(Queue.ESCALATED, secondVisit);
        assertThat(dashboard.customerWords(Queue.ESCALATED)).hasText(reply);
        assertDraftedFor(dashboard, customer);

        actOnAlert(dashboard, secondVisit);
    }

    @Test(priority = 4, groups = {"Regression", "NoAlerts", "NeedsReview"})
    public void unreadableReplyNeedsReview() {
        CustomerData customer = new CustomerData();
        String reply = ReplyLibrary.randomFrom(ReplyLibrary.UNREADABLE);

        // A long complaint at check-in: the scorer must judge the reply, not this.
        String visitId = checkInAndCloseJob(customer,
                ReplyLibrary.randomFrom(ReplyLibrary.LONG_NEGATIVE_CHECK_IN_ISSUES));
        submitFeedback(visitId, reply);

        ManagerDashboardPage dashboard = signInAsManager();
        assertRoutedTo(dashboard, visitId, Queue.NEEDS_REVIEW);
        // The whole reason, because a failed or unsure AI also ends "Please read it."
        Assert.assertEquals(dashboard.getReason(Queue.NEEDS_REVIEW, visitId),
                "Reply unclear or not about the visit. Please read it.");
        // The scoring prompt's fixed summary, so nothing from the check-in has leaked into it.
        Assert.assertEquals(dashboard.getSummary(Queue.NEEDS_REVIEW, visitId),
                "The reply could not be understood.");

        dashboard.openVisit(Queue.NEEDS_REVIEW, visitId);
        assertThat(dashboard.customerWords(Queue.NEEDS_REVIEW)).hasText(reply);
        assertThat(dashboard.stars(Queue.NEEDS_REVIEW)).hasCount(0);
        dashboard.clickAction("Mark resolved");
        assertLeftTheQueues(dashboard, Queue.NEEDS_REVIEW, visitId);
    }

    @Test(priority = 5, groups = {"Regression", "Escalation", "SeriousComplaint"})
    public void seriousComplaintIsEscalated() {
        CustomerData customer = new CustomerData();
        // Drawn from both pools as one, so a safety complaint comes up about half the time.
        String reply = ReplyLibrary.randomFrom(Stream.concat(ReplyLibrary.SAFETY_COMPLAINT.stream(),
                ReplyLibrary.SERIOUS_COMPLAINT.stream()).toList());

        String visitId = checkInAndCloseJob(customer, ReplyLibrary.randomFrom(ReplyLibrary.CHECK_IN_ISSUES));
        submitFeedback(visitId, reply);

        ManagerDashboardPage dashboard = signInAsManager();
        assertRoutedTo(dashboard, visitId, Queue.ESCALATED);
        Assert.assertTrue(dashboard.getReason(Queue.ESCALATED, visitId).startsWith("Severity"),
                "Unexpected reason: " + dashboard.getReason(Queue.ESCALATED, visitId));
        Assert.assertTrue(dashboard.getSeverity(Queue.ESCALATED, visitId) >= 4,
                "A serious complaint should be severity 4 or 5.");
        if (ReplyLibrary.SAFETY_COMPLAINT.contains(reply)) {
            Assert.assertEquals(dashboard.getSeverity(Queue.ESCALATED, visitId), 5,
                    "A safety complaint must be severity 5.");
        }

        dashboard.openVisit(Queue.ESCALATED, visitId);
        assertThat(dashboard.customerWords(Queue.ESCALATED)).hasText(reply);
        assertDraftedFor(dashboard, customer);
        if (ReplyLibrary.SAFETY_COMPLAINT.contains(reply)) {
            assertThat(dashboard.draftReply()).hasValue(
                    Pattern.compile("do not drive the car", Pattern.CASE_INSENSITIVE));
        }

        actOnAlert(dashboard, visitId);
    }

    /** Escalated rows stay on the dashboard; acting on the alert flips their badge to "Acted on". */
    private void actOnAlert(ManagerDashboardPage dashboard, String visitId) {
        assertThat(dashboard.alertBadge(visitId)).hasText("Open");
        dashboard.openVisit(Queue.ESCALATED, visitId).clickAction("Mark alert acted on");
        assertThat(dashboard.actionMessage("Alert marked as acted on.")).isVisible();
        assertThat(dashboard.alertBadge(visitId)).hasText("Acted on");
    }
}
