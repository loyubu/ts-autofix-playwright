package pageobjects;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The n8n form "How did we do?" that the feedback email links to. The suite cannot read the
 * customer's inbox, so it opens the same link the email contains: the form address plus
 * ?visit_id={job code}. Submitting it starts n8n's "02 Reply &amp; Route".
 */
public class FeedbackFormPage extends BasePage {

    public FeedbackFormPage(Page page) {
        super(page);
    }

    public FeedbackFormPage open(String formUrl, String visitId) {
        page.navigate(formUrl + "?visit_id=" + URLEncoder.encode(visitId, StandardCharsets.UTF_8));
        return this;
    }

    public FeedbackFormPage enterFeedback(String feedback) {
        page.locator("#field-0").fill(feedback);
        return this;
    }

    public FeedbackFormPage submit() {
        page.locator("#submit-btn").click();
        return this;
    }

    /** "Form Submitted" once n8n has accepted the reply. */
    public Locator confirmationHeader() {
        return page.locator("#submitted-header");
    }
}
