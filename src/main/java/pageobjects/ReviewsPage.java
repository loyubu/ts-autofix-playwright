package pageobjects;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;

/** /reviews: what the public sees. Only permitted, published reviews ever reach it. */
public class ReviewsPage extends BasePage {

    public ReviewsPage(Page page) {
        super(page);
    }

    public Response open(String baseUrl) {
        return page.navigate(baseUrl + "/reviews");
    }

    public Locator heading() {
        return page.locator("main h1");
    }

    public Locator unavailableMessage() {
        return page.getByText("Reviews are unavailable just now.");
    }

    public Locator emptyMessage() {
        return page.getByText("Reviews from our customers will appear here.");
    }

    /** One card per published review: the customer's words, then who, where and when. */
    public Locator cards() {
        return page.locator("main ul > li");
    }

    public String words(int card) {
        return cards().nth(card).locator("p").first().textContent().trim();
    }

    /** For example "Ngozi A. · Ikoyi · November 2026", before the page's styling capitalises it. */
    public String signature(int card) {
        return cards().nth(card).locator("p").last().textContent().trim();
    }

    public String mainText() {
        return page.locator("main").textContent();
    }

    /** Everything sent to the browser, including data the page does not show. */
    public String source() {
        return page.content();
    }
}
