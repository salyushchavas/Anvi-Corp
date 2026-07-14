package com.anvicorp.api.exception;

/**
 * Thrown when a CANDIDATE caller hits an I-983 endpoint without a STEM_OPT
 * track (resolved from {@code Engagement.track}, falling back to
 * {@code Candidate.expectedTrack}). Mapped by {@code GlobalExceptionHandler}
 * to 403 with {@code code="STEM_OPT_REQUIRED"} so the frontend renders the
 * "training plan not required" state instead of an error.
 *
 * Backs PED §8 (I-983 is STEM-OPT-only) / GAP_REPORT A5. Defense in depth —
 * the frontend already hides the tile via {@code user.expectedTrack}, but
 * the server is now also authoritative.
 */
public class StemOptRequiredException extends RuntimeException {
    public StemOptRequiredException(String message) {
        super(message);
    }
}
