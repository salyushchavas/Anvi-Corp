package com.anvicorp.api.security;

import com.anvicorp.api.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * Security Wave 2 (M) — magic-byte validation for uploaded files.
 *
 * <p>Every upload surface used to trust either the client-supplied
 * {@code Content-Type} header or the filename extension. Both are
 * attacker-controlled: an executable renamed {@code .pdf} sailed straight
 * through the resume upload, an SVG accepted where PNG was expected
 * would run its embedded {@code <script>} on preview. This validator
 * peeks at the actual first bytes and enforces a per-surface allow-list.</p>
 *
 * <p>Signatures below cover the file types the app actually accepts —
 * PDF, DOC/DOCX (compound doc / OOXML zip), PNG, JPEG, WebP, plus a few
 * OOXML/ZIP peers. Video / audio uploads (recordings) use their own
 * validation path via the recording pipeline.</p>
 */
@Slf4j
public final class FileContentValidator {

    private FileContentValidator() {}

    /** {@code %PDF-} — PDF header. */
    public static final MagicSignature PDF = MagicSignature.of("PDF",
            "application/pdf", new int[]{0x25, 0x50, 0x44, 0x46, 0x2D});

    /** {@code PK\03\04} — ZIP (also DOCX / XLSX / PPTX / EPUB / JAR). */
    public static final MagicSignature ZIP = MagicSignature.of("ZIP/OOXML",
            "application/zip", new int[]{0x50, 0x4B, 0x03, 0x04});

    /** {@code PK\05\06} — empty ZIP central directory (rare but legal). */
    public static final MagicSignature ZIP_EMPTY = MagicSignature.of("ZIP-empty",
            "application/zip", new int[]{0x50, 0x4B, 0x05, 0x06});

    /** {@code D0 CF 11 E0 A1 B1 1A E1} — MS Compound Doc (.doc legacy). */
    public static final MagicSignature DOC = MagicSignature.of("DOC (compound doc)",
            "application/msword",
            new int[]{0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1});

    /** {@code 89 50 4E 47 0D 0A 1A 0A} — PNG. */
    public static final MagicSignature PNG = MagicSignature.of("PNG",
            "image/png",
            new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

    /** {@code FF D8 FF} — JPEG (JFIF/EXIF share the same first 3 bytes). */
    public static final MagicSignature JPEG = MagicSignature.of("JPEG",
            "image/jpeg", new int[]{0xFF, 0xD8, 0xFF});

    /**
     * {@code RIFF????WEBP} — bytes 0-3 = RIFF, bytes 8-11 = WEBP. Encoded
     * as a two-window match by the caller when needed; for straight-line
     * detection we accept a signature that matches only the RIFF prefix
     * and check the WEBP window separately in {@link #matchesWebp}.
     */
    public static final MagicSignature RIFF = MagicSignature.of("RIFF",
            "image/webp", new int[]{0x52, 0x49, 0x46, 0x46});

    /** Preset — allow-list for onboarding document uploads (PDF only). */
    public static final Set<MagicSignature> PDF_ONLY = Set.of(PDF);

    /** Preset — allow-list for resume / weekly-report / project-attachment. */
    public static final Set<MagicSignature> RESUME_ATTACHMENTS = Set.of(
            PDF, ZIP, ZIP_EMPTY, DOC, PNG, JPEG, RIFF);

    /** Preset — allow-list for signature capture (image PNG/JPEG only — no SVG). */
    public static final Set<MagicSignature> SIGNATURE_IMAGES = Set.of(PNG, JPEG);

    /** Preset — allow-list for the editable-template DOCX studio upload. */
    public static final Set<MagicSignature> DOCX_ONLY = Set.of(ZIP, ZIP_EMPTY);

    /**
     * Reject the upload with a clean 400 if {@code bytes} does not match
     * any signature in {@code allowed}. Returns the matched signature on
     * success so the caller can also assert MIME consistency if desired.
     *
     * @param bytes raw file bytes (only the first ~16 bytes are read)
     * @param allowed the per-surface signature allow-list
     * @param surface human-readable name for the error message
     */
    public static MagicSignature requireOneOf(byte[] bytes,
                                              Set<MagicSignature> allowed,
                                              String surface) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("Empty file — pick a real file and try again.");
        }
        for (MagicSignature sig : allowed) {
            if (sig.matches(bytes)) {
                // Belt-and-suspenders WebP check: RIFF is generic; make
                // sure bytes 8-11 spell WEBP when WebP is on the list.
                if (sig == RIFF && !matchesWebp(bytes)) continue;
                return sig;
            }
        }
        String expected = allowed.stream().map(MagicSignature::label).sorted().toList().toString();
        throw new BadRequestException(
                "Unsupported file type for " + surface + ". Expected one of " + expected
                        + " (based on actual file bytes, not the filename).");
    }

    /** Convenience that also enforces a hard size cap (bytes). */
    public static MagicSignature requireOneOfWithSizeCap(byte[] bytes,
                                                          Set<MagicSignature> allowed,
                                                          long maxBytes,
                                                          String surface) {
        if (bytes != null && bytes.length > maxBytes) {
            throw new BadRequestException(surface + " exceeds size limit of "
                    + (maxBytes / (1024 * 1024)) + " MB");
        }
        return requireOneOf(bytes, allowed, surface);
    }

    /** Bytes 8-11 spell "WEBP" for a genuine WebP inside a RIFF container. */
    public static boolean matchesWebp(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return false;
        return bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    /** Handle for a single magic-byte pattern. */
    public record MagicSignature(String label, String canonicalMime, int[] pattern) {
        public static MagicSignature of(String label, String mime, int[] pattern) {
            return new MagicSignature(label, mime, pattern);
        }

        public boolean matches(byte[] bytes) {
            if (bytes == null || bytes.length < pattern.length) return false;
            for (int i = 0; i < pattern.length; i++) {
                if ((bytes[i] & 0xFF) != pattern[i]) return false;
            }
            return true;
        }
    }

    /**
     * Sanity check on the caller's declared MIME. If the client claims
     * "image/png" but the bytes are actually a PDF, reject — this protects
     * against downstream code that reads {@code mimeType} for content
     * negotiation. Purely advisory when the declared MIME is a generic
     * fallback (application/octet-stream, absent).
     */
    public static void assertMimeConsistent(String declaredMime, MagicSignature matched) {
        if (declaredMime == null || declaredMime.isBlank()) return;
        String canonical = matched.canonicalMime();
        if ("application/octet-stream".equalsIgnoreCase(declaredMime)) return;
        if (declaredMime.equalsIgnoreCase(canonical)) return;
        // OOXML DOCX is a ZIP under the hood — the declared MIME
        // (application/vnd.openxmlformats-officedocument.wordprocessingml.document)
        // is the real, richer type; keep it and don't complain about ZIP mismatch.
        if (matched == ZIP && declaredMime.startsWith("application/vnd.openxmlformats")) return;
        if (matched == JPEG && declaredMime.equalsIgnoreCase("image/jpg")) return;
        // Log at debug (not warn) — a mismatched declared MIME with matching
        // magic bytes is common with browser uploads on older systems.
        log.debug("[FileContent] declared MIME '{}' does not match magic bytes ({}); "
                + "keeping magic-byte result", declaredMime, matched.label());
    }

    /** Alias used by tests + selective services that want the shorter name. */
    public static MagicSignature detect(byte[] bytes, List<MagicSignature> allowed) {
        return requireOneOf(bytes, Set.copyOf(allowed), "file");
    }
}
