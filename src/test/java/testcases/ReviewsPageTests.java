package testcases;

import com.microsoft.playwright.Response;
import org.testng.Assert;
import org.testng.annotations.Test;
import pageobjects.ReviewsPage;
import utils.Constants;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * A smoke test of the public reviews page. It only looks: no customer, no sign-in, no records, no
 * alerts. Publishing and taking down stay manual, so nothing here ever puts a review on the page.
 */
public class ReviewsPageTests extends BaseTest {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[A-Za-z]{2,}");
    private static final Pattern JOB_CODE = Pattern.compile("AF-\\d{6}-[A-Z0-9]+");
    private static final Pattern PHONE = Pattern.compile("\\d(?:[ -]?\\d){9,}");

    /** First name and last initial, or "A customer"; a branch; a month where there is one. */
    private static final Pattern SIGNATURE = Pattern.compile(
            "(A customer|[^\\s@]+( .\\.)?) · (" + String.join("|", Constants.BRANCHES) + ")( · [A-Z][a-z]+ \\d{4})?");

    @Test(groups = {"Regression", "NoAlerts", "Reviews"})
    public void reviewsPageLoadsAndShowsNothingPrivate() {
        ReviewsPage reviews = new ReviewsPage(page);
        Response response = reviews.open(Constants.baseUrl());

        Assert.assertEquals(response.status(), 200);
        assertThat(reviews.heading()).hasText("What our customers say");
        assertThat(reviews.unavailableMessage()).hasCount(0);
        // Either reviews or the "will appear here" line, never a blank page.
        assertThat(reviews.cards().first().or(reviews.emptyMessage())).isVisible();

        for (int i = 0; i < reviews.cards().count(); i++) {
            Assert.assertFalse(reviews.words(i).isEmpty(), "Review " + (i + 1) + " has no words.");
            Assert.assertTrue(SIGNATURE.matcher(reviews.signature(i)).matches(),
                    "Review " + (i + 1) + " should show a first name and initial, a branch and a month.");
        }

        // The whole source, not just what is shown: private fields must never leave the server.
        assertNone(EMAIL, reviews.source(), "an email address");
        assertNone(JOB_CODE, reviews.source(), "a job code");
        // Only the visible text, because the source holds long numbers that are timestamps.
        assertNone(PHONE, reviews.mainText(), "a phone number");
    }

    /** Never prints what it found: CI logs are public. */
    private static void assertNone(Pattern pattern, String text, String what) {
        Matcher found = pattern.matcher(text);
        Assert.assertFalse(found.find(), "The public reviews page contains " + what + ".");
    }
}
