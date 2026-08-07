package com.anvicorp.api.common;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Shared helpers for building HTTP {@code Content-Disposition} filename
 * values. Extracted from {@code DocumentGalleryService} (B3 ZIP export)
 * so the IDMS executed-PDF download and any other vault download uses
 * the same RFC-5987-safe pattern.
 *
 * <p>Two-header pattern always emitted:</p>
 * <pre>
 *   Content-Disposition: attachment; filename="ascii-fallback.pdf"; filename*=UTF-8''unicode%20name.pdf
 * </pre>
 * <p>Legacy clients read {@code filename=}, modern clients read
 * {@code filename*=}. Both are always emitted so a non-ASCII intern
 * name still arrives with the right label.</p>
 */
public final class ContentDispositionFilenames {

    private ContentDispositionFilenames() {}

    /**
     * Build the full {@code Content-Disposition} value.
     *
     * @param disposition {@code "attachment"} or {@code "inline"}
     * @param unicodeFilename the display filename (may contain non-ASCII).
     *                        Passed through {@link #sanitizeFilename} to
     *                        strip control chars + Windows-illegal set.
     */
    public static String forFilename(String disposition, String unicodeFilename) {
        String safe = sanitizeFilename(unicodeFilename);
        String ascii = sanitizeAscii(safe);
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return disposition + "; filename=\"" + ascii + "\"; "
                + "filename*=UTF-8''" + encoded;
    }

    /**
     * Extract a file extension for a given {@link String} MIME type +
     * optional source filename hint. Used by IDMS to derive a {@code
     * .pdf} extension from the stored {@code mime_type} even if the
     * legacy on-disk key ends in {@code .bin}.
     *
     * <p>Falls back to {@code ""} for unrecognised types so the caller
     * can decide whether to omit the extension or supply a default.</p>
     */
    public static String extensionForMime(String mimeType, String fileNameHint) {
        if (fileNameHint != null) {
            int dot = fileNameHint.lastIndexOf('.');
            if (dot >= 0 && dot < fileNameHint.length() - 1) {
                String hinted = fileNameHint.substring(dot + 1)
                        .toLowerCase(Locale.ROOT);
                // Ignore the ".bin" convention the vault uses on-disk
                // when a real MIME type is available.
                if (!"bin".equals(hinted)) return hinted;
            }
        }
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return switch (mime) {
            case "application/pdf"          -> "pdf";
            case "image/png"                -> "png";
            case "image/jpeg", "image/jpg"  -> "jpg";
            case "image/gif"                -> "gif";
            case "image/webp"               -> "webp";
            case "text/plain"               -> "txt";
            case "text/html"                -> "html";
            case "application/zip"          -> "zip";
            default                         -> "";
        };
    }

    /** Replace control chars + Windows-illegal set + path separators
     *  with {@code _} so entry names are safe on every extractor. */
    public static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "Untitled";
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c < 0x20 || c == 0x7F) { out.append('_'); continue; }
            switch (c) {
                case '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> out.append('_');
                default -> out.append(c);
            }
        }
        String cleaned = out.toString().trim();
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned.isEmpty() ? "Untitled" : cleaned;
    }

    /** ASCII fallback for legacy {@code filename=} header. Non-ASCII
     *  chars stripped; the {@code filename*=} carries the true name. */
    public static String sanitizeAscii(String raw) {
        if (raw == null) return "Untitled";
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= 0x20 && c <= 0x7E && c != '"' && c != '\\') out.append(c);
        }
        String cleaned = sanitizeFilename(out.toString());
        return cleaned.isEmpty() ? "Untitled" : cleaned;
    }
}
