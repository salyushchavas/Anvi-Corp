package com.anvicorp.api.entity;

import com.anvicorp.api.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    /**
     * Bcrypt hash of the user's password. NULLABLE — a row with a null
     * hash is an unactivated staff account created by SUPER_ADMIN via
     * the activation-link flow ({@code POST /api/v1/admin/users} +
     * {@code POST /auth/activate}). Login refuses these rows with a
     * "Account not activated" error until the user redeems their
     * activation link and sets a password.
     */
    @Column
    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    private String phoneNumber;

    @ElementCollection(targetClass = UserRole.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    @Builder.Default
    private Set<UserRole> roles = new HashSet<>();

    /**
     * Active accounts can log in; deactivated accounts are rejected at the
     * auth layer. Defaults to TRUE for new users. The Postgres column has
     * {@code NOT NULL DEFAULT TRUE} so existing rows backfill cleanly when
     * the column is first added (see {@code SchemaFixupRunner}).
     */
    @Column(nullable = false, columnDefinition = "boolean not null default true")
    @Builder.Default
    private Boolean active = true;

    /**
     * Forces the user to change their password on next login before any
     * other API call succeeds. Set TRUE when a SUPER_ADMIN creates a
     * staff user with a temp password; cleared atomically by
     * {@link com.anvicorp.api.service.UserProfileService#changePassword}
     * when the user supplies a fresh password. The server-side gate is
     * enforced by {@code ForcePasswordChangeFilter}; the frontend mirrors
     * the gate via {@code AuthContext} so the user is redirected to the
     * force-change-password screen immediately on login.
     */
    @Column(name = "must_change_password", nullable = false,
            columnDefinition = "boolean not null default false")
    @Builder.Default
    private Boolean mustChangePassword = false;

    /**
     * Email-verification gate. New CANDIDATE registrations start FALSE; verified
     * via the 6-digit code in {@code emailVerificationCode}. Existing rows are
     * backfilled to TRUE on first boot (see {@code SchemaFixupRunner} —
     * column added with {@code DEFAULT TRUE}). Openings + apply require this
     * to be TRUE for CANDIDATE callers (phase 1.3 gate).
     */
    @Column(name = "email_verified", nullable = false,
            columnDefinition = "boolean not null default false")
    @Builder.Default
    private Boolean emailVerified = false;

    /** 6-digit code; nullable after verification succeeds. */
    @Column(name = "email_verification_code")
    private String emailVerificationCode;

    @Column(name = "email_verification_sent_at")
    private Instant emailVerificationSentAt;

    @Column(name = "email_verification_expires_at")
    private Instant emailVerificationExpiresAt;

    /**
     * Anvi Corp Applicant ID, format {@code SKZ-INT-YYYY-NNNNNN} where NNNNNN
     * is zero-padded from a Postgres sequence ({@code skyzen_applicant_seq}).
     * Issued on first email-verification for CANDIDATE accounts; staff don't
     * receive one. Unique across the entire users table.
     */
    @Column(name = "applicant_id", unique = true, length = 32)
    private String applicantId;

    @Column(name = "applicant_id_created_at")
    private Instant applicantIdCreatedAt;

    /**
     * Anvi Corp Employee ID, minted at the OFFER_SIGNED → EMPLOYEE_ID_CREATED
     * transition (Phase 3). Nullable until that transition fires.
     */
    @Column(name = "employee_id", length = 40)
    private String employeeId;

    /**
     * Position on the applicant-to-intern lifecycle funnel. Single source of
     * truth for the Phase-1 dashboard mode engine. Existing rows are stamped
     * REGISTERED via {@code SchemaFixupRunner}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 40,
            columnDefinition = "varchar(40) not null default 'REGISTERED'")
    @Builder.Default
    private com.anvicorp.api.enums.InternLifecycleStatus lifecycleStatus =
            com.anvicorp.api.enums.InternLifecycleStatus.REGISTERED;

    /**
     * JSON array of nav-item keys this candidate/intern has already opened —
     * used to suppress the "new" badge after the first visit. Additive column
     * (ddl-auto handles it); legacy rows surface as null which the nav
     * service treats as an empty list. Stored as TEXT to keep the entity
     * Hibernate-portable; the service parses with ObjectMapper.
     */
    @Column(name = "seen_nav_items", columnDefinition = "TEXT")
    private String seenNavItemsJson;

    /**
     * Intern's self-provided GitHub username. Used by the project-assignment
     * module: the TE invites this GitHub user as a collaborator on the
     * project repository out-of-band (no GitHub API call from the platform).
     * Stored as a plain string; no token / OAuth state ever stored.
     */
    @Column(name = "github_username", length = 100)
    private String githubUsername;

    /**
     * Zoom email for ERM members who host interviews. Used as the host
     * user id when ZoomService creates a meeting. Null for non-ERM users
     * and for ERM members who haven't been provisioned in Zoom yet.
     */
    @Column(name = "zoom_email", length = 100)
    private String zoomEmail;

    /**
     * Email notification opt-outs. Both default TRUE (opt-in) — transactional
     * emails ignore these flags entirely (per CAN-SPAM + sensibility). Set to
     * FALSE in the user's preferences page to silence reminders / engagement
     * updates respectively.
     */
    @Column(name = "prefs_reminders", nullable = false,
            columnDefinition = "boolean not null default true")
    @Builder.Default
    private Boolean prefsReminders = true;

    @Column(name = "prefs_engagement_updates", nullable = false,
            columnDefinition = "boolean not null default true")
    @Builder.Default
    private Boolean prefsEngagementUpdates = true;

    // ── Trainer Phase 4 — trainer-role preferences ─────────────────────────

    /** Default recurrence on the Schedule Meeting modal — NONE | WEEKLY. */
    @Column(name = "prefs_trainer_default_recurrence", length = 16)
    private String prefsTrainerDefaultRecurrence;

    /** Default duration on the Schedule Meeting modal (minutes). */
    @Column(name = "prefs_trainer_default_duration")
    private Short prefsTrainerDefaultDuration;

    /** Default sort on the Pending Reviews queue — OLDEST | NEWEST | INTERN. */
    @Column(name = "prefs_trainer_review_priority", length = 16)
    private String prefsTrainerReviewPriority;

    /** Default state of the "notify stakeholders" checkbox on the
     *  Project Assignment wizard. */
    @Column(name = "prefs_trainer_notify_stakeholders")
    private Boolean prefsTrainerNotifyStakeholders;

    /** Email frequency for in-app reminders — DAILY | WEEKLY | NEVER. */
    @Column(name = "prefs_trainer_email_frequency", length = 16)
    private String prefsTrainerEmailFrequency;

    /** Notify me when an intern submits a new project for review. */
    @Column(name = "prefs_trainer_notify_submissions")
    private Boolean prefsTrainerNotifySubmissions;

    /** Notify me when one of my escalations is reviewed by ERM. */
    @Column(name = "prefs_trainer_notify_escalation_resolved")
    private Boolean prefsTrainerNotifyEscalationResolved;

    // ── Evaluator Phase 4 — per-Evaluator preferences ───────────────────────

    /** Default duration on the Schedule Session modal (minutes). */
    @Column(name = "prefs_evaluator_default_duration")
    private Short prefsEvaluatorDefaultDuration;

    /** DAILY | WEEKLY | NEVER — email reminder cadence for unacked evaluations. */
    @Column(name = "prefs_evaluator_reminder_frequency", length = 16)
    private String prefsEvaluatorReminderFrequency;

    /** Notify me when intern acknowledges. */
    @Column(name = "prefs_evaluator_notify_acknowledged")
    private Boolean prefsEvaluatorNotifyAcknowledged;

    /** Notify me when an I-983 DSO submission window approaches expiry. */
    @Column(name = "prefs_evaluator_notify_dso_window")
    private Boolean prefsEvaluatorNotifyDsoWindow;

    /** When the user accepted ToS + privacy. Null for legacy pre-checkbox accounts. */
    @Column(name = "tos_accepted_at")
    private Instant tosAcceptedAt;

    /** Version of the ToS the user accepted (e.g. "2026-05-27"). */
    @Column(name = "tos_version", length = 32)
    private String tosVersion;

    // ── Mail bridge (Phase 1 — additive, dormant) ──────────────────────────
    // These four columns are written/read by NOTHING yet. They lay the
    // groundwork for the intern → employee mail handover. Behaviour is
    // unchanged until later phases wire the dispatcher. See
    // MAIL_BRIDGE_SURVEY.md and com.anvicorp.api.enums.MailHandoverState.

    /**
     * One-way link to the user's company mailbox in {@code mail_accounts}.
     * Null while the user is still on personal Gmail (the default). No JPA
     * relation — kept as a bare UUID column so a {@code DELETE} on
     * {@code mail_accounts} doesn't cascade through JPA and no foreign-key
     * constraint surprises a future mail-side cleanup.
     */
    @Column(name = "mail_account_id")
    private UUID mailAccountId;

    /**
     * Where the user sits in the intern → employee mailbox handover. Always
     * starts {@code PERSONAL}; ERM transitions to {@code PENDING_ACTIVATION}
     * on mailbox assignment, and the user moves to {@code ACTIVATED} once
     * they sign in to the company mailbox. See {@link com.anvicorp.api.enums.MailHandoverState}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mail_handover_state", nullable = false, length = 24,
            columnDefinition = "varchar(24) not null default 'PERSONAL'")
    @Builder.Default
    private com.anvicorp.api.enums.MailHandoverState mailHandoverState =
            com.anvicorp.api.enums.MailHandoverState.PERSONAL;

    /** Stamp when {@code mailHandoverState} flipped to {@code ACTIVATED}. */
    @Column(name = "mail_handover_at")
    private Instant mailHandoverAt;

    /**
     * The user's original personal Gmail, archived once the login
     * {@code email} is swapped to their company address. Null while
     * {@code email} still holds the personal Gmail (i.e. pre-Phase-2).
     */
    @Column(name = "personal_email", length = 255)
    private String personalEmail;

    /**
     * Timestamp of the last time the user opened / dismissed the to-do
     * pop-up. Drives {@code TodoPanelResponse.hasNewSinceLastSeen} — a
     * pending action whose {@code firstAppearedAt > todoPanelLastSeenAt}
     * counts as "new since last check", which is the once-per-session
     * gate for the login pop-up. Null on legacy rows → treated as "any
     * pending action is new" until the user opens the pop-up once. See
     * {@code TodoController.markSeen}.
     */
    @Column(name = "todo_panel_last_seen_at")
    private Instant todoPanelLastSeenAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
