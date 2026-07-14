package com.anvicorp.api.notification;

/**
 * Renders the HTML body for meeting-related emails (interview, weekly
 * meeting, evaluation). Takes the existing plain-text template output and
 * returns an HTML body that:
 * <ul>
 *   <li>preserves the plain-text content with line breaks + HTML-escapes
 *       all dynamic content (no risk of HTML injection from intern names,
 *       agenda text, etc.);</li>
 *   <li>appends styled "Join Meeting" / "Start Meeting (Host)" buttons
 *       (anchors) for the provided URLs, using the Anvi Corp accent
 *       gradient so they're visually obvious.</li>
 * </ul>
 *
 * <p>Callers should still supply the original plain body to
 * {@link EmailProvider#sendBrandedHtml(String, String, String, String)}
 * — the plain side stays human-readable for clients that don't render
 * HTML (the URL there is the same one embedded in the button so users
 * can paste it manually if needed).</p>
 */
public final class MeetingEmailHtmlBuilder {

    /** Anvi Corp accent orange — matches SmtpEmailProvider chrome. */
    private static final String ACCENT_FROM = "#fb9b47";
    private static final String ACCENT_TO   = "#ff7c20";
    private static final String TEXT_BODY   = "#1f2937";

    private MeetingEmailHtmlBuilder() {}

    /**
     * Render an HTML body fragment with a "Join Meeting" button (the
     * participant-facing variant). For host-facing emails that should
     * carry the start link, use {@link #buildWithHostStart}.
     *
     * @param plainBody  the rendered template body (plain text, already
     *                   substituted). Pass {@code null} for an empty body.
     * @param joinUrl    the meeting join URL. Pass {@code null} or blank to
     *                   skip the button entirely (e.g. for cancelled /
     *                   missed emails that have no live link).
     * @return safe HTML fragment for {@code sendBrandedHtml}.
     */
    public static String build(String plainBody, String joinUrl) {
        return buildWithHostStart(plainBody, joinUrl, null);
    }

    /**
     * Render an HTML body fragment with up to two buttons — a "Join
     * Meeting" button (when {@code joinUrl} is non-blank) AND a "Start
     * Meeting (Host)" button (when {@code startUrl} is non-blank). For
     * scheduler emails pass both; for participant emails pass only
     * {@code joinUrl}.
     */
    public static String buildWithHostStart(String plainBody, String joinUrl,
                                             String startUrl) {
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"margin:0;font-size:15px;line-height:1.55;color:")
                .append(TEXT_BODY).append(";\">");
        if (plainBody != null && !plainBody.isEmpty()) {
            html.append(escapeWithBreaks(plainBody));
        }
        html.append("</div>");
        appendButton(html, joinUrl, "Join Meeting");
        appendButton(html, startUrl, "Start Meeting (Host)");
        return html.toString();
    }

    private static void appendButton(StringBuilder html, String url, String label) {
        if (url == null || url.isBlank()) return;
        String href = escape(url.trim());
        html.append("<div style=\"margin:24px 0 8px;\">")
                .append("<a href=\"").append(href).append("\" target=\"_blank\" ")
                .append("style=\"display:inline-block;padding:12px 28px;")
                .append("background:linear-gradient(135deg,").append(ACCENT_FROM)
                .append(" 0%,").append(ACCENT_TO).append(" 100%);")
                .append("color:#ffffff;font-size:15px;font-weight:600;")
                .append("text-decoration:none;border-radius:8px;")
                .append("box-shadow:0 2px 6px rgba(255,124,32,0.25);\">")
                .append(escape(label)).append("</a>")
                .append("</div>");
    }

    /** Escape HTML-special chars AND convert newlines to {@code <br>}. */
    private static String escapeWithBreaks(String s) {
        return escape(s).replace("\r\n", "<br>").replace("\n", "<br>");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
