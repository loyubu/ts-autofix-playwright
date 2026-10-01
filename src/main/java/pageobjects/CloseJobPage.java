package pageobjects;

import com.microsoft.playwright.Page;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** /feedback/{code}: the customer records the agreed amount and closes the job. */
public class CloseJobPage extends BasePage {

    public CloseJobPage(Page page) {
        super(page);
    }

    public CloseJobPage enterAmountInNaira(int amount) {
        page.locator("input[name='amount']").fill(String.valueOf(amount));
        return this;
    }

    /**
     * Closes the job. The site needs today's naira/dollar rate before it will close one, so this
     * waits for the rate line first. Closing triggers n8n's "03 Job complete", which emails the
     * customer the feedback form.
     */
    public JobSheetPage confirmAndClose() {
        assertThat(page.getByText("Rate used:")).isVisible();
        button("Confirm & close job").click();
        page.waitForURL("**/job/**");
        return new JobSheetPage(page);
    }
}
