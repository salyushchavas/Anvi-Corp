package com.anvicorp.api.erm;

/**
 * ERM decision taxonomy. Values are grouped by {@link Category}; each
 * carries a human label and a {@code requiresFreeText} flag (true for the
 * {@code *_OTHER} catch-all values where ERM must supply free-form context).
 *
 * <p>Phase 0 (this file) only compiles the enum. Later phases inject
 * {@code reasonCode} as a required body field on the relevant action
 * endpoints (shortlist/reject/hold, interview decisions, offer voids,
 * document rejections, exit initiation).</p>
 */
public enum ReasonCode {

    // ── Application decisions ──────────────────────────────────────────────
    REJECT_SKILL_MISMATCH(Category.APPLICATION_REJECT, "Skill mismatch", false),
    REJECT_NO_WORK_AUTH(Category.APPLICATION_REJECT, "No work authorization", false),
    REJECT_OVERQUALIFIED(Category.APPLICATION_REJECT, "Overqualified", false),
    REJECT_INCOMPLETE_APPLICATION(Category.APPLICATION_REJECT, "Incomplete application", false),
    REJECT_DUPLICATE(Category.APPLICATION_REJECT, "Duplicate application", false),
    REJECT_OTHER(Category.APPLICATION_REJECT, "Other (specify)", true),

    HOLD_PENDING_INFO(Category.APPLICATION_HOLD, "Pending additional info", false),
    HOLD_AWAITING_TEAM_FIT(Category.APPLICATION_HOLD, "Awaiting team fit", false),
    HOLD_FUTURE_CYCLE(Category.APPLICATION_HOLD, "Future cycle", false),
    HOLD_OTHER(Category.APPLICATION_HOLD, "Other (specify)", true),

    REQUEST_INFO_RESUME(Category.APPLICATION_REQUEST_INFO, "Updated resume", false),
    REQUEST_INFO_WORK_AUTH(Category.APPLICATION_REQUEST_INFO, "Work authorization details", false),
    REQUEST_INFO_EDUCATION(Category.APPLICATION_REQUEST_INFO, "Education verification", false),
    REQUEST_INFO_OTHER(Category.APPLICATION_REQUEST_INFO, "Other (specify)", true),

    // ── Interview decisions ────────────────────────────────────────────────
    INTERVIEW_SELECT_STRONG(Category.INTERVIEW_DECISION, "Strong select", false),
    INTERVIEW_SELECT_STANDARD(Category.INTERVIEW_DECISION, "Standard select", false),
    INTERVIEW_HOLD_FOLLOW_UP(Category.INTERVIEW_DECISION, "Hold for follow-up", false),
    INTERVIEW_HOLD_TEAM_FIT(Category.INTERVIEW_DECISION, "Hold pending team fit", false),
    INTERVIEW_REJECT_SKILL(Category.INTERVIEW_DECISION, "Reject — technical skill", false),
    INTERVIEW_REJECT_COMMUNICATION(Category.INTERVIEW_DECISION, "Reject — communication", false),
    INTERVIEW_REJECT_OTHER(Category.INTERVIEW_DECISION, "Reject — other (specify)", true),

    // ── Interview reschedule (ERM Phase 3) ─────────────────────────────────
    INTERVIEW_RESCHEDULE_APPLICANT_REQUEST(Category.INTERVIEW_RESCHEDULE,
            "Applicant requested reschedule", false),
    INTERVIEW_RESCHEDULE_INTERVIEWER_CONFLICT(Category.INTERVIEW_RESCHEDULE,
            "Interviewer conflict", false),
    INTERVIEW_RESCHEDULE_TECHNICAL_ISSUE(Category.INTERVIEW_RESCHEDULE,
            "Technical issue / no-go", false),
    INTERVIEW_RESCHEDULE_OTHER(Category.INTERVIEW_RESCHEDULE,
            "Other (specify)", true),

    // ── Interview cancel (ERM Phase 3) ─────────────────────────────────────
    INTERVIEW_CANCEL_APPLICANT_WITHDREW(Category.INTERVIEW_CANCEL,
            "Applicant withdrew", false),
    INTERVIEW_CANCEL_BUSINESS_DECISION(Category.INTERVIEW_CANCEL,
            "Business decision", false),
    INTERVIEW_CANCEL_NO_SHOW(Category.INTERVIEW_CANCEL,
            "No-show", false),
    INTERVIEW_CANCEL_OTHER(Category.INTERVIEW_CANCEL,
            "Other (specify)", true),

    // ── Offer voids ────────────────────────────────────────────────────────
    OFFER_VOID_DECLINED_VERBALLY(Category.OFFER_VOID, "Candidate declined verbally", false),
    OFFER_VOID_BUSINESS_CHANGE(Category.OFFER_VOID, "Business change", false),
    OFFER_VOID_TERMS_REVISED(Category.OFFER_VOID, "Terms revised — re-issuing", false),
    OFFER_VOID_OTHER(Category.OFFER_VOID, "Other (specify)", true),

    // ── Offer resend (ERM Phase 4) ─────────────────────────────────────────
    OFFER_RESEND_NO_RESPONSE(Category.OFFER_RESEND, "No response yet", false),
    OFFER_RESEND_TECHNICAL_ISSUE(Category.OFFER_RESEND, "Technical issue — link not delivered", false),
    OFFER_RESEND_OTHER(Category.OFFER_RESEND, "Other (specify)", true),

    // ── Document rejections ────────────────────────────────────────────────
    DOC_REJECT_INCOMPLETE(Category.DOCUMENT_REJECT, "Incomplete document", false),
    DOC_REJECT_WRONG_FILE(Category.DOCUMENT_REJECT, "Wrong file uploaded", false),
    DOC_REJECT_ILLEGIBLE(Category.DOCUMENT_REJECT, "Illegible / unreadable", false),
    DOC_REJECT_EXPIRED(Category.DOCUMENT_REJECT, "Expired document", false),
    DOC_REJECT_INFO_MISMATCH(Category.DOCUMENT_REJECT, "Information mismatch", false),
    DOC_REJECT_OTHER(Category.DOCUMENT_REJECT, "Other (specify)", true),

    // ── Exit ───────────────────────────────────────────────────────────────
    EXIT_COMPLETED_TERM(Category.EXIT, "Completed full term", false),
    EXIT_RESIGNED_NEW_OPPORTUNITY(Category.EXIT, "Resigned — new opportunity", false),
    EXIT_RESIGNED_PERSONAL(Category.EXIT, "Resigned — personal reasons", false),
    EXIT_TERMINATED_PERFORMANCE(Category.EXIT, "Terminated — performance", false),
    EXIT_TERMINATED_CONDUCT(Category.EXIT, "Terminated — conduct", false),
    EXIT_TERMINATED_BUSINESS(Category.EXIT, "Terminated — business reasons", false),
    EXIT_EXTENDED_NEW_ROLE(Category.EXIT, "Extended — new role being created", false),
    EXIT_OTHER(Category.EXIT, "Other (specify)", true),

    // ── Exception resolution (ERM Phase 6) ─────────────────────────────────
    EXCEPTION_RESOLVED_FIXED(Category.EXCEPTION_RESOLVE,
            "Underlying issue fixed", false),
    EXCEPTION_RESOLVED_BY_NATURAL_FLOW(Category.EXCEPTION_RESOLVE,
            "Resolved by natural workflow", false),
    EXCEPTION_RESOLVED_OVERRIDE(Category.EXCEPTION_RESOLVE,
            "Manager override (specify)", true),

    // ── Exception dismissal (ERM Phase 6) ──────────────────────────────────
    EXCEPTION_DISMISSED_FALSE_POSITIVE(Category.EXCEPTION_DISMISS,
            "False positive", false),
    EXCEPTION_DISMISSED_OUT_OF_SCOPE(Category.EXCEPTION_DISMISS,
            "Out of ERM scope", false),
    EXCEPTION_DISMISSED_DUPLICATE(Category.EXCEPTION_DISMISS,
            "Duplicate of existing exception", false),

    // ── Exit manager override (ERM Phase 7) ────────────────────────────────
    EXIT_OVERRIDE_BUSINESS_NECESSITY(Category.EXIT_OVERRIDE,
            "Business necessity", true),
    EXIT_OVERRIDE_COMPLIANCE_REQUIRED(Category.EXIT_OVERRIDE,
            "Compliance requirement", true),
    EXIT_OVERRIDE_OTHER(Category.EXIT_OVERRIDE,
            "Other (specify)", true),

    // ── Asset status (ERM Phase 7) ─────────────────────────────────────────
    ASSET_RETURNED(Category.ASSET, "Returned", false),
    ASSET_NOT_APPLICABLE(Category.ASSET, "Not applicable", false),
    ASSET_PENDING(Category.ASSET, "Pending return", false);

    public enum Category {
        APPLICATION_REJECT,
        APPLICATION_HOLD,
        APPLICATION_REQUEST_INFO,
        INTERVIEW_DECISION,
        INTERVIEW_RESCHEDULE,
        INTERVIEW_CANCEL,
        OFFER_VOID,
        OFFER_RESEND,
        DOCUMENT_REJECT,
        EXIT,
        EXCEPTION_RESOLVE,
        EXCEPTION_DISMISS,
        EXIT_OVERRIDE,
        ASSET
    }

    private final Category category;
    private final String humanLabel;
    private final boolean requiresFreeText;

    ReasonCode(Category category, String humanLabel, boolean requiresFreeText) {
        this.category = category;
        this.humanLabel = humanLabel;
        this.requiresFreeText = requiresFreeText;
    }

    public Category category() { return category; }
    public String humanLabel() { return humanLabel; }
    public boolean requiresFreeText() { return requiresFreeText; }
}
