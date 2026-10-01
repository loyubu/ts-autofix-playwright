package pageobjects;

import com.microsoft.playwright.Page;

/** The home page's "New repair check-in" form. */
public class CheckInPage extends BasePage {

    public CheckInPage(Page page) {
        super(page);
    }

    public CheckInPage open(String baseUrl) {
        page.navigate(baseUrl + "/#intake");
        return this;
    }

    public CheckInPage selectBranch(String branch) {
        page.locator("select[name='branch']").selectOption(branch);
        return this;
    }

    public CheckInPage enterFullName(String name) {
        page.locator("input[name='clientName']").fill(name);
        return this;
    }

    public CheckInPage enterPhoneNumber(String phone) {
        page.locator("input[name='phone']").fill(phone);
        return this;
    }

    public CheckInPage enterCarMake(String make) {
        page.locator("input[name='carMake']").fill(make);
        return this;
    }

    public CheckInPage enterCarModel(String model) {
        page.locator("input[name='carModel']").fill(model);
        return this;
    }

    public CheckInPage enterEmail(String email) {
        page.locator("input[name='email']").fill(email);
        return this;
    }

    public CheckInPage enterIssue(String issue) {
        page.locator("textarea[name='issue']").fill(issue);
        return this;
    }

    /** Submits the check-in and lands on the new job sheet. */
    public JobSheetPage submit() {
        button("Submit & get job code").click();
        page.waitForURL("**/job/**");
        return new JobSheetPage(page);
    }
}
