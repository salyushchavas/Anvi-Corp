package com.anvicorp.api.exception;

/**
 * Thrown when a CANDIDATE caller hits a post-offer-only endpoint (I-9 create
 * or Section 1 submit) without an ACCEPTED offer (or with an engagement in
 * BLOCKED_NO_AUTHORIZATION). Mapped by {@code GlobalExceptionHandler} to 403
 * with {@code code="OFFER_REQUIRED"} so the frontend can render a clean
 * "available after your offer is accepted" state.
 *
 * Backs PED rule 4 / GAP_REPORT A1: I-9 and E-Verify are POST-OFFER ONLY —
 * E-Verify is explicitly "not a prescreening tool".
 */
public class OfferRequiredException extends RuntimeException {
    public OfferRequiredException(String message) {
        super(message);
    }
}
