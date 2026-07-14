/**
 * Anvi Careers — Trainer surface (placeholder package).
 *
 * <p>Phase 0 scaffolds doc-required schema + reserves this package for
 * Phase 1-4 services + controllers. No classes live here yet; this
 * {@code package-info.java} exists to record the doc-spec'd permission
 * boundaries + cross-role architecture invariants for any developer who
 * later opens the package.</p>
 *
 * <h2>Scope filter</h2>
 * Every Trainer-facing query MUST be filtered by
 * {@code intern_lifecycles.trainer_id = caller.id} (or
 * {@code SUPER_ADMIN} bypass). This is the canonical scope assigned by
 * ERM Phase 4's atomic {@code /assign-reporting} flow. The Trainer
 * never queries across the full intern population.
 *
 * <h2>Permission boundaries (Trainer doc §3 + Acceptance Criteria)</h2>
 * Trainer DTOs MUST NOT include any of:
 * <ul>
 *   <li>I-9 / W-4 / ACH / SSN / DOB / passport / A-number / I-94</li>
 *   <li>Immigration / E-Verify / work-auth fields</li>
 *   <li>Offer letter / compensation / employee ID mutation surface</li>
 *   <li>Payroll fields</li>
 *   <li>Final evaluation edits after PUBLISHED</li>
 * </ul>
 * These constraints are enforced at the DTO serialisation layer (Phase
 * 1+); the Trainer service layer must never expose these fields even
 * by accident.
 *
 * <h2>Lifecycle invariants</h2>
 * Trainer actions NEVER mutate {@code users.lifecycle_status}. The
 * Trainer operates strictly within the {@code ACTIVE_INTERN} window;
 * status transitions belong to ERM (Phases 0-7).
 *
 * <h2>Project status enum</h2>
 * {@code Project.status} stays canonical:
 * {@code ASSIGNED → IN_PROGRESS → SUBMITTED → REVISION_REQUESTED →
 * COMPLETED | CANCELLED}. No parallel Trainer-specific status enum.
 * {@link com.anvicorp.api.enums.ProjectReviewDecision} adds the doc
 * §9 four feedback decisions which map onto these status transitions
 * (Phase 3 wires the mapping).
 *
 * <h2>Audit log</h2>
 * Doc §11 requires "every file upload, meeting status, feedback
 * decision, status change is audit logged". This is satisfied by the
 * combination of:
 * <ul>
 *   <li>{@link com.anvicorp.api.entity.ProjectAssignmentEventLog} —
 *       per-project event history (parallel to OfferEventLog,
 *       InterviewEventLog, OnboardingReviewLog).</li>
 *   <li>The global {@link com.anvicorp.api.entity.AuditLog} — already
 *       written from every action endpoint.</li>
 * </ul>
 *
 * <h2>Notifications</h2>
 * Doc §10 recipient matrix is realised via the existing
 * {@link com.anvicorp.api.notification.UserNotificationDispatcher} +
 * {@link com.anvicorp.api.erm.CommunicationTemplateService}. Trainer
 * Phase 0 seeds 7 templates (PROJECT_ASSIGNED, WEEKLY_MEETING_*,
 * SUBMISSION_UPLOADED, FEEDBACK_PUBLISHED, PROJECT_OVERDUE). No
 * parallel notification system.
 *
 * <h2>Files / Templates</h2>
 * {@link com.anvicorp.api.entity.ProjectTemplate} IS the doc's
 * TrainingMaterial. Shared library — not per-trainer (doc §3 Reporting
 * row implies cross-trainer collaboration). The retired WeeklyMaterial
 * + MaterialAcknowledgement concept (intern-side ack ledger) is NOT in
 * the Trainer doc and was removed in Phase 0.
 *
 * <h2>Cross-role read sharing</h2>
 * Same {@code ExceptionRecord} rows feed the Trainer / Evaluator /
 * Manager dashboards via role-scoped filtering on the existing
 * {@code /api/v1/erm/escalations} endpoint. Intern actions (submit
 * timesheet, attend meeting) naturally close their own exceptions on
 * the next ERM Phase 6 scan tick.
 */
package com.anvicorp.api.trainer;
