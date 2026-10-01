package pageobjects;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** /manager before sign-in: a single passcode field. */
public class ManagerLoginPage extends BasePage {

    public ManagerLoginPage(Page page) {
        super(page);
    }

    public ManagerLoginPage open(String baseUrl) {
        page.navigate(baseUrl + "/manager");
        return this;
    }

    /** Signs in, unless this browser is still signed in from earlier in the test. */
    public ManagerDashboardPage signIn(String passcode) {
        ManagerDashboardPage dashboard = new ManagerDashboardPage(page);
        Locator passcodeField = page.locator("#passcode");
        passcodeField.or(dashboard.signOutLink()).first().waitFor();
        if (passcodeField.isVisible()) {
            passcodeField.fill(passcode);
            button("Open dashboard").click();
        }
        assertThat(dashboard.signOutLink()).isVisible();
        return dashboard;
    }
}
