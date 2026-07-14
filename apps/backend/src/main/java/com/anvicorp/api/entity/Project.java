package com.anvicorp.api.entity;

import com.anvicorp.api.enums.ProjectStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Long-running project workspace — supervisor allocates, intern works,
 * supervisor verifies. Separate from {@link WorkAssignment} (the weekly
 * single-task surface) because the Project lifecycle is richer:
 *
 * <ul>
 *   <li>Multiple submissions (history kept in {@link ProjectSubmission})</li>
 *   <li>Checklist for progress tracking ({@link ProjectTask})</li>
 *   <li>Split review outcome — {@code RETURNED} (resubmit) vs
 *       {@code COMPLETED} (terminal lock)</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   create (supervisor)        → NOT_STARTED
 *   /start (intern)            → IN_PROGRESS (stamp started_at)
 *   /progress (intern)         → no status change; updates progress_pct
 *   /submit (intern)           → SUBMITTED (stamp submitted_at; write a
 *                                 ProjectSubmission row)
 *   /return (supervisor)       → RETURNED (stamp reviewed_at; review_notes required)
 *   /submit (intern) again     → SUBMITTED (next ProjectSubmission row)
 *   /complete (supervisor)     → COMPLETED (terminal; stamp completed_at)
 * </pre>
 *
 * <h2>Supervisor scoping</h2>
 * The owning supervisor is on the {@link Engagement#getSupervisor()}, not
 * directly on the project. The service-layer gate resolves the engagement's
 * supervisor at action time; SUPER_ADMIN bypasses.
 */
@Entity
@Table(
        name = "projects",
        indexes = {
                @Index(name = "idx_project_intern_status", columnList = "intern_id, status"),
                @Index(name = "idx_project_engagement", columnList = "engagement_id"),
                @Index(name = "idx_project_status_due", columnList = "status, due_date")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    /** Brief / context — what the project is about. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** What the intern is expected to produce. Separate from description. */
    @Column(columnDefinition = "TEXT")
    private String deliverables;

    /** JSON-encoded {@code List<String>} of resource URLs given at allocation. */
    @Column(name = "resource_links_json", columnDefinition = "TEXT")
    private String resourceLinksJson;

    /**
     * Trainer Phase 2 relaxed engagement_id + intern_id to nullable —
     * lifecycle-tracked projects key off {@link #internLifecycleId}
     * instead. Legacy single-allocation paths still populate both.
     * {@code optional = true} + {@code nullable = true} are declared
     * EXPLICITLY (rather than relying on defaults) so a future regression
     * that adds {@code optional = false} is visible in this entity, and
     * so Hibernate's DDL generator never emits NOT NULL again even on a
     * cold-start environment.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "engagement_id", nullable = true)
    private Engagement engagement;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "intern_id", nullable = true)
    private Candidate intern;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by", nullable = false)
    private User assignedBy;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.NOT_STARTED;

    /**
     * 0–100. Updated by the intern via /progress. Independent from the task
     * checklist count — the UI can drive either explicitly or via tasks-done
     * percentage; the service trusts whatever the intern sends, clamped.
     */
    @Column(name = "progress_pct", nullable = false)
    @Builder.Default
    private Integer progressPct = 0;

    /** Reviewer's most recent feedback note (required on RETURN; optional on COMPLETE). */
    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    /** Most-recent submission timestamp (re-stamped on each resubmit). */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ── Catalog fields (additive — populated by the new Project Catalog +
    //    Assignment module; legacy single-allocation paths leave these
    //    null and continue to read title / description / deliverables /
    //    resourceLinksJson as before).

    /** Catalog name. Falls back to {@link #title} for legacy rows. */
    @Column(name = "name", length = 200)
    private String name;

    /** Hard requirements — text block listing the must-haves. */
    @Column(name = "requirements", columnDefinition = "TEXT")
    private String requirements;

    /** Project objectives / learning goals. */
    @Column(name = "objectives", columnDefinition = "TEXT")
    private String objectives;

    /** Free-text tech stack — comma-separated tokens or short summary. */
    @Column(name = "tech_stack", length = 500)
    private String techStack;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "difficulty", length = 20)
    private com.anvicorp.api.enums.Difficulty difficulty;

    @Column(name = "expected_duration_days")
    private Integer expectedDurationDays;

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    /** TE who created the catalog entry (distinct from {@link #assignedBy}). */
    @Column(name = "created_by_id")
    private java.util.UUID createdById;

    // ── Trainer Phase 0 — doc §7 Project Assignment Form columns ───────────

    /** Denormalised lifecycle id — gives the partial UNIQUE
     *  {@code uq_projects_active_per_slot} something to key on without
     *  joining through {@code intern -> candidate -> intern_lifecycle}.
     *  Populated by Trainer Phase 2's assign-project flow; legacy rows
     *  stay null (tolerated). */
    @Column(name = "intern_lifecycle_id")
    private java.util.UUID internLifecycleId;

    /** Doc §7 "Project 1 or Project 2" — DB-level CHECK enforces 1|2. */
    @Column(name = "project_number")
    private Short projectNumber;

    /** Doc §7 month/year picker — YYYY-MM. Combined with
     *  {@link #projectNumber} + {@link #internLifecycleId} drives the
     *  partial UNIQUE "two monthly projects" rule. */
    @Column(name = "month_year", length = 7)
    private String monthYear;

    /** Manager / ERM user id who authorised a backdated assignment. */
    @Column(name = "backdate_authorized_by_id")
    private java.util.UUID backdateAuthorizedById;

    /** Free-text reason for the backdate (doc uses free-text, not codes). */
    @Column(name = "backdate_reason", columnDefinition = "TEXT")
    private String backdateReason;

    @Column(name = "backdate_authorized_at")
    private Instant backdateAuthorizedAt;

    /** Doc §7 "Evaluation objective mapping" — Recommended, free-text. */
    @Column(name = "learning_objective_label", length = 300)
    private String learningObjectiveLabel;

    /** Optional pointer into {@code i983_training_plans.objectives_json};
     *  only meaningful when the intern's work-auth track is F1_STEM_OPT. */
    @Column(name = "i983_objective_index")
    private Short i983ObjectiveIndex;

    /** Set when this assignment was instantiated from a
     *  {@link ProjectTemplate}. */
    @Column(name = "project_template_id")
    private java.util.UUID projectTemplateId;

    /** Doc §7 "Notify stakeholders" — internal fan-out toggle. Intern
     *  notification always fires; ERM / manager / evaluator only when
     *  this flag is true. */
    @Column(name = "notify_stakeholders_internal", nullable = false,
            columnDefinition = "boolean not null default true")
    @Builder.Default
    private Boolean notifyStakeholdersInternal = Boolean.TRUE;

    // ── KT (Knowledge Transfer) session — Trainer-marked, per monthly project.
    // The Trainer assigns the project, holds a KT call to explain it, and
    // marks it done. Surfaces a "KT" column on the monthly roster + a
    // status row on the intern's My Projects detail.

    /** {@code NOT_DONE} or {@code DONE}. DB default {@code NOT_DONE}. */
    @Column(name = "kt_status", length = 16, nullable = false,
            columnDefinition = "varchar(16) not null default 'NOT_DONE'")
    @Builder.Default
    private String ktStatus = "NOT_DONE";

    /** When the Trainer marked KT done. Null while NOT_DONE. */
    @Column(name = "kt_completed_at")
    private Instant ktCompletedAt;

    /** Optional meeting link (Zoom / Meet / Teams) the Trainer supplied
     *  alongside the KT mark. Phase 0 — manual link only; Zoom
     *  auto-create is a follow-up. */
    @Column(name = "kt_meeting_link", columnDefinition = "TEXT")
    private String ktMeetingLink;

    /** Optional Trainer note about the KT (covered topics, recording link…). */
    @Column(name = "kt_notes", columnDefinition = "TEXT")
    private String ktNotes;

    /** UUID of the Trainer (or SUPER_ADMIN) who marked KT done. */
    @Column(name = "kt_marked_by_id")
    private java.util.UUID ktMarkedById;

    // ── KT Zoom session — scheduling parity with WeeklyMeeting.
    // Trainer schedules a live KT session: backend calls MeetingProvider
    // .createMeeting and stashes the Zoom links here so the intern can
    // join and the trainer can start as host. Mark-KT-done remains a
    // separate, always-available action (it doesn't require a scheduled
    // session — trust the trainer).
    //
    // Hibernate ddl-auto=update adds these columns on next boot; no
    // manual migration. All fields are nullable so existing rows
    // (created before this phase) keep loading.

    /** Provider meeting id (Zoom returns numeric; we store as String
     *  for parity with WeeklyMeeting.zoom_meeting_id). */
    @Column(name = "kt_zoom_meeting_id", length = 64)
    private String ktZoomMeetingId;

    /** Intern-safe join URL (the public attendee link). */
    @Column(name = "kt_zoom_join_url", columnDefinition = "TEXT")
    private String ktZoomJoinUrl;

    /** Host-only start URL. NEVER exposed to interns. zak token in this
     *  URL expires ~2h after create — the host-start endpoint re-fetches
     *  on demand so the trainer's "Start as Host" button is always live. */
    @Column(name = "kt_zoom_start_url", columnDefinition = "TEXT")
    private String ktZoomStartUrl;

    /** When the KT session is scheduled to occur. */
    @Column(name = "kt_scheduled_for")
    private Instant ktScheduledFor;

    /** Session length in minutes. Defaults to 30 when not specified. */
    @Column(name = "kt_duration_minutes")
    private Integer ktDurationMinutes;

    /** IANA timezone for the schedule (e.g. {@code Asia/Kolkata}).
     *  Defaults to UTC if missing. */
    @Column(name = "kt_timezone", length = 50)
    private String ktTimezone;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
