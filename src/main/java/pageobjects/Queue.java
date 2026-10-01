package pageobjects;

/** The four queues on the manager dashboard, by the heading each one shows. */
public enum Queue {
    ESCALATED("Escalated"),
    NEEDS_REVIEW("Needs review"),
    PRIVATE_DRAFTS("Private drafts"),
    READY_TO_POST("Ready to post");

    private final String title;

    Queue(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
