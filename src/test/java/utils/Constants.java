package utils;

import java.time.Duration;

/**
 * Settings for a run. Each value is read from an environment variable first (used by CI), then
 * from a -D system property (handy for local runs). The two secrets have no default: the suite
 * fails fast with a clear message rather than running half-configured. Neither is ever committed,
 * because this repository is public.
 */
public final class Constants {

    /** Shown by the n8n form once a reply is accepted. */
    public static final String FORM_SUBMITTED_TEXT = "Form Submitted";

    /** The four branches the site offers at check-in. */
    public static final String[] BRANCHES = {"Ikoyi", "Gbagada", "Maitama", "Effurun"};

    private Constants() {
    }

    public static String baseUrl() {
        return stripTrailingSlash(optional("BASE_URL", "base.url", "https://transsahara.com"));
    }

    public static String feedbackFormUrl() {
        return stripTrailingSlash(required("FEEDBACK_FORM_URL", "feedback.form.url",
                "the n8n feedback form address the customer email links to, without ?visit_id="));
    }

    public static String managerPasscode() {
        return required("MANAGER_PASSCODE", "manager.passcode", "the passcode for /manager");
    }

    /** How long to wait for n8n to score a reply and the dashboard to show it. */
    public static Duration routingTimeout() {
        return Duration.ofSeconds(Long.parseLong(
                optional("ROUTING_TIMEOUT_SECONDS", "routing.timeout.seconds", "180")));
    }

    public static boolean headless() {
        return Boolean.parseBoolean(optional("HEADLESS", "headless", "true"));
    }

    private static String required(String envKey, String propKey, String what) {
        String value = lookup(envKey, propKey);
        if (value == null) {
            throw new IllegalStateException("Missing setting: " + what + ". Set the " + envKey
                    + " environment variable, or pass -D" + propKey + "=... to Maven.");
        }
        return value;
    }

    private static String optional(String envKey, String propKey, String fallback) {
        String value = lookup(envKey, propKey);
        return value == null ? fallback : value;
    }

    private static String lookup(String envKey, String propKey) {
        String value = System.getenv(envKey);
        if (isBlank(value)) {
            value = System.getProperty(propKey);
        }
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
