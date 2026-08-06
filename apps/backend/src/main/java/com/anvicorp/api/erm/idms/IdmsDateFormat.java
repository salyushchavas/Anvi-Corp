package com.anvicorp.api.erm.idms;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDMS date-format contract — every document-interpolated date renders as
 * {@code MM/DD/YYYY} (US letter convention). Peer of the frontend
 * {@code formatIsoDateMdy} helper in {@code lib/careers/idms.ts}; both
 * must produce the identical string for the same ISO input so the live
 * preview and the finalized PDF are byte-equal.
 *
 * <p>Locale-independent by construction — we parse the ISO literal's
 * year/month/day and reassemble; no {@link java.time.format.DateTimeFormatter}
 * locale means no Turkish-locale surprises on {@code MM/dd}.</p>
 */
public final class IdmsDateFormat {

    private IdmsDateFormat() {}

    /** Accepts either bare {@code YYYY-MM-DD} or a full ISO datetime and
     *  extracts the date portion. */
    private static final Pattern ISO_DATE_PREFIX =
            Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})");

    private static final DateTimeFormatter MDY =
            DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * Format an ISO date string (or the date portion of a datetime) as
     * {@code MM/DD/YYYY}. Returns the input verbatim when the string
     * isn't ISO-shaped — safer than hiding an already-formatted or
     * mis-stored value from the reader.
     */
    public static String formatIsoDateString(String s) {
        if (s == null || s.isEmpty()) return "";
        Matcher m = ISO_DATE_PREFIX.matcher(s.trim());
        if (!m.find()) return s;
        return m.group(2) + "/" + m.group(3) + "/" + m.group(1);
    }

    /** Format a LocalDate as {@code MM/DD/YYYY}. */
    public static String formatLocalDate(LocalDate d) {
        if (d == null) return "";
        return d.format(MDY);
    }
}
