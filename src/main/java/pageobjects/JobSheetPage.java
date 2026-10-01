package pageobjects;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.regex.Pattern;

/** /job/{code}: the job sheet a customer sees after checking in, with the Done button. */
public class JobSheetPage extends BasePage {

    public static final Pattern JOB_CODE = Pattern.compile("AF-\\d{6}-[A-Z0-9]{8}");

    public JobSheetPage(Page page) {
        super(page);
    }

    /**
     * The job code in the page heading, for example AF-260930-62ZBXGYM. The site changes the
     * address before it draws the job sheet, so this waits for a heading that is a job code.
     */
    public String getJobCode() {
        Locator heading = page.locator("main h1").filter(new Locator.FilterOptions().setHasText(JOB_CODE));
        heading.waitFor();
        return heading.innerText().trim();
    }

    /** The job details panel, for checking what the customer entered was saved. */
    public Locator details() {
        return page.locator("main section").first();
    }

    public Locator completedMessage() {
        return page.getByText("This job is completed");
    }

    /** Presses Done and moves to the close-job page. */
    public CloseJobPage pressDone() {
        button("Done").click();
        page.waitForURL("**/feedback/**");
        return new CloseJobPage(page);
    }
}
