package com.anvicorp.api.enums;

/**
 * Phase 1.4 — candidate's expected work-authorization track captured at
 * registration / on the profile page via a NEUTRAL self-attestation.
 *
 * IMPORTANT compliance rule: this value is the candidate's own statement of
 * what they expect to use. NO documents are collected at this stage; I-9 +
 * E-Verify happen only post-offer (Phase 3). Do NOT use this value to gate
 * application access or to make pre-offer eligibility decisions — it drives
 * downstream compliance routing only.
 */
public enum WorkAuthTrack {
    CITIZEN,
    CPT,
    OPT,
    STEM_OPT,
    OTHER
}
