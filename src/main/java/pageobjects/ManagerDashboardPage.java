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
    private static final int SUMMARY_COLUMN = 4;
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
            for (Queue queue : Queue.values()) {
                if (reveal(queue, visitId)) {
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

    /**
     * Brings a visit's row onto the screen. A queue lists its latest 5 rows until "Show all N" is
     * pressed, then 10 rows a page. Acting on a row can move it to another page, so call this before
     * reading or clicking a row. Returns false if the visit is not in this queue.
     */
    public boolean reveal(Queue queue, String visitId) {
        Locator section = queue(queue);
        Locator showAll = section.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(Pattern.compile("^Show all \\d+$")));
        if (showAll.count() > 0) {
            showAll.click();
        }
        Locator previous = pageButton(section, "Previous page");
        while (previous.count() > 0 && previous.isEnabled()) {
            previous.click();
        }
        Locator next = pageButton(section, "Next page");
        while (row(queue, visitId).count() == 0) {
            if (next.count() == 0 || !next.isEnabled()) {
                return false;
            }
            next.click();
        }
        return true;
    }

    private static Locator pageButton(Locator section, String name) {
        return section.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(name).setExact(true));
    }

    public String getReason(Queue queue, String visitId) {
        reveal(queue, visitId);
        return row(queue, visitId).locator("td").nth(REASON_COLUMN).innerText().trim();
    }

    public String getSummary(Queue queue, String visitId) {
        reveal(queue, visitId);
        return row(queue, visitId).locator("td").nth(SUMMARY_COLUMN).innerText().trim();
    }

    public int getSeverity(Queue queue, String visitId) {
        reveal(queue, visitId);
        return Integer.parseInt(row(queue, visitId).locator("td").nth(SEVERITY_COLUMN).innerText().trim());
    }

    /**
     * Opens a row to show what the customer wrote, the drafted reply and the action buttons.
     * Clicking an open row would close it, so a row that is already open is left alone.
     */
    public ManagerDashboardPage openVisit(Queue queue, String visitId) {
        reveal(queue, visitId);
        if (!"true".equals(row(queue, visitId).getAttribute("aria-expanded"))) {
            row(queue, visitId).click();
        }
        return this;
    }

    /** "What the customer wrote" in a queue's open row. */
    public Locator customerWords(Queue queue) {
        return queue(queue).locator("blockquote");
    }

    /** The drafted reply box of the open row (Escalated and Private drafts only). */
    public Locator draftReply() {
        return page.locator("textarea[id^='draft-']");
    }

    /** The open Ready-to-post row's line "Customer allowed publishing: Yes/No". */
    public Locator publishingPermission() {
        return page.getByText("Customer allowed publishing:");
    }

    /** One of the open row's buttons by its exact name, for checking it is or is not offered. */
    public Locator actionButton(String buttonName) {
        return button(buttonName);
    }

    /**
     * Presses one of the open row's buttons, for example "Close without publishing". This suite
     * never presses "Send reply" or "Publish on website", so it never emails a customer and never
     * puts anything on the public reviews page.
     */
    public ManagerDashboardPage clickAction(String buttonName) {
        button(buttonName).click();
        return this;
    }

    /** The confirmation the page shows after an action, for example "Closed without publishing." */
    public Locator actionMessage(String text) {
        return page.getByText(text, new Page.GetByTextOptions().setExact(true));
    }

    /** The "Open" / "Acted on" badge an escalated row carries. */
    public Locator alertBadge(String visitId) {
        reveal(Queue.ESCALATED, visitId);
        return row(Queue.ESCALATED, visitId).locator("td").first().locator("span").last();
    }
}
