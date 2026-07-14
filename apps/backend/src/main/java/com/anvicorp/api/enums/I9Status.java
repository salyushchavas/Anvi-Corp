package com.anvicorp.api.enums;

/**
 * I-9 form workflow status.
 *
 *   NOT_STARTED         No data yet.
 *   SECTION_2_PENDING   (Phase 3 step 5) Section 1 signed by the candidate;
 *                       Section 2 due within 3 business days of start.
 *   SECTION_1_COMPLETE  Legacy / deprecated value (kept so pre-Phase-3 rows
 *                       still deserialize). Semantically equivalent to
 *                       SECTION_2_PENDING — treated the same by all readers.
 *   COMPLETED           Both sections signed.
 *   REOPENED            Admin reopened a previously COMPLETED form for
 *                       correction; goes back through Section 1 → Section 2.
 */
public enum I9Status {
    NOT_STARTED,
    SECTION_2_PENDING,
    SECTION_1_COMPLETE,
    COMPLETED,
    REOPENED
}
