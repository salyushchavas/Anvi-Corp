package com.anvicorp.api.security;

import com.anvicorp.api.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Security Wave 2 (M) — DOCX zip-bomb / archive-abuse guard.
 *
 * <p>DOCX is an OOXML zip container. Without inspection an attacker can
 * ship a 25 MB {@code .docx} that inflates to hundreds of GB (nested
 * or highly-repetitive streams) and either OOM the docx-preview library
 * on the frontend OR fill the templates volume on the backend at extract
 * time.</p>
 *
 * <p>This validator streams the zip via {@link ZipInputStream} — never
 * writes to disk — and enforces three caps:
 * <ul>
 *   <li>Max ENTRY COUNT (default 500) — legitimate DOCX rarely exceeds
 *       ~40 entries; 500 is comfortably above OOXML's real-world upper.</li>
 *   <li>Max total UNCOMPRESSED size (default 200 MB) — hard ceiling.</li>
 *   <li>Max per-entry COMPRESSION RATIO (default 100) — a single stream
 *       compressed more than 100× is the classic zip-bomb signature.</li>
 * </ul>
 * Any breach → {@link BadRequestException} with a clean message; the
 * upload row is never persisted.</p>
 */
@Slf4j
public final class DocxSafetyValidator {

    private static final int DEFAULT_MAX_ENTRIES = 500;
    private static final long DEFAULT_MAX_TOTAL_UNCOMPRESSED = 200L * 1024 * 1024;
    private static final int DEFAULT_MAX_RATIO = 100;

    private DocxSafetyValidator() {}

    /**
     * Streams the zip and enforces the default caps. Called from every
     * DOCX-accepting service AFTER the magic-byte check succeeds.
     */
    public static void assertSafe(byte[] bytes, String surface) {
        assertSafe(bytes, surface,
                DEFAULT_MAX_ENTRIES, DEFAULT_MAX_TOTAL_UNCOMPRESSED, DEFAULT_MAX_RATIO);
    }

    public static void assertSafe(byte[] bytes,
                                    String surface,
                                    int maxEntries,
                                    long maxTotalUncompressed,
                                    int maxRatio) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException(surface + " is empty");
        }
        int entries = 0;
        long totalUncompressed = 0L;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            byte[] buf = new byte[8 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                entries++;
                if (entries > maxEntries) {
                    throw new BadRequestException(surface + " has too many archive entries "
                            + "(> " + maxEntries + ") — refusing as possible zip-bomb.");
                }
                // Walk the stream to get the true uncompressed size (some
                // producers set getSize()=-1). Stream cap enforced inline.
                long entryUncompressed = 0L;
                int n;
                while ((n = zin.read(buf)) != -1) {
                    entryUncompressed += n;
                    totalUncompressed += n;
                    if (totalUncompressed > maxTotalUncompressed) {
                        throw new BadRequestException(surface
                                + " exceeds safe uncompressed size (> "
                                + (maxTotalUncompressed / (1024 * 1024))
                                + " MB) — refusing as possible zip-bomb.");
                    }
                }
                long entryCompressed = e.getCompressedSize();
                if (entryCompressed > 0) {
                    long ratio = entryUncompressed / Math.max(1, entryCompressed);
                    if (ratio > maxRatio) {
                        throw new BadRequestException(surface + " contains an archive entry "
                                + "with compression ratio " + ratio + ":1 (> " + maxRatio
                                + ":1) — refusing as possible zip-bomb.");
                    }
                }
                zin.closeEntry();
            }
        } catch (BadRequestException re) {
            throw re;
        } catch (Exception ex) {
            log.debug("[DocxSafety] archive walk failed for {}: {}", surface, ex.getMessage());
            throw new BadRequestException(surface + " is not a valid archive: "
                    + ex.getMessage());
        }
        log.debug("[DocxSafety] {} passed: entries={} totalUncompressed={} bytes",
                surface, entries, totalUncompressed);
    }
}
