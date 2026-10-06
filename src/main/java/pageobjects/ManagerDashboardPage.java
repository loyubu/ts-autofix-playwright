package pageobjects;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * /manager after sign-in: the four queues, each a table with one row per open item.
 * A row's first cell holds the visit (job) code; the last holds "Why here", the routing reason.
 */
public class ManagerDashboardPage extends BasePage {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);
    private static final int SEVERITY_COLUMN = 5;
    private static final int REASON_COLUMN = 6;

    public ManagerDashboardPage(Page page) {
        super(page);
    }

    public Locator signOutLink() {
        return button("Sign out");
    }

    /** The panel holding one queue, found by its heading. */
    public Locator queue(Queue queue) {
        return page.locator("h2", new Page.LocatorOptions().setHasText(queue.title()))
                .locator("xpath=ancestor::section[1]");
    }

    /** A visit's summary row in a queue. Only summary rows carry aria-expanded. */
    public Locator row(Queue queue, String visitId) {
        return queue(queue).locator("tr[aria-expanded]",
                new Locator.LocatorOptions().setHasText(visitId));
    }

    /**
     * Waits for n8n to score and route the reply, reloading the dashboard until the visit shows
     * up in one of the queues. Returns which queue, or empty if it never arrived.
     */
    public Optional<Queue> waitForVisit(String visitId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            signOutLink().waitFor();
            showAllRows();
            for (Queue queue : Queue.values()) {
                if (row(queue, visitId).count() > 0) {
                    return Optional.of(queue);
                }
            }
            if (Instant.now().isAfter(deadline)) {
                return Optional.empty();
            }
            page.waitForTimeout(POLL_INTERVAL.toMillis());
            page.reload();
        }
    }

    /** Queues show their latest 5 rows until "Show all N" is pressed; press it in every queue. */
    public ManagerDashboardPage showAllRows() {
        Locator showAll = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("^Show all \\d+$")));
        while (showAll.count() > 0) {
            showAll.first().click();
        }
        return this;
    }

    public String getReason(Queue queue, String visitId) {
        return row(queue, visitId).locator("td").nth(REASON_COLUMN).innerText().trim();
    }

    public int getSeverity(Queue queue, String visitId) {
        return Integer.parseInt(row(queue, visitId).locator("td").nth(SEVERITY_COLUMN).innerText().trim());
    }

    /** Opens a row to show what the customer wrote, the drafted reply and the action buttons. */
    public ManagerDashboardPage openVisit(Queue queue, String visitId) {
        row(queue, visitId).click();
        return this;
    }

    /** The drafted reply box of the open row (Escalated and Private drafts only). */
    public Locator draftReply() {
        return page.locator("textarea[id^='draft-']");
    }

    /**
     * Presses one of the open row's buttons, for example "Mark posted". This suite never presses
     * "Send reply", so it never emails a customer.
     */
    public ManagerDashboardPage clickAction(String buttonName) {
        button(buttonName).click();
        return this;
    }

    /** The confirmation the page shows after an action, for example "Marked as posted." */
    public Locator actionMessage(String text) {
        return page.getByText(text, new Page.GetByTextOptions().setExact(true));
    }

    /** The "Open" / "Acted on" badge an escalated row carries. */
    public Locator alertBadge(String visitId) {
        return row(Queue.ESCALATED, visitId).locator("td").first().locator("span").last();
    }
}
