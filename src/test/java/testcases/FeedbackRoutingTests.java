package testcases;

import com.microsoft.playwright.assertions.LocatorAssertions;
import org.testng.Assert;
import org.testng.annotations.Test;
import pageobjects.ManagerDashboardPage;
import pageobjects.Queue;
import utils.CustomerData;
import utils.ReplyLibrary;

import java.util.regex.Pattern;

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
        submitFeedback(visitId, reply);

        ManagerDashboardPage dashboard = signInAsManager();
        assertRoutedTo(dashboard, visitId, Queue.READY_TO_POST);
        Assert.assertEquals(dashboard.getReason(Queue.READY_TO_POST, visitId), "Positive");

        // Acting moves the row out of the queue, so the check is that it has gone.
        dashboard.openVisit(Queue.READY_TO_POST, visitId).clickAction("Mark posted");
        assertThat(dashboard.row(Queue.READY_TO_POST, visitId)).hasCount(0);
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
        // n8n saves the drafted reply a few seconds after the row; the page picks it up on refresh.
        assertThat(dashboard.draftReply()).not().hasValue("",
                new LocatorAssertions.HasValueOptions().setTimeout(90_000));
        assertThat(dashboard.draftReply()).hasValue(Pattern.compile("^Dear "));

        // "Mark sent (own email)" records the reply without the site emailing anyone.
        dashboard.clickAction("Mark sent (own email)");
        assertThat(dashboard.row(Queue.PRIVATE_DRAFTS, visitId)).hasCount(0);
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
        assertThat(dashboard.row(Queue.PRIVATE_DRAFTS, firstVisit)).hasCount(0);

        // Visit 2: the same customer, a deliberately mild complaint. History alone escalates it.
        String reply = ReplyLibrary.randomFrom(ReplyLibrary.REPEAT_COMPLAINT);
        String secondVisit = checkInAndCloseJob(customer, ReplyLibrary.randomFrom(ReplyLibrary.CHECK_IN_ISSUES));
        submitFeedback(secondVisit, reply);

        dashboard = signInAsManager();
        assertRoutedTo(dashboard, secondVisit, Queue.ESCALATED);
        Assert.assertTrue(dashboard.getReason(Queue.ESCALATED, secondVisit).startsWith("Repeat complaint"),
                "Unexpected reason: " + dashboard.getReason(Queue.ESCALATED, secondVisit));

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
        Assert.assertTrue(dashboard.getReason(Queue.NEEDS_REVIEW, visitId).endsWith("Please read it."),
                "Unexpected reason: " + dashboard.getReason(Queue.NEEDS_REVIEW, visitId));

        dashboard.openVisit(Queue.NEEDS_REVIEW, visitId).clickAction("Mark resolved");
        assertThat(dashboard.row(Queue.NEEDS_REVIEW, visitId)).hasCount(0);
    }

    @Test(priority = 5, groups = {"Regression", "Escalation", "SeriousComplaint"})
    public void seriousComplaintIsEscalated() {
        CustomerData customer = new CustomerData();
        String reply = ReplyLibrary.randomFrom(ReplyLibrary.SERIOUS_COMPLAINT);

        String visitId = checkInAndCloseJob(customer, ReplyLibrary.randomFrom(ReplyLibrary.CHECK_IN_ISSUES));
        submitFeedback(visitId, reply);

        ManagerDashboardPage dashboard = signInAsManager();
        assertRoutedTo(dashboard, visitId, Queue.ESCALATED);
        Assert.assertTrue(dashboard.getReason(Queue.ESCALATED, visitId).startsWith("Severity"),
                "Unexpected reason: " + dashboard.getReason(Queue.ESCALATED, visitId));
        Assert.assertTrue(dashboard.getSeverity(Queue.ESCALATED, visitId) >= 4,
                "A serious complaint should be severity 4 or 5.");

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
