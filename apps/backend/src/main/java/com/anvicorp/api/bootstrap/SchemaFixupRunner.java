package com.anvicorp.api.bootstrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.erm.documents.SkyzenDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs before every other runner and drops Hibernate's auto-generated check
 * constraint on {@code applications.status}. ddl-auto=update never updates
 * existing check constraints, so once a new enum value is added the constraint
 * goes stale and writes to the new value crash. Application-layer enforcement
 * (@Enumerated(EnumType.STRING) + the Java enum) is the real source of truth.
 *
 * Idempotent: {@code DROP CONSTRAINT IF EXISTS} is a no-op after the first
 * successful drop. Never throws — a failure here must not crash startup.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class SchemaFixupRunner implements CommandLineRunner {

    /**
     * GitHub repo URL pattern shared with {@code ProjectService.GITHUB_REPO_URL}.
     * Matches {@code https://github.com/{owner}/{name}} with optional
     * {@code .git} suffix and trailing slash. Compiled once at class load.
     */
    private static final Pattern GITHUB_REPO_URL =
            Pattern.compile("^https?://github\\.com/([\\w.-]+)/([\\w.-]+?)(\\.git)?/?$");

    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${app.default-trainer-email:}")
    private String defaultTrainerEmail;

    @org.springframework.beans.factory.annotation.Value("${app.default-evaluator-email:}")
    private String defaultEvaluatorEmail;

    @Override
    public void run(String... args) {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE applications DROP CONSTRAINT IF EXISTS applications_status_check");
            log.info("Dropped stale applications_status_check (if present).");
        } catch (Exception e) {
            log.warn("applications_status_check drop failed (non-fatal): {}", e.getMessage(), e);
        }

        // Password-reset tokens now store 6-digit codes (same shape as the
        // registration verification code) rather than UUIDs. Different users
        // can legitimately hold the same 6-digit value, so the previous
        // UNIQUE on `token` must be dropped or concurrent requests would
        // 23505 on insert. Idempotent: DROP ... IF EXISTS is a no-op after
        // the first successful drop. Application-layer lookup is scoped to
        // (user_id, token) via PasswordResetTokenRepository.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE password_reset_tokens "
                            + "DROP CONSTRAINT IF EXISTS password_reset_tokens_token_key");
            jdbcTemplate.execute("DROP INDEX IF EXISTS idx_prt_token");
            log.info("Dropped stale password_reset_tokens UNIQUE(token) (if present).");
        } catch (Exception e) {
            log.warn("password_reset_tokens UNIQUE(token) drop failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // Phase 3 step 5: SECTION_2_PENDING was added to I9Status. ddl-auto
        // never updates the existing CHECK constraint, so we drop it here
        // before any row tries to write the new value. Application-layer
        // @Enumerated(EnumType.STRING) remains the real source of truth.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms DROP CONSTRAINT IF EXISTS i9_forms_status_check");
            log.info("Dropped stale i9_forms_status_check (if present).");
        } catch (Exception e) {
            log.warn("i9_forms_status_check drop failed (non-fatal): {}", e.getMessage(), e);
        }

        // ProjectStatus grew several values after its initial CHECK was
        // emitted (TECH_APPROVED, PENDING_VIVA, and the terminal COMPLETED).
        // A bare DROP CONSTRAINT IF EXISTS was in place here originally,
        // but it kept getting undone (Hibernate 6 re-emits @Enumerated CHECKs
        // during schema-update on some deploys, and a silent DROP failure
        // just logs WARN and moves on) — TestInternLifecycleSeeder tripped
        // 23514 on the first COMPLETED insert. Mirror the timesheets /
        // weekly_reports rebuild: DROP + ADD with the full current enum,
        // verify via pg_constraint. Java @Enumerated(EnumType.STRING) on
        // ProjectStatus stays the real source of truth; the CHECK is
        // belt-and-suspenders.
        rebuildProjectsStatusCheck();

        // Same risk class on the newer project_assignments table.
        // ProjectAssignmentStatus has the same 7 values and the trainer /
        // seeder flows write SUBMITTED / COMPLETED / TECH_APPROVED into it.
        rebuildProjectAssignmentsStatusCheck();

        // REPORTING_MANAGER was added to UserRole. The role values live on
        // the user_roles join table; same stale-CHECK reasoning as above.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS user_roles_role_check");
            log.info("Dropped stale user_roles_role_check (if present).");
        } catch (Exception e) {
            log.warn("user_roles_role_check drop failed (non-fatal): {}", e.getMessage(), e);
        }

        // Phase B2 — VERIFIED was added to TimesheetStatus when the
        // ERM-verify stage shipped. The original timesheets_status_check
        // pre-dated VERIFIED so the ERM verifyBatch call fails with 23514
        // the moment it tries to write VERIFIED. Drop + recreate with the
        // full current enum; verify the rebuild took (engagement_id-relax
        // lesson — silent DROP/ADD failures hide for weeks). App-layer
        // @Enumerated(EnumType.STRING) on TimesheetStatus stays the real
        // source of truth; the DB CHECK is belt-and-suspenders.
        rebuildTimesheetsStatusCheck();

        // Same story for weekly_reports — VERIFIED was added in the
        // two-stage review rollout but the legacy CHECK still lists the
        // pre-VERIFIED set, so every ERM verify write 23514s. Mirror the
        // timesheets pattern verbatim.
        rebuildWeeklyReportsStatusCheck();

        // Manager approval gate for evaluation recordings — new column,
        // Hibernate emits a CHECK from the raw String field ONLY when it
        // sees an existing @Enumerated column, so we belt-and-suspenders
        // the check ourselves. Any expansion of the values list needs
        // this rebuild to keep writes flowing.
        rebuildRecordingApprovalStatusCheck();

        // Seed the onboarding_document_templates table from the static
        // SkyzenDocument enum so every known template is manageable in
        // the Admin ⟶ Onboarding Documents page. Idempotent: uses
        // ON CONFLICT DO NOTHING on the unique key column so admin-
        // uploaded files + admin-added custom rows are never disturbed.
        seedOnboardingTemplatesFromEnum();

        // ── 8-role finalize: rename HR_COMPLIANCE → HR and
        //                    TECHNICAL_SUPERVISOR → TECHNICAL_EVALUATOR.
        //
        // Idempotent: subsequent boots find zero matching rows. The CHECK
        // drop above must run first so the new values aren't rejected.
        // Each UPDATE is wrapped in its own try/catch so a missing table /
        // column on some deployment doesn't crash startup.
        try {
            int n = jdbcTemplate.update(
                    "UPDATE user_roles SET role = 'HR' WHERE role = 'HR_COMPLIANCE'");
            if (n > 0) log.info("[SchemaFixupRunner] role rename: {} user_roles rows updated "
                    + "HR_COMPLIANCE → HR", n);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] role rename UPDATE skipped (HR_COMPLIANCE → HR): {}",
                    e.getMessage());
        }
        try {
            int n = jdbcTemplate.update(
                    "UPDATE user_roles SET role = 'TECHNICAL_EVALUATOR' "
                            + "WHERE role = 'TECHNICAL_SUPERVISOR'");
            if (n > 0) log.info("[SchemaFixupRunner] role rename: {} user_roles rows updated "
                    + "TECHNICAL_SUPERVISOR → TECHNICAL_EVALUATOR", n);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] role rename UPDATE skipped "
                    + "(TECHNICAL_SUPERVISOR → TECHNICAL_EVALUATOR): {}", e.getMessage());
        }
        // Defensive: any deployment that stored roles on a single users.role
        // column (rather than the join table) gets the same rename.
        try {
            int n = jdbcTemplate.update(
                    "UPDATE users SET role = 'HR' WHERE role = 'HR_COMPLIANCE'");
            if (n > 0) log.info("[SchemaFixupRunner] role rename: {} users.role rows updated "
                    + "HR_COMPLIANCE → HR", n);
        } catch (Exception e) {
            // Expected on the join-table schema (no users.role column).
            log.debug("[SchemaFixupRunner] users.role HR rename skipped: {}", e.getMessage());
        }
        try {
            int n = jdbcTemplate.update(
                    "UPDATE users SET role = 'TECHNICAL_EVALUATOR' "
                            + "WHERE role = 'TECHNICAL_SUPERVISOR'");
            if (n > 0) log.info("[SchemaFixupRunner] role rename: {} users.role rows updated "
                    + "TECHNICAL_SUPERVISOR → TECHNICAL_EVALUATOR", n);
        } catch (Exception e) {
            log.debug("[SchemaFixupRunner] users.role TECHNICAL_EVALUATOR rename skipped: {}",
                    e.getMessage());
        }

        // ── 6-role finalize: collapse the legacy role taxonomy onto the new
        //                    INTERN/TRAINER/REPORTING_MANAGER/MANAGER/ERM/SUPER_ADMIN.
        //
        // Idempotent — subsequent boots find zero matching rows. Each UPDATE
        // is wrapped in its own try/catch so a missing table / column on some
        // deployment doesn't crash startup. The mapping mirrors UserRole.java.
        applyRoleRemap("user_roles", "APPLICANT", "INTERN");
        applyRoleRemap("user_roles", "TECHNICAL_EVALUATOR", "TRAINER");
        applyRoleRemap("user_roles", "TECHNICAL_SUPERVISOR", "TRAINER");
        applyRoleRemap("user_roles", "HR", "ERM");
        applyRoleRemap("user_roles", "HR_COMPLIANCE", "ERM");
        applyRoleRemap("user_roles", "OPERATIONS", "ERM");
        applyRoleRemap("user_roles", "EXECUTIVE", "MANAGER");
        applyRoleRemap("users", "APPLICANT", "INTERN");
        applyRoleRemap("users", "TECHNICAL_EVALUATOR", "TRAINER");
        applyRoleRemap("users", "TECHNICAL_SUPERVISOR", "TRAINER");
        applyRoleRemap("users", "HR", "ERM");
        applyRoleRemap("users", "HR_COMPLIANCE", "ERM");
        applyRoleRemap("users", "OPERATIONS", "ERM");
        applyRoleRemap("users", "EXECUTIVE", "MANAGER");

        // After remap user_roles may contain duplicate (user_id, role) pairs
        // (e.g. a user who carried both OPERATIONS and HR is now ERM twice).
        // Collapse them to keep the join table well-formed.
        try {
            int n = jdbcTemplate.update(
                    "DELETE FROM user_roles a USING user_roles b "
                            + "WHERE a.ctid < b.ctid "
                            + "  AND a.user_id = b.user_id "
                            + "  AND a.role = b.role");
            if (n > 0) log.info("[SchemaFixupRunner] role dedupe: removed {} duplicate user_roles "
                    + "rows after collapse", n);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] user_roles dedupe skipped (non-fatal): {}", e.getMessage());
        }

        // Workspace submissions — drop the auto-generated CHECK on
        // review_outcome so future ReviewOutcome additions don't trip the
        // stale-CHECK trap. Idempotent.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE workspace_submissions "
                            + "DROP CONSTRAINT IF EXISTS workspace_submissions_review_outcome_check");
            log.info("Dropped stale workspace_submissions_review_outcome_check (if present).");
        } catch (Exception e) {
            log.warn("workspace_submissions_review_outcome_check drop failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // Q&A sessions — drop the auto-generated CHECK on status so future
        // QaSessionStatus additions don't trip the stale-CHECK trap.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE qa_sessions DROP CONSTRAINT IF EXISTS qa_sessions_status_check");
            log.info("Dropped stale qa_sessions_status_check (if present).");
        } catch (Exception e) {
            log.warn("qa_sessions_status_check drop failed (non-fatal): {}", e.getMessage(), e);
        }

        // sent_notifications.event_type was created varchar(20) on the legacy
        // schema; the entity now declares length = 64 but ddl-auto never
        // widens an existing column. The longest enum name today is
        // PROJECT_RETURNED_FOR_REVISIONS (30 chars); shorter values like
        // I9_SECTION2_PENDING fit varchar(20) by one byte but trip the
        // overflow during I-9 Section 1 commit because the row write fails
        // before the column is widened. Idempotent: ALTER ... TYPE on an
        // already-varchar(64) column is a no-op.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE sent_notifications ALTER COLUMN event_type TYPE varchar(64)");
            log.info("Ensured sent_notifications.event_type is varchar(64).");
        } catch (Exception e) {
            log.warn("sent_notifications.event_type widen failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // sent_notifications — drop the auto-generated CHECK on event_type so
        // post-batch-1 additions (I9_SECTION2_PENDING, the workspace events,
        // workauth expiry buckets, …) write cleanly. Java enum is the source
        // of truth; the CHECK is dead weight.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE sent_notifications "
                            + "DROP CONSTRAINT IF EXISTS sent_notifications_event_type_check");
            log.info("Dropped stale sent_notifications_event_type_check (if present).");
        } catch (Exception e) {
            log.warn("sent_notifications_event_type_check drop failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // i9_forms.status — defensive widening to varchar(32). The current
        // longest I9Status value (SECTION_1_COMPLETE, 18 chars) fits the
        // legacy varchar(20), but we widen pre-emptively so adding a longer
        // status (e.g. a new SECTION_3_*) later doesn't hit the same
        // ddl-auto-can't-widen trap that bit sent_notifications.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms ALTER COLUMN status TYPE varchar(32)");
            log.info("Ensured i9_forms.status is varchar(32).");
        } catch (Exception e) {
            log.warn("i9_forms.status widen failed (non-fatal): {}", e.getMessage(), e);
        }

        // i9_forms.citizenship_status — the @Column had no explicit length so
        // the legacy schema created it as varchar(20). Two CitizenshipStatus
        // values exceed that: LAWFUL_PERMANENT_RESIDENT (25 chars) and
        // ALIEN_AUTHORIZED_TO_WORK (24 chars). Writing either triggers
        // "value too long for type character varying(20)" on the I-9 Section 1
        // commit, marking the transaction rollback-only. Widen to varchar(32).
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms ALTER COLUMN citizenship_status TYPE varchar(32)");
            log.info("Ensured i9_forms.citizenship_status is varchar(32).");
        } catch (Exception e) {
            log.warn("i9_forms.citizenship_status widen failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // ── Project Catalog + Assignment module ────────────────────────────
        //
        // Additive — the new Project Catalog model adds catalog columns to
        // the existing projects table and a new project_assignments table.
        // Legacy intern_id/status/due_date columns on projects are LEFT IN
        // PLACE so the workspace + submission + project-workflow paths keep
        // working unchanged.

        try {
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS name VARCHAR(200)");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS tech_stack VARCHAR(500)");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS difficulty VARCHAR(20)");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS expected_duration_days INTEGER");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS instructions TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS created_by_id UUID");
            log.info("Ensured projects catalog columns exist (name, tech_stack, "
                    + "difficulty, expected_duration_days, instructions, created_by_id).");
        } catch (Exception e) {
            log.warn("projects catalog-columns ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS project_assignments ("
                            + "  id UUID PRIMARY KEY,"
                            + "  project_id UUID NOT NULL,"
                            + "  intern_id UUID NOT NULL,"
                            + "  assigned_by_id UUID NOT NULL,"
                            + "  assignment_date DATE NOT NULL,"
                            + "  due_date DATE,"
                            + "  notes TEXT,"
                            + "  status VARCHAR(40) NOT NULL DEFAULT 'ASSIGNED',"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_pa_intern "
                            + "ON project_assignments(intern_id)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_pa_project "
                            + "ON project_assignments(project_id)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_pa_assigned_by "
                            + "ON project_assignments(assigned_by_id)");
            log.info("Ensured project_assignments table + indexes exist.");
        } catch (Exception e) {
            log.warn("project_assignments table ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // access_granted was created via Hibernate's @Column(nullable=false)
        // which does NOT emit a DB-level DEFAULT. The CREATE TABLE block above
        // declares "NOT NULL DEFAULT FALSE" for fresh schemas, but on existing
        // deployments the column already existed without a default — so the
        // backfill INSERT below (which doesn't list access_granted) crashed on
        // every boot with a not-null violation. Set the default explicitly so
        // any future INSERT that omits the column also succeeds. Idempotent:
        // SET DEFAULT FALSE on a column that already has it is a no-op.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments "
                            + "ALTER COLUMN access_granted SET DEFAULT FALSE");
            log.info("Ensured project_assignments.access_granted has DB-level DEFAULT FALSE.");
        } catch (Exception e) {
            log.warn("project_assignments.access_granted default set failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // Backfill — for each legacy projects row that has an intern_id but
        // no matching project_assignments row, mint one. Idempotent via the
        // NOT EXISTS guard. Translates the legacy ProjectStatus values to
        // the equivalent ProjectAssignmentStatus enum names (the assignment
        // enum was deliberately defined with the same upstream values).
        //
        // Two hardenings vs. the original block:
        //   * Includes access_granted in the column list with value FALSE.
        //     The DB-level default above also covers this, but listing it
        //     explicitly keeps the INSERT readable and survives any future
        //     deployment that drops the default.
        //   * Status mapping: legacy NOT_STARTED isn't a member of
        //     ProjectAssignmentStatus, so map it to ASSIGNED. All other
        //     legacy statuses overlap by name with the assignment enum.
        try {
            int inserted = jdbcTemplate.update(
                    "INSERT INTO project_assignments "
                            + "  (id, project_id, intern_id, assigned_by_id, "
                            + "   assignment_date, due_date, notes, status, "
                            + "   created_at, updated_at, access_granted) "
                            + "SELECT gen_random_uuid(), p.id, c.user_id, p.assigned_by, "
                            + "       COALESCE(p.start_date, CURRENT_DATE), "
                            + "       p.due_date, NULL, "
                            + "       CASE WHEN p.status = 'NOT_STARTED' THEN 'ASSIGNED' "
                            + "            ELSE COALESCE(p.status, 'ASSIGNED') END, "
                            + "       COALESCE(p.created_at, NOW()), "
                            + "       COALESCE(p.updated_at, NOW()), "
                            + "       FALSE "
                            + "FROM projects p "
                            + "JOIN candidates c ON c.id = p.intern_id "
                            + "WHERE p.intern_id IS NOT NULL "
                            + "  AND c.user_id IS NOT NULL "
                            + "  AND p.assigned_by IS NOT NULL "
                            + "  AND NOT EXISTS ("
                            + "    SELECT 1 FROM project_assignments pa "
                            + "    WHERE pa.project_id = p.id AND pa.intern_id = c.user_id"
                            + "  )");
            if (inserted > 0) {
                log.info("[SchemaFixupRunner] backfilled {} project_assignments from legacy projects",
                        inserted);
            } else {
                log.info("[SchemaFixupRunner] legacy project_assignments backfill: 0 rows (already up-to-date)");
            }
        } catch (Exception e) {
            log.warn("project_assignments backfill failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // ── Project Catalog + Assignment module — repository link table ────
        //
        // Company-owned-repository model: one GitHub repo per Project,
        // shared by every assigned intern. Schema is additive — no impact
        // on the legacy single-allocation paths or workspace.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS project_repositories ("
                            + "  id UUID PRIMARY KEY,"
                            + "  project_id UUID NOT NULL UNIQUE,"
                            + "  repository_name VARCHAR(200) NOT NULL,"
                            + "  repository_url VARCHAR(500) NOT NULL,"
                            + "  github_repository_id VARCHAR(100),"
                            + "  linked_by_id UUID NOT NULL,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_pr_project "
                            + "ON project_repositories(project_id)");
            log.info("Ensured project_repositories table + index exist.");
        } catch (Exception e) {
            log.warn("project_repositories table ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // Project catalog text fields the repository-owned-by-project model
        // adds on top of the prior catalog migration.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS requirements TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS objectives TEXT");
            log.info("Ensured projects.requirements + projects.objectives columns exist.");
        } catch (Exception e) {
            log.warn("projects requirements/objectives ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // Catalog-readers (ProjectAssignmentService.mapWithGraph, ProjectCatalogService)
        // read project.name with a fall-back to project.title. The legacy
        // ProjectService.create path only writes title — leaving name NULL.
        // The intern detail page is now driven by the catalog DTO; mirroring
        // title → name once means every legacy row has a meaningful `name`
        // immediately, even before its first PUT. Idempotent: the NULL guard
        // short-circuits on subsequent boots.
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE projects SET name = title "
                            + "WHERE name IS NULL AND title IS NOT NULL");
            if (updated > 0) {
                log.info("[SchemaFixupRunner] backfilled projects.name from title for {} row(s).",
                        updated);
            }
        } catch (Exception e) {
            log.warn("projects.name <- title backfill failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // ── Legacy resource-link → project_repositories backfill ───────────
        //
        // Many existing legacy `projects` rows carry a GitHub URL inside
        // resource_links_json (the TE typed it in the "resource links" field
        // of the legacy allocation modal) but have no matching
        // project_repositories row. The intern detail page's Repository card
        // reads from project_repositories, so those projects render
        // "Repository not yet linked" even though the URL is sitting one
        // column away. This block bridges the gap once, on boot, idempotently.
        //
        // Per-row try/catch so a single malformed JSON or DB error doesn't
        // stop the batch. ON CONFLICT DO NOTHING on project_id makes the
        // INSERT a no-op if a row already exists for the project — re-runs
        // are safe.
        try {
            backfillProjectRepositoriesFromResourceLinks();
        } catch (Exception e) {
            log.warn("project_repositories backfill from resource_links failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // Assignment lifecycle columns — access-granted tracking + start /
        // submit timestamps + the renamed `remarks` column (the legacy
        // `notes` column is left in place for back-compat reads).
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments "
                            + "ADD COLUMN IF NOT EXISTS remarks TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments "
                            + "ADD COLUMN IF NOT EXISTS access_granted BOOLEAN "
                            + "NOT NULL DEFAULT FALSE");
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments "
                            + "ADD COLUMN IF NOT EXISTS access_granted_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments "
                            + "ADD COLUMN IF NOT EXISTS access_granted_by_id UUID");
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments "
                            + "ADD COLUMN IF NOT EXISTS started_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments "
                            + "ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments "
                            + "ADD COLUMN IF NOT EXISTS submission_notes TEXT");
            // GitHub invitation id captured by GitHubService.addCollaborator on
            // a successful grant. Null when the App is unconfigured (out-of-band
            // grants) or when we haven't called GitHub for this assignment yet.
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments "
                            + "ADD COLUMN IF NOT EXISTS github_invitation_id BIGINT");
            log.info("Ensured project_assignments lifecycle columns exist.");
        } catch (Exception e) {
            log.warn("project_assignments lifecycle-columns ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // Intern's self-provided GitHub username (used by the assignment
        // module so the TE can invite them as a collaborator out-of-band).
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS github_username VARCHAR(100)");
            log.info("Ensured users.github_username column exists.");
        } catch (Exception e) {
            log.warn("users.github_username ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        try {
            // Adds the `users.active` column on existing databases. Hibernate's
            // ddl-auto=update can't add a NOT NULL column to a table with rows
            // unless the DDL also supplies a DEFAULT — we do that here before
            // Hibernate's schema validation runs, so existing users keep working.
            // Idempotent: ADD COLUMN IF NOT EXISTS is a no-op the second time.
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE");
            log.info("Ensured users.active column exists (default TRUE).");
        } catch (Exception e) {
            log.warn("users.active column ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // Phase 1.2: email-verification gate + Anvi Corp Applicant ID.
        // Existing rows are backfilled to email_verified=TRUE via the column
        // DEFAULT so they aren't locked out by the new gate. New CANDIDATE
        // registrations are written explicitly with FALSE by the JPA insert.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT TRUE");
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verification_code VARCHAR(16)");
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verification_sent_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verification_expires_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS applicant_id VARCHAR(32) UNIQUE");
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS applicant_id_created_at TIMESTAMP");
            log.info("Ensured users verification + applicant_id columns exist.");
        } catch (Exception e) {
            log.warn("users verification columns ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // Sequence backing SKZ-INT-YYYY-NNNNNN. Atomic nextval() under
        // concurrency; CACHE 1 keeps the suffix monotonically increasing
        // across boots even if pre-cached values would otherwise be skipped.
        try {
            jdbcTemplate.execute(
                    "CREATE SEQUENCE IF NOT EXISTS skyzen_applicant_seq START WITH 1 INCREMENT BY 1 CACHE 1");
            log.info("Ensured skyzen_applicant_seq exists.");
        } catch (Exception e) {
            log.warn("skyzen_applicant_seq ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // Sequence backing SKZ-JOB-YYYY-NNNNNN. Same atomic-nextval/CACHE-1
        // pattern as skyzen_applicant_seq above. Used by JobIdGenerator
        // when a new job posting is created and by the one-shot backfill
        // (BACKFILL_JOB_ID_V1) for any rows predating the column.
        try {
            jdbcTemplate.execute(
                    "CREATE SEQUENCE IF NOT EXISTS skyzen_job_seq START WITH 1 INCREMENT BY 1 CACHE 1");
            log.info("Ensured skyzen_job_seq exists.");
        } catch (Exception e) {
            log.warn("skyzen_job_seq ensure failed (non-fatal): {}", e.getMessage(), e);
        }
        // Make sure the column exists before the backfill UPDATE tries to
        // touch it. ddl-auto=update normally handles this for the entity
        // but explicit ALTERs cover deployments where ddl-auto is off.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE job_postings ADD COLUMN IF NOT EXISTS job_id VARCHAR(32)");
        } catch (Exception e) {
            log.warn("job_postings.job_id add failed (non-fatal): {}", e.getMessage());
        }

        // Phase 1.5 — structured education + work-auth start date on
        // candidates. All additive + nullable; existing rows keep their
        // legacy free-text `education` / `degree` values untouched, and
        // the ERM application detail page falls back to those when the
        // structured columns are null.
        for (String sql : new String[] {
                "ALTER TABLE candidates ADD COLUMN IF NOT EXISTS degree_level VARCHAR(20)",
                "ALTER TABLE candidates ADD COLUMN IF NOT EXISTS specialization VARCHAR(200)",
                "ALTER TABLE candidates ADD COLUMN IF NOT EXISTS graduation_year SMALLINT",
                "ALTER TABLE candidates ADD COLUMN IF NOT EXISTS validity_start_date DATE"
        }) {
            try { jdbcTemplate.execute(sql); }
            catch (Exception e) {
                log.warn("[SchemaFixup] candidates Phase 1.5 ALTER skipped ({}): {}",
                        sql, e.getMessage());
            }
        }

        // Phase 1.6 — explicit intern handoff on the document packet.
        // intern_locked defaults to FALSE so existing packets keep
        // their current "intern may upload anytime" behaviour. The
        // backfill is the column default; no UPDATE needed.
        for (String sql : new String[] {
                "ALTER TABLE document_packets ADD COLUMN IF NOT EXISTS intern_locked BOOLEAN NOT NULL DEFAULT FALSE",
                "ALTER TABLE document_packets ADD COLUMN IF NOT EXISTS intern_submitted_at TIMESTAMP"
        }) {
            try { jdbcTemplate.execute(sql); }
            catch (Exception e) {
                log.warn("[SchemaFixup] document_packets Phase 1.6 ALTER skipped ({}): {}",
                        sql, e.getMessage());
            }
        }

        // Phase 2.2: scorecard dimensions. New columns are nullable (legacy
        // rows pre-2.2 simply won't have a problem-solving score) so the
        // ADD COLUMN IF NOT EXISTS is safe on a populated table.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS feedback_problem_solving_rating INTEGER");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS feedback_comments TEXT");
            log.info("Ensured interviews scorecard columns exist.");
        } catch (Exception e) {
            log.warn("interviews scorecard columns ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // GAP C7 — widen I-9 PII columns to TEXT so the AES-256-GCM ciphertext
        // envelope (base64(IV||ct+tag), ~50-90 bytes per short string) fits.
        // Each ALTER is idempotent under Postgres: TYPE TEXT on an already-TEXT
        // column is a no-op. date_of_birth requires a USING clause because
        // it's a real DATE column being switched to TEXT — Postgres won't
        // implicitly cast DATE -> TEXT.
        //
        // KEY-LOSS WARNING: encrypted columns are unrecoverable without the
        // I9_ENCRYPTION_KEY. Existing plaintext rows will FAIL to decrypt
        // post-rollout — run the I9PlaintextEncryptionMigrator (profile
        // i9-migrate) on environments with data worth keeping, or truncate
        // i9_forms + everify_cases on environments without real data.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms ALTER COLUMN ssn TYPE TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms ALTER COLUMN alien_registration_number TYPE TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms ALTER COLUMN foreign_passport_number TYPE TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms ALTER COLUMN list_a_document_number TYPE TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms ALTER COLUMN list_b_document_number TYPE TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms ALTER COLUMN list_c_document_number TYPE TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE i9_forms ALTER COLUMN date_of_birth TYPE TEXT "
                            + "USING date_of_birth::text");
            log.info("Ensured i9_forms PII columns are TEXT for AES-256-GCM ciphertext.");
        } catch (Exception e) {
            log.warn("i9_forms PII column widening failed (non-fatal): {}", e.getMessage(), e);
        }

        // ── Phase 0 (Applicant-to-Intern Lifecycle) ────────────────────────
        //
        // users.lifecycle_status — canonical position on the 13-state journey
        // the Phase-1 mode engine derives the intern dashboard mode from.
        // Backfilled to REGISTERED for every existing row via the column
        // DEFAULT so the NOT NULL add is safe on a populated table.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS lifecycle_status "
                            + "VARCHAR(40) NOT NULL DEFAULT 'REGISTERED'");
            log.info("Ensured users.lifecycle_status column exists (default REGISTERED).");
        } catch (Exception e) {
            log.warn("users.lifecycle_status add failed (non-fatal): {}", e.getMessage(), e);
        }

        // users.employee_id — minted at OFFER_SIGNED → EMPLOYEE_ID_CREATED
        // transition (Phase 3). Nullable until then; no default value.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS employee_id VARCHAR(40)");
            log.info("Ensured users.employee_id column exists (nullable).");
        } catch (Exception e) {
            log.warn("users.employee_id add failed (non-fatal): {}", e.getMessage(), e);
        }

        // job_postings.employment_type → job_type. Doc nomenclature uses
        // job_type (FULL_TIME | INTERNSHIP). RENAME COLUMN is non-idempotent
        // on Postgres < 15 — wrap in try/catch so the second boot's no-op
        // failure is swallowed silently. If neither column exists, Hibernate
        // ddl-auto=update will add job_type from the entity definition.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE job_postings RENAME COLUMN employment_type TO job_type");
            log.info("Renamed job_postings.employment_type → job_type.");
        } catch (Exception e) {
            log.debug("job_postings.employment_type → job_type rename skipped (likely already renamed): {}",
                    e.getMessage());
        }

        // Doc-spec entity field aligns: additive columns only — idempotent.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS zoom_meeting_id VARCHAR(64)");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS applicant_visible_notes TEXT");
            log.info("Ensured interviews zoom_meeting_id + applicant_visible_notes columns exist.");
        } catch (Exception e) {
            log.warn("interviews doc-spec columns add failed (non-fatal): {}", e.getMessage(), e);
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS docusign_envelope_id VARCHAR(64)");
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS signed_pdf_file_id VARCHAR(128)");
            log.info("Ensured offers legacy envelope id + signed_pdf_file_id columns exist.");
        } catch (Exception e) {
            log.warn("offers doc-spec columns add failed (non-fatal): {}", e.getMessage(), e);
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE applications ADD COLUMN IF NOT EXISTS manager_owner_id UUID");
            log.info("Ensured applications.manager_owner_id column exists.");
        } catch (Exception e) {
            log.warn("applications.manager_owner_id add failed (non-fatal): {}", e.getMessage(), e);
        }
        // applications.operations_owner_id → erm_owner_id. Rename if legacy
        // column exists; otherwise no-op.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE applications RENAME COLUMN operations_owner_id TO erm_owner_id");
            log.info("Renamed applications.operations_owner_id → erm_owner_id.");
        } catch (Exception e) {
            log.debug("applications.operations_owner_id rename skipped: {}", e.getMessage());
        }

        // ── Phase 2 (pre-offer modules + Zoom) ─────────────────────────────
        //
        // Additive columns only — idempotent ADD COLUMN IF NOT EXISTS.

        // users.zoom_email — ERM members who host Zoom interviews. The Zoom
        // user id used as host when creating a meeting; falls back to "me"
        // (the service-account host) when null.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS zoom_email VARCHAR(100)");
            log.info("Ensured users.zoom_email column exists.");
        } catch (Exception e) {
            log.warn("users.zoom_email add failed (non-fatal): {}", e.getMessage(), e);
        }

        // users.must_change_password — drives the first-login forced
        // password-change flow for staff accounts created by SUPER_ADMIN
        // with a temp password. Defaults FALSE so existing rows (intern
        // signups, prior admin-created staff) aren't gated retroactively.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                            + "must_change_password BOOLEAN NOT NULL DEFAULT FALSE");
            log.info("Ensured users.must_change_password column exists.");
        } catch (Exception e) {
            log.warn("users.must_change_password add failed (non-fatal): {}", e.getMessage(), e);
        }

        // users.password_hash — drop NOT NULL so the activation-link admin
        // create flow can persist an unactivated row (no usable password
        // until the user redeems their activation link). AuthService.login
        // refuses null-hash rows with a clear "Account not activated"
        // error; setPassword via /auth/activate clears the null.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL");
            log.info("Ensured users.password_hash is nullable (activation-link flow).");
        } catch (Exception e) {
            log.warn("users.password_hash DROP NOT NULL failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // staff_activation_tokens — one row per outstanding admin invite.
        // Token stored ONLY as SHA-256 hex of the raw value (never raw).
        // Single-use via used_at IS NOT NULL; 24-hour expiry; uniqueness
        // on the hash so a collision can't shadow another row.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS staff_activation_tokens ("
                            + "  id UUID PRIMARY KEY,"
                            + "  user_id UUID NOT NULL,"
                            + "  token_hash VARCHAR(128) NOT NULL,"
                            + "  expires_at TIMESTAMP NOT NULL,"
                            + "  used_at TIMESTAMP,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  created_by_id UUID"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_staff_activation_tokens_hash "
                            + "ON staff_activation_tokens(token_hash)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_staff_activation_tokens_user "
                            + "ON staff_activation_tokens(user_id)");
            log.info("Ensured staff_activation_tokens table + indexes exist.");
        } catch (Exception e) {
            log.warn("staff_activation_tokens table ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // applications doc-spec fields added in Phase 2.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE applications ADD COLUMN IF NOT EXISTS statement_of_interest TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE applications ADD COLUMN IF NOT EXISTS applicant_visible_feedback TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE applications ADD COLUMN IF NOT EXISTS erm_owner_id UUID");
            log.info("Ensured applications statement_of_interest + applicant_visible_feedback + erm_owner_id columns exist.");
        } catch (Exception e) {
            log.warn("applications Phase 2 columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // Selection acknowledgment — intern's explicit "I've been selected,
        // please send my offer letter" click. Gates ErmOfferService.createAndSend
        // so an offer isn't issued until the intern actively requests it.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE applications ADD COLUMN IF NOT EXISTS selection_acknowledged_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE applications ADD COLUMN IF NOT EXISTS selection_acknowledged_by UUID");
            log.info("Ensured applications selection_acknowledged_at + selection_acknowledged_by columns exist.");
        } catch (Exception e) {
            log.warn("applications selection_acknowledged columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // ── Phase 3 (offer letter + IDMS signing + employee id) ────────────

        // documents table — the document vault. Phase 3 wrote SIGNED_OFFER
        // rows; under IDMS the signature image is stored inline on the
        // offer row, so SIGNED_OFFER documents only land for legacy
        // externally-signed offers. Phase 4 adds W4 / I9 / ACH / etc.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS documents ("
                            + "  id UUID PRIMARY KEY,"
                            + "  owner_user_id UUID NOT NULL,"
                            + "  file_name VARCHAR(255) NOT NULL,"
                            + "  file_size BIGINT NOT NULL,"
                            + "  mime_type VARCHAR(100) NOT NULL,"
                            + "  storage_key VARCHAR(500) NOT NULL,"
                            + "  category VARCHAR(40) NOT NULL,"
                            + "  sensitivity VARCHAR(20) NOT NULL DEFAULT 'NORMAL',"
                            + "  uploaded_by_id UUID,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_documents_owner "
                            + "ON documents(owner_user_id)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_documents_category "
                            + "ON documents(category)");
            log.info("Ensured documents table + indexes exist.");
        } catch (Exception e) {
            log.warn("documents table ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // offers Phase 3 columns. signed_pdf_file_id was the Phase 0 column;
        // Phase 3 introduces signed_pdf_document_id pointing at documents.id.
        // Both can coexist; the entity reads/writes signed_pdf_document_id.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS docusign_template_id VARCHAR(80)");
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS signed_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS voided_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS voided_reason TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS role_title VARCHAR(200)");
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS compensation_summary VARCHAR(500)");
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS worksite VARCHAR(200)");
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS expected_hours_per_week INTEGER");
            jdbcTemplate.execute(
                    "ALTER TABLE offers ADD COLUMN IF NOT EXISTS signed_pdf_document_id UUID");
            log.info("Ensured offers Phase 3 columns exist (signing / voided / etc.).");
        } catch (Exception e) {
            log.warn("offers Phase 3 columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // docusign_envelope_id was added in Phase 0 (varchar 64) — widen to 80
        // to match the legacy external-signing id format and add the UNIQUE
        // index defensively. Column name kept post-IDMS-migration for data
        // preservation; new offers leave it null.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE offers ALTER COLUMN docusign_envelope_id TYPE VARCHAR(80)");
        } catch (Exception e) {
            log.debug("offers.docusign_envelope_id widen skipped: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uk_offers_docusign_envelope_id "
                            + "ON offers(docusign_envelope_id) "
                            + "WHERE docusign_envelope_id IS NOT NULL");
        } catch (Exception e) {
            log.warn("uk_offers_docusign_envelope_id ensure failed (non-fatal): {}", e.getMessage());
        }

        // intern_lifecycles Phase 3 columns. user_id + hired_at are required
        // for any new row Phase 3 writes; pre-existing skeletal rows (Phase 0)
        // may have NULL for either. Hibernate enforces NOT NULL on new INSERTs
        // via the entity; the ALTER below sets the column NOT NULL only when
        // there are no NULLs left (safer no-op otherwise).
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS user_id UUID");
            jdbcTemplate.execute(
                    "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS hired_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS started_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP");
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uk_intern_lifecycles_user_id "
                            + "ON intern_lifecycles(user_id) WHERE user_id IS NOT NULL");
            log.info("Ensured intern_lifecycles Phase 3 columns exist (user_id, hired_at, ...).");
        } catch (Exception e) {
            log.warn("intern_lifecycles Phase 3 columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // skyzen_employee_seq — backs ANVI-EMP-YYYY-NNNNNN. CACHE 1 so the
        // suffix is monotonic across boots even if pre-cached values would
        // otherwise be skipped. Mirrors skyzen_applicant_seq. Sequence name
        // is kept as skyzen_employee_seq to preserve the DB counter across
        // the Skyzen → Anvi rebrand — only the emitted string prefix moves.
        try {
            jdbcTemplate.execute(
                    "CREATE SEQUENCE IF NOT EXISTS skyzen_employee_seq "
                            + "START WITH 1000 INCREMENT BY 1 CACHE 1");
            log.info("Ensured skyzen_employee_seq exists (START 1000).");
        } catch (Exception e) {
            log.warn("skyzen_employee_seq ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // ── Phase 4 (onboarding packet + document vault) ───────────────────

        // onboarding_packets — one per intern, created when ERM assigns.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS onboarding_packets ("
                            + "  id UUID PRIMARY KEY,"
                            + "  user_id UUID NOT NULL UNIQUE,"
                            + "  intern_lifecycle_id UUID NOT NULL UNIQUE,"
                            + "  status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED',"
                            + "  assigned_by_id UUID NOT NULL,"
                            + "  assigned_at TIMESTAMP NOT NULL,"
                            + "  accepted_at TIMESTAMP,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_onboarding_packets_status "
                            + "ON onboarding_packets(status)");
            log.info("Ensured onboarding_packets table + index exist.");
        } catch (Exception e) {
            log.warn("onboarding_packets table ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // onboarding_items — one per W4/I9/ACH/EMERGENCY_CONTACT/HANDBOOK_ACK/I983.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS onboarding_items ("
                            + "  id UUID PRIMARY KEY,"
                            + "  packet_id UUID NOT NULL,"
                            + "  category VARCHAR(40) NOT NULL,"
                            + "  required BOOLEAN NOT NULL DEFAULT TRUE,"
                            + "  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',"
                            + "  form_data_json TEXT,"
                            + "  document_id UUID,"
                            + "  submitted_at TIMESTAMP,"
                            + "  reviewed_at TIMESTAMP,"
                            + "  reviewed_by_id UUID,"
                            + "  erm_comments TEXT,"
                            + "  internal_notes TEXT,"
                            + "  version INTEGER NOT NULL DEFAULT 1,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uk_onboarding_items_packet_category "
                            + "ON onboarding_items(packet_id, category)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_onboarding_items_packet "
                            + "ON onboarding_items(packet_id)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_onboarding_items_status "
                            + "ON onboarding_items(status)");
            log.info("Ensured onboarding_items table + indexes exist.");
        } catch (Exception e) {
            log.warn("onboarding_items table ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // documents Phase 4 additive columns — encryption envelope for the
        // content bytes (PII / FINANCIAL / GOVERNMENT_ID sensitivities) and
        // a soft-delete marker so deletes don't lose forensic trail.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE documents ADD COLUMN IF NOT EXISTS encryption_metadata_json TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE documents ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP");
            log.info("Ensured documents Phase 4 columns exist (encryption_metadata_json, deleted_at).");
        } catch (Exception e) {
            log.warn("documents Phase 4 columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // ── Phase 5 (active intern: meetings, project alignment) ───────────

        // weekly_meetings table — Trainer schedules + Zoom. Distinct from the
        // existing interviews table (which is pre-hire scope).
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS weekly_meetings ("
                            + "  id UUID PRIMARY KEY,"
                            + "  intern_lifecycle_id UUID NOT NULL,"
                            + "  scheduled_for TIMESTAMP NOT NULL,"
                            + "  duration_minutes INTEGER NOT NULL DEFAULT 30,"
                            + "  timezone VARCHAR(50) NOT NULL DEFAULT 'UTC',"
                            + "  topic VARCHAR(200) NOT NULL,"
                            + "  agenda TEXT,"
                            + "  zoom_meeting_id VARCHAR(64),"
                            + "  zoom_join_url TEXT,"
                            + "  zoom_start_url TEXT,"
                            + "  zoom_password VARCHAR(40),"
                            + "  host_user_id UUID NOT NULL,"
                            + "  status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',"
                            + "  recurrence VARCHAR(20),"
                            + "  recurrence_parent_id UUID,"
                            + "  trainer_notes TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_weekly_meetings_lifecycle_scheduled "
                            + "ON weekly_meetings(intern_lifecycle_id, scheduled_for)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_weekly_meetings_status "
                            + "ON weekly_meetings(status)");
            log.info("Ensured weekly_meetings table + indexes exist.");
        } catch (Exception e) {
            log.warn("weekly_meetings table ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // weekly_meetings.zoom_meeting_id — was BIGINT; Phase 2 of the WebEx
        // migration widens it to VARCHAR(64) so a single column carries either
        // Zoom's numeric id (stored as a decimal string) or WebEx's opaque
        // alphanumeric id. Hibernate's ddl-auto=update can't safely cast
        // BIGINT -> VARCHAR on its own — needs an explicit USING cast. Same
        // idempotent pattern as the previous BIGINT cast: a no-op on tables
        // already VARCHAR.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE weekly_meetings ALTER COLUMN zoom_meeting_id "
                            + "TYPE VARCHAR(64) USING zoom_meeting_id::text");
            log.info("Ensured weekly_meetings.zoom_meeting_id is VARCHAR(64).");
        } catch (Exception e) {
            log.warn("weekly_meetings.zoom_meeting_id VARCHAR cast failed (non-fatal): {}",
                    e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ALTER COLUMN zoom_meeting_id "
                            + "TYPE VARCHAR(64) USING zoom_meeting_id::text");
            log.info("Ensured interviews.zoom_meeting_id is VARCHAR(64).");
        } catch (Exception e) {
            log.warn("interviews.zoom_meeting_id VARCHAR cast failed (non-fatal): {}",
                    e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE intern_evaluations ALTER COLUMN zoom_meeting_id "
                            + "TYPE VARCHAR(64) USING zoom_meeting_id::text");
            log.info("Ensured intern_evaluations.zoom_meeting_id is VARCHAR(64).");
        } catch (Exception e) {
            log.warn("intern_evaluations.zoom_meeting_id VARCHAR cast failed (non-fatal): {}",
                    e.getMessage());
        }

        // weekly_meetings — persistent Zoom failure flag. Mirrors the ERM
        // interview ThreadLocal pattern but keeps the signal across requests
        // so the trainer UI can render a Regenerate banner on a fresh GET
        // after a silent Zoom PATCH failure during reschedule.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE weekly_meetings ADD COLUMN IF NOT EXISTS "
                            + "zoom_update_failed BOOLEAN NOT NULL DEFAULT FALSE");
            jdbcTemplate.execute(
                    "ALTER TABLE weekly_meetings ADD COLUMN IF NOT EXISTS "
                            + "zoom_last_error TEXT");
            log.info("Ensured weekly_meetings.zoom_update_failed + zoom_last_error columns exist.");
        } catch (Exception e) {
            log.warn("weekly_meetings zoom-status columns add failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        // projects Phase 5 column additions. Existing Project entity already
        // has most fields; Phase 5 adds the doc-spec ones that were missing.
        // Idempotent — re-runs are no-ops once columns exist.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS intern_lifecycle_id UUID");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS learning_objectives TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS expected_hours INTEGER");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS completion_remarks TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS github_repo_url VARCHAR(500)");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS github_repo_owner VARCHAR(100)");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS github_repo_name VARCHAR(100)");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS github_access_granted "
                            + "BOOLEAN NOT NULL DEFAULT FALSE");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS github_invitation_id BIGINT");
            log.info("Ensured projects Phase 5 columns exist.");
        } catch (Exception e) {
            log.warn("projects Phase 5 columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // KT (Knowledge Transfer) session — Trainer-marked, per monthly
        // project. Tracked on the project row because Project is already
        // per-intern-per-month-slot (intern_lifecycle_id + month_year +
        // project_number is the canonical assignment key). Default
        // NOT_DONE; flipped to DONE by the Trainer after the session.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS kt_status VARCHAR(16) "
                            + "NOT NULL DEFAULT 'NOT_DONE'");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS kt_completed_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS kt_meeting_link TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS kt_notes TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD COLUMN IF NOT EXISTS kt_marked_by_id UUID");
            log.info("Ensured projects KT (Knowledge Transfer) columns exist.");
        } catch (Exception e) {
            log.warn("projects KT columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // project_submissions Phase 5 columns — versioned reviews.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS version "
                            + "INTEGER NOT NULL DEFAULT 1");
            jdbcTemplate.execute(
                    "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS trainer_decision "
                            + "VARCHAR(20)");
            jdbcTemplate.execute(
                    "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS trainer_feedback TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP");
            jdbcTemplate.execute(
                    "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS reviewed_by_id UUID");
            log.info("Ensured project_submissions Phase 5 columns exist.");
        } catch (Exception e) {
            log.warn("project_submissions Phase 5 columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // Phase B (timesheet weekly redesign) — two-stage approval chain.
        // VERIFIED is the new ERM-verify stage that B2 transitions into.
        // B1 ships only the column so existing readers don't crash when
        // the enum value lands. Manager-approve continues to write
        // approved_by / approved_at; B2 will add verified_by / verified_at.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE timesheets ADD COLUMN IF NOT EXISTS verified_by UUID");
            jdbcTemplate.execute(
                    "ALTER TABLE timesheets ADD COLUMN IF NOT EXISTS verified_at TIMESTAMP");
            log.info("Ensured timesheets Phase B columns exist.");
        } catch (Exception e) {
            log.warn("timesheets Phase B columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // ── Phase 6 (evaluation cycle) ─────────────────────────────────────

        // intern_evaluations table — distinct from the legacy `evaluations`
        // table (DRAFT → FINALIZED supervisor model). Phase 6's full
        // DRAFT → SCHEDULED → IN_PROGRESS → PUBLISHED → ACKNOWLEDGED →
        // AMENDED lifecycle lives here.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS intern_evaluations ("
                            + "  id UUID PRIMARY KEY,"
                            + "  intern_lifecycle_id UUID NOT NULL,"
                            + "  intern_id UUID NOT NULL,"
                            + "  evaluator_id UUID NOT NULL,"
                            + "  evaluation_type VARCHAR(30) NOT NULL,"
                            + "  linked_project_id UUID,"
                            + "  linked_i983_id UUID,"
                            + "  period_start DATE,"
                            + "  period_end DATE,"
                            + "  scheduled_for TIMESTAMP,"
                            + "  duration_minutes INTEGER,"
                            + "  timezone VARCHAR(50),"
                            + "  zoom_meeting_id VARCHAR(64),"
                            + "  zoom_join_url TEXT,"
                            + "  zoom_start_url TEXT,"
                            + "  zoom_password VARCHAR(40),"
                            + "  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',"
                            + "  overall_score INTEGER,"
                            + "  technical_skills_score INTEGER,"
                            + "  communication_score INTEGER,"
                            + "  professionalism_score INTEGER,"
                            + "  learning_application_score INTEGER,"
                            + "  strengths_narrative TEXT,"
                            + "  areas_for_improvement_narrative TEXT,"
                            + "  improvement_plan TEXT,"
                            + "  intern_acknowledged_at TIMESTAMP,"
                            + "  intern_response TEXT,"
                            + "  published_at TIMESTAMP,"
                            + "  amended_at TIMESTAMP,"
                            + "  amendment_reason TEXT,"
                            + "  version INTEGER NOT NULL DEFAULT 1,"
                            + "  internal_notes TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_intern_eval_lifecycle_type "
                            + "ON intern_evaluations(intern_lifecycle_id, evaluation_type)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_intern_eval_intern_status "
                            + "ON intern_evaluations(intern_id, status)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_intern_eval_evaluator_status "
                            + "ON intern_evaluations(evaluator_id, status)");
            log.info("Ensured intern_evaluations table + indexes exist.");
        } catch (Exception e) {
            log.warn("intern_evaluations table ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS evaluation_amendments ("
                            + "  id UUID PRIMARY KEY,"
                            + "  evaluation_id UUID NOT NULL,"
                            + "  amended_by_id UUID NOT NULL,"
                            + "  amendment_reason TEXT NOT NULL,"
                            + "  previous_version INTEGER NOT NULL,"
                            + "  new_version INTEGER NOT NULL,"
                            + "  snapshot_json TEXT NOT NULL,"
                            + "  amended_at TIMESTAMP NOT NULL"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_eval_amendments_eval_at "
                            + "ON evaluation_amendments(evaluation_id, amended_at)");
            log.info("Ensured evaluation_amendments table + index exist.");
        } catch (Exception e) {
            log.warn("evaluation_amendments table ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // ── Phase 7 (cross-cutting: notifications inbox + support tickets) ─

        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS user_notifications ("
                            + "  id UUID PRIMARY KEY,"
                            + "  recipient_user_id UUID NOT NULL,"
                            + "  event_type VARCHAR(64) NOT NULL,"
                            + "  subject_user_id UUID,"
                            + "  title VARCHAR(200) NOT NULL,"
                            + "  body TEXT NOT NULL,"
                            + "  action_url VARCHAR(500),"
                            + "  read_at TIMESTAMP,"
                            + "  email_sent BOOLEAN NOT NULL DEFAULT FALSE,"
                            + "  email_sent_at TIMESTAMP,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_user_notifications_recipient_read "
                            + "ON user_notifications(recipient_user_id, read_at)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_user_notifications_recipient_created "
                            + "ON user_notifications(recipient_user_id, created_at)");
            log.info("Ensured user_notifications table + indexes exist.");
        } catch (Exception e) {
            log.warn("user_notifications table ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS support_tickets ("
                            + "  id UUID PRIMARY KEY,"
                            + "  opener_user_id UUID NOT NULL,"
                            + "  subject VARCHAR(200) NOT NULL,"
                            + "  body TEXT NOT NULL,"
                            + "  category VARCHAR(30) NOT NULL,"
                            + "  priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',"
                            + "  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',"
                            + "  assigned_to_id UUID,"
                            + "  resolved_at TIMESTAMP,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_support_tickets_opener_status "
                            + "ON support_tickets(opener_user_id, status)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_support_tickets_status_created "
                            + "ON support_tickets(status, created_at)");
            log.info("Ensured support_tickets table + indexes exist.");
        } catch (Exception e) {
            log.warn("support_tickets table ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS support_ticket_replies ("
                            + "  id UUID PRIMARY KEY,"
                            + "  ticket_id UUID NOT NULL,"
                            + "  author_user_id UUID NOT NULL,"
                            + "  body TEXT NOT NULL,"
                            + "  internal_only BOOLEAN NOT NULL DEFAULT FALSE,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_support_ticket_replies_ticket_created "
                            + "ON support_ticket_replies(ticket_id, created_at)");
            log.info("Ensured support_ticket_replies table + index exist.");
        } catch (Exception e) {
            log.warn("support_ticket_replies table ensure failed (non-fatal): {}", e.getMessage(), e);
        }

        // interviews doc-spec fields. zoom_start_url is HOST-ONLY — never
        // returned to applicants (enforced in the DTO mapper).
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS timezone VARCHAR(50) "
                            + "NOT NULL DEFAULT 'UTC'");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS zoom_join_url TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS zoom_start_url TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS zoom_password VARCHAR(40)");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS decision VARCHAR(20)");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS internal_notes TEXT");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS prep_instructions TEXT");
            // zoom_meeting_id added in Phase 0; widened to VARCHAR(64) in
            // WebEx-migration Phase 2 (the ALTER TYPE block above is the
            // converter for existing BIGINT columns; this defensive
            // ADD-IF-NOT-EXISTS now uses VARCHAR so a fresh DB matches).
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS zoom_meeting_id VARCHAR(64)");
            // applicant_visible_notes added in Phase 0 — defensive.
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS applicant_visible_notes TEXT");
            log.info("Ensured interviews Phase 2 columns exist "
                    + "(timezone, zoom_*, decision, internal_notes, prep_instructions).");
        } catch (Exception e) {
            log.warn("interviews Phase 2 columns add failed (non-fatal): {}", e.getMessage(), e);
        }

        // Phase 8.6.3 — sweep every enum-backed CHECK constraint and realign
        // with the current Java enum value set. Runs last so every table /
        // column referenced by the spec list has had a chance to be created
        // by the ensure*() methods above. Gated by a migration_log row so
        // detection only fires once per database.
        alignEnumCheckConstraintsV1();

        // Phase E — re-sync the document_tasks.document_key CHECK on
        // every boot. Hibernate auto-generates the constraint from the
        // {@link SkyzenDocument} enum when the column is created, but
        // ddl-auto=update never refreshes it when the enum grows —
        // adding PASSPORT_FRONT etc. broke onboarding-packet assign
        // with a 23514 check_violation. This sync method is NOT
        // migration_log-gated because the enum is still growing; it
        // is idempotent (no-op when the constraint already covers
        // every current SkyzenDocument value).
        syncDocumentKeyCheckConstraint();

        // Trainer/Evaluator auto-link backfill — one-shot, idempotent.
        // Catches active interns whose intern_lifecycles.trainer_id /
        // evaluator_id are NULL because DEFAULT_TRAINER_EMAIL /
        // DEFAULT_EVALUATOR_EMAIL were unset (or unresolved) at the
        // time their offer was signed. Without this they remain
        // invisible to the Trainer / Evaluator dashboards forever.
        // Future activations are covered by the runtime backstop in
        // InternActivationJob (via ReportingStructureAutoLinker).
        backfillAutoLinkV1();

        // V2 sweep. V1 stamps migration_log unconditionally on first
        // boot, so any interns already activated before
        // DEFAULT_TRAINER_EMAIL / DEFAULT_EVALUATOR_EMAIL resolved
        // stayed NULL forever (their dashboards saw "Intern not in your
        // roster" 403s on project / KT actions). V2 re-runs the same
        // fill-nulls-only update under a fresh migration_log key so the
        // gap closes once without any risk of overwriting explicit ERM
        // assignments. Future activations remain covered by the runtime
        // backstop in InternActivationJob.
        backfillAutoLinkV2();

        // Self-healing every-boot pass. V1 + V2 are migration_log-gated
        // and only ran once each, so an intern activated AFTER both
        // backfills but BEFORE DEFAULT_TRAINER_EMAIL /
        // DEFAULT_EVALUATOR_EMAIL resolved still has null links forever.
        // This pass runs unconditionally on every boot, idempotently
        // filling nulls on ACTIVE lifecycles — a no-op when nothing is
        // null, and self-heals new env-var configurations the moment
        // they land. Never overwrites explicit ERM assignments
        // (WHERE trainer_id IS NULL guarantees this).
        selfHealActiveInternAutoLinks();

        // Self-healing start-date pass. Some activation paths (admin
        // status flips, legacy data, importers) reach ACTIVE without
        // stamping started_at or hired_at, which makes the intern
        // invisible to any "active during month" roster query (those
        // predicates COALESCE the two columns and require non-null).
        // Idempotent: only touches ACTIVE rows where both columns are
        // null. Future activations are covered by InternActivationJob.
        selfHealActiveInternStartDates();

        // Job ID backfill — one-shot, idempotent. Stamps the new
        // SKZ-JOB-YYYY-NNNNNN job_id on any existing job_postings row
        // missing one. Future postings are stamped at creation via
        // JobIdGenerator.
        backfillJobIdV1();

        // Manager hire-approval gate columns + one-shot backfill.
        // The new model lets the Manager (not the ERM) approve a hire;
        // ERM submits the scorecard, Manager flips
        // managerHireDecision PENDING → APPROVED/REJECTED to gate the
        // existing SELECTED → ack → offer chain.
        ensureManagerHireDecisionColumns();

        // ERM Pass 2 columns:
        //   document_tasks.downloaded_by_id  — verify-after-download gate
        //   intern_lifecycles.joining_date   — ERM-set activation switch
        // Both nullable, both idempotent ADD COLUMN IF NOT EXISTS.
        ensureErmPass2Columns();

        // Mail bridge Phase 1 — additive columns on users to track the
        // intern → employee mailbox handover. Nothing reads or writes
        // these yet; the column floor lets later phases ship without a
        // separate schema patch. All idempotent ADD COLUMN IF NOT EXISTS.
        ensureMailBridgeColumns();

        // Scorecard overall_recommendation — collapsed from the legacy
        // 4-bucket set (STRONG_HIRE / HIRE / NO_HIRE / STRONG_NO_HIRE)
        // to the 3-bucket Hire/Reject/Hold to mirror the binding manager
        // decision (still independent — overall_recommendation is purely
        // advisory). Idempotent one-shot UPDATE; rows already on the new
        // values are no-ops on subsequent boots.
        remapOverallRecommendation();
    }

    /**
     * One-shot backfill of {@code intern_lifecycles.trainer_id} /
     * {@code evaluator_id} from the org-wide
     * {@code DEFAULT_TRAINER_EMAIL} / {@code DEFAULT_EVALUATOR_EMAIL}.
     *
     * <p>The auto-link runs at offer-sign + as a backstop at activation
     * via {@link com.anvicorp.api.intern.ReportingStructureAutoLinker};
     * this method patches existing active interns who slipped through
     * (env vars unset at the time their offer was signed, or default
     * user record didn't exist yet). Idempotent — only touches rows
     * where the corresponding ID is NULL, so explicit ERM assignments
     * are never overwritten. Gated by a {@code migration_log} row keyed
     * {@code BACKFILL_AUTO_LINK_V1} so the JOIN-with-users lookup only
     * fires on first boot after this fix lands.</p>
     */
    private void backfillAutoLinkV1() {
        backfillAutoLinkUnderKey("BACKFILL_AUTO_LINK_V1");
    }

    /**
     * Re-run the V1 auto-link sweep under a fresh migration_log key.
     *
     * <p>V1 wrote its migration_log row unconditionally on first boot
     * even when {@code DEFAULT_TRAINER_EMAIL} / {@code
     * DEFAULT_EVALUATOR_EMAIL} were unset, so any intern that activated
     * between the V1 run and the env vars being configured kept a NULL
     * {@code trainer_id} / {@code evaluator_id} forever. That null is
     * what produced the Trainer "Intern is not in your roster." 403 on
     * the project-assign + slot-status endpoints (the
     * {@link com.anvicorp.api.trainer.TrainerScopeGuard} fallback
     * masks it but durable data is preferable — the dashboard counters
     * and assignment audits expect a real {@code trainer_id} on every
     * active lifecycle). V2 reuses the same fill-nulls-only update,
     * never overwriting explicit ERM assignments. Idempotent — gated
     * by a separate {@code BACKFILL_AUTO_LINK_V2} migration_log key.</p>
     */
    private void backfillAutoLinkV2() {
        backfillAutoLinkUnderKey("BACKFILL_AUTO_LINK_V2");
    }

    /**
     * Self-healing auto-link pass for ACTIVE interns. Runs on every boot;
     * fills nulls on {@code intern_lifecycles.trainer_id /
     * evaluator_id} where active_status = 'ACTIVE'. Idempotent — no-op
     * once nothing is null. Never overwrites a non-null assignment
     * (the WHERE … IS NULL guarantees ERM's explicit assignments are
     * preserved).
     *
     * <p>Reason this is unconditional (no migration_log gate): V1 + V2
     * each only run ONCE per database. An intern activated after both
     * gates closed, but before DEFAULT_TRAINER_EMAIL /
     * DEFAULT_EVALUATOR_EMAIL resolved, keeps null links forever — and
     * with null links they're invisible on the Trainer/Evaluator
     * rosters and show an incomplete "Your team" on the intern
     * dashboard. This pass closes that gap the moment the env vars
     * resolve, on the next deploy.</p>
     */
    private void selfHealActiveInternAutoLinks() {
        java.util.UUID trainerUserId = resolveEmailToUserId(defaultTrainerEmail, "trainer");
        java.util.UUID evaluatorUserId = resolveEmailToUserId(defaultEvaluatorEmail, "evaluator");

        if (trainerUserId == null && evaluatorUserId == null) {
            // Both unconfigured / unresolvable — log once at WARN so it
            // surfaces on every boot until configured.
            log.warn("[SchemaFixup] self-heal auto-link: BOTH "
                    + "DEFAULT_TRAINER_EMAIL and DEFAULT_EVALUATOR_EMAIL "
                    + "unset or unresolvable. ACTIVE interns will have "
                    + "null trainer_id/evaluator_id until configured.");
            return;
        }

        int trainersFilled = 0;
        int evaluatorsFilled = 0;
        if (trainerUserId != null) {
            try {
                trainersFilled = jdbcTemplate.update(
                        "UPDATE intern_lifecycles "
                                + "   SET trainer_id = ? "
                                + " WHERE active_status = 'ACTIVE' "
                                + "   AND trainer_id IS NULL",
                        trainerUserId);
            } catch (Exception e) {
                log.warn("[SchemaFixup] self-heal trainer_id failed: {}", e.getMessage());
            }
        }
        if (evaluatorUserId != null) {
            try {
                evaluatorsFilled = jdbcTemplate.update(
                        "UPDATE intern_lifecycles "
                                + "   SET evaluator_id = ? "
                                + " WHERE active_status = 'ACTIVE' "
                                + "   AND evaluator_id IS NULL",
                        evaluatorUserId);
            } catch (Exception e) {
                log.warn("[SchemaFixup] self-heal evaluator_id failed: {}", e.getMessage());
            }
        }
        try {
            int structureCompleted = jdbcTemplate.update(
                    "UPDATE intern_lifecycles "
                            + "   SET reporting_structure_complete = TRUE, "
                            + "       reporting_structure_completed_at = NOW() "
                            + " WHERE active_status = 'ACTIVE' "
                            + "   AND trainer_id IS NOT NULL "
                            + "   AND evaluator_id IS NOT NULL "
                            + "   AND (reporting_structure_complete IS NULL "
                            + "        OR reporting_structure_complete = FALSE)");
            if (trainersFilled > 0 || evaluatorsFilled > 0 || structureCompleted > 0) {
                log.info("[SchemaFixup] self-heal auto-link: trainer_id={} "
                                + "evaluator_id={} structure_complete={}",
                        trainersFilled, evaluatorsFilled, structureCompleted);
            } else {
                log.debug("[SchemaFixup] self-heal auto-link: all ACTIVE "
                        + "lifecycles already linked (no-op).");
            }
        } catch (Exception e) {
            log.warn("[SchemaFixup] self-heal structure-complete flip failed: {}",
                    e.getMessage());
        }
    }

    /**
     * Stamps {@code started_at = NOW()} on ACTIVE intern_lifecycles
     * where both {@code started_at} and {@code hired_at} are null.
     * Without this, the Trainer / Manager / ERM "Active Interns"
     * roster — which scopes by
     * {@code COALESCE(started_at, hired_at)} — silently hides the row
     * for the current month. Idempotent: no-op once nothing matches.
     */
    private void selfHealActiveInternStartDates() {
        try {
            int filled = jdbcTemplate.update(
                    "UPDATE intern_lifecycles "
                            + "   SET started_at = NOW() "
                            + " WHERE active_status = 'ACTIVE' "
                            + "   AND started_at IS NULL "
                            + "   AND hired_at IS NULL");
            if (filled > 0) {
                log.info("[SchemaFixup] self-heal start-date: stamped "
                        + "started_at on {} ACTIVE lifecycle(s) with "
                        + "null start + hire dates.", filled);
            } else {
                log.debug("[SchemaFixup] self-heal start-date: all ACTIVE "
                        + "lifecycles have a start date (no-op).");
            }
        } catch (Exception e) {
            log.warn("[SchemaFixup] self-heal start-date failed (non-fatal): {}",
                    e.getMessage());
        }
    }

    /**
     * Manager hire-approval gate (Phase: ERM-decision → Manager-decision).
     * Adds the 4 columns the Interview entity now requires + a one-shot
     * backfill that maps existing decisions onto the new field so
     * in-flight applications don't freeze:
     * <ul>
     *   <li>decision = SELECTED → managerHireDecision = APPROVED
     *       (treated as auto-approved by the historical ERM authority);
     *       the in-flight SELECTED → ack → offer flow continues
     *       uninterrupted.</li>
     *   <li>decision = REJECTED → managerHireDecision = REJECTED
     *       (mirrors the recorded outcome).</li>
     *   <li>decision = HOLD or NULL → managerHireDecision = NULL,
     *       which the new gate treats as PENDING; a Manager must now
     *       triage these explicitly.</li>
     * </ul>
     * Idempotent: ADD COLUMN IF NOT EXISTS + the UPDATE only touches
     * rows where managerHireDecision is still null.
     */
    /**
     * ERM Pass 2 — two new nullable columns gated server-side.
     * <ul>
     *   <li>{@code document_tasks.downloaded_by_id} — stamped together
     *       with the existing {@code last_downloaded_at} +
     *       {@code download_count} when the ERM hits the per-task
     *       download endpoint. The Pass 2 verify-after-download gate
     *       rejects an ACCEPT decision when {@code last_downloaded_at}
     *       is null, so a fresh deploy with the existing columns +
     *       this new {@code downloaded_by_id} can enforce the rule
     *       without a data migration.</li>
     *   <li>{@code intern_lifecycles.joining_date} — ERM-set DATE,
     *       distinct from the offer's {@code tentative_start_date}.
     *       {@link com.anvicorp.api.intern.InternActivationJob}
     *       requires this to be non-null AND {@code <= today} before
     *       it flips the lifecycle to {@code ACTIVE_INTERN}.</li>
     * </ul>
     * Both ALTERs are idempotent ADD COLUMN IF NOT EXISTS.
     */
    private void ensureErmPass2Columns() {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE document_tasks ADD COLUMN IF NOT EXISTS "
                            + "downloaded_by_id UUID");
            log.debug("[SchemaFixup] ensured document_tasks.downloaded_by_id");
        } catch (Exception e) {
            log.warn("[SchemaFixup] add document_tasks.downloaded_by_id "
                    + "failed (non-fatal): {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS "
                            + "joining_date DATE");
            log.debug("[SchemaFixup] ensured intern_lifecycles.joining_date");
        } catch (Exception e) {
            log.warn("[SchemaFixup] add intern_lifecycles.joining_date "
                    + "failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Mail bridge Phase 1 — additive columns on {@code users} for the
     * intern → employee mailbox handover. Nothing reads or writes these
     * yet; the column floor lets the dispatcher land in a later phase
     * without a separate schema patch. All four are idempotent
     * {@code ADD COLUMN IF NOT EXISTS}.
     *
     * <ul>
     *   <li>{@code mail_account_id UUID} — one-way link to
     *       {@code mail_accounts.id}; no FK constraint.</li>
     *   <li>{@code mail_handover_state VARCHAR(24) NOT NULL DEFAULT 'PERSONAL'}
     *       — mirrors {@link com.anvicorp.api.enums.MailHandoverState}.
     *       The DEFAULT lets existing rows backfill cleanly when the column
     *       is first added (same trick as {@code users.active} +
     *       {@code users.email_verified}).</li>
     *   <li>{@code mail_handover_at TIMESTAMPTZ} — flip timestamp.</li>
     *   <li>{@code personal_email VARCHAR(255)} — archive of the original
     *       Gmail once login email is swapped to the company address.</li>
     * </ul>
     */
    private void ensureMailBridgeColumns() {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                            + "mail_account_id UUID");
            log.debug("[SchemaFixup] ensured users.mail_account_id");
        } catch (Exception e) {
            log.warn("[SchemaFixup] add users.mail_account_id "
                    + "failed (non-fatal): {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                            + "mail_handover_state VARCHAR(24) NOT NULL "
                            + "DEFAULT 'PERSONAL'");
            log.debug("[SchemaFixup] ensured users.mail_handover_state");
        } catch (Exception e) {
            log.warn("[SchemaFixup] add users.mail_handover_state "
                    + "failed (non-fatal): {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                            + "mail_handover_at TIMESTAMPTZ");
            log.debug("[SchemaFixup] ensured users.mail_handover_at");
        } catch (Exception e) {
            log.warn("[SchemaFixup] add users.mail_handover_at "
                    + "failed (non-fatal): {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                            + "personal_email VARCHAR(255)");
            log.debug("[SchemaFixup] ensured users.personal_email");
        } catch (Exception e) {
            log.warn("[SchemaFixup] add users.personal_email "
                    + "failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * One-shot remap of {@code interviews.overall_recommendation} from
     * the legacy four-bucket set to the new {@code HIRE | REJECT | HOLD}
     * tri-state. Idempotent — subsequent boots find zero matching rows
     * and the {@code UPDATE} returns 0. No CHECK constraint to rebuild
     * (the column is a plain {@code VARCHAR(20)}); the application-side
     * validator in {@code ErmInterviewService} is the source of truth.
     *
     * <p>Mapping:</p>
     * <ul>
     *   <li>STRONG_HIRE      → HIRE</li>
     *   <li>NO_HIRE          → REJECT</li>
     *   <li>STRONG_NO_HIRE   → REJECT</li>
     * </ul>
     * HIRE rows stay HIRE; HOLD rows (none today) stay HOLD.
     */
    private void remapOverallRecommendation() {
        try {
            int hire = jdbcTemplate.update(
                    "UPDATE interviews SET overall_recommendation = 'HIRE' "
                            + "WHERE overall_recommendation = 'STRONG_HIRE'");
            int reject1 = jdbcTemplate.update(
                    "UPDATE interviews SET overall_recommendation = 'REJECT' "
                            + "WHERE overall_recommendation = 'NO_HIRE'");
            int reject2 = jdbcTemplate.update(
                    "UPDATE interviews SET overall_recommendation = 'REJECT' "
                            + "WHERE overall_recommendation = 'STRONG_NO_HIRE'");
            if (hire + reject1 + reject2 > 0) {
                log.info("[SchemaFixup] overall_recommendation remap: "
                                + "STRONG_HIRE→HIRE={}, NO_HIRE→REJECT={}, "
                                + "STRONG_NO_HIRE→REJECT={}",
                        hire, reject1, reject2);
            } else {
                log.debug("[SchemaFixup] overall_recommendation remap: no-op "
                        + "(no legacy values present).");
            }
        } catch (Exception e) {
            log.warn("[SchemaFixup] overall_recommendation remap failed "
                    + "(non-fatal): {}", e.getMessage());
        }
    }

    private void ensureManagerHireDecisionColumns() {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS "
                            + "manager_hire_decision VARCHAR(20)");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS "
                            + "manager_hire_decision_at TIMESTAMPTZ");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS "
                            + "manager_hire_decision_by_id UUID");
            jdbcTemplate.execute(
                    "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS "
                            + "manager_hire_decision_note TEXT");
        } catch (Exception e) {
            log.warn("[SchemaFixup] manager_hire_decision columns add failed "
                    + "(non-fatal): {}", e.getMessage());
            return;
        }
        try {
            int approved = jdbcTemplate.update(
                    "UPDATE interviews SET manager_hire_decision = 'APPROVED', "
                            + "                  manager_hire_decision_at = COALESCE("
                            + "                      feedback_submitted_at, updated_at, NOW()) "
                            + " WHERE manager_hire_decision IS NULL "
                            + "   AND UPPER(decision) = 'SELECTED'");
            int rejected = jdbcTemplate.update(
                    "UPDATE interviews SET manager_hire_decision = 'REJECTED', "
                            + "                  manager_hire_decision_at = COALESCE("
                            + "                      feedback_submitted_at, updated_at, NOW()) "
                            + " WHERE manager_hire_decision IS NULL "
                            + "   AND UPPER(decision) = 'REJECTED'");
            if (approved > 0 || rejected > 0) {
                log.info("[SchemaFixup] manager_hire_decision backfill: "
                        + "approved={} rejected={} (HOLD/null rows left "
                        + "pending for explicit manager triage)",
                        approved, rejected);
            } else {
                log.debug("[SchemaFixup] manager_hire_decision backfill: "
                        + "no rows needed mapping (no-op).");
            }
        } catch (Exception e) {
            log.warn("[SchemaFixup] manager_hire_decision backfill failed "
                    + "(non-fatal): {}", e.getMessage());
        }
    }

    private void backfillAutoLinkUnderKey(final String migKey) {
        try {
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM migration_log WHERE migration_key = ?",
                    Integer.class, migKey);
            if (existing != null && existing > 0) {
                log.info("[SchemaFixup] {} already applied — skip", migKey);
                return;
            }
        } catch (Exception e) {
            log.warn("[SchemaFixup] migration_log lookup failed for {} "
                    + "(non-fatal, continuing): {}", migKey, e.getMessage());
        }

        java.util.UUID trainerUserId = resolveEmailToUserId(defaultTrainerEmail, "trainer");
        java.util.UUID evaluatorUserId = resolveEmailToUserId(defaultEvaluatorEmail, "evaluator");

        int trainersBackfilled = 0;
        int evaluatorsBackfilled = 0;
        int structureCompleted = 0;

        if (trainerUserId != null) {
            try {
                trainersBackfilled = jdbcTemplate.update(
                        "UPDATE intern_lifecycles "
                                + "   SET trainer_id = ? "
                                + " WHERE active_status = 'ACTIVE' "
                                + "   AND trainer_id IS NULL",
                        trainerUserId);
                log.info("[SchemaFixup] {} — backfilled trainer_id on {} active lifecycle(s)",
                        migKey, trainersBackfilled);
            } catch (Exception e) {
                log.warn("[SchemaFixup] {} trainer backfill failed (non-fatal): {}",
                        migKey, e.getMessage());
            }
        } else {
            log.info("[SchemaFixup] {} — skipping trainer backfill "
                    + "(DEFAULT_TRAINER_EMAIL unset or unresolved)", migKey);
        }

        if (evaluatorUserId != null) {
            try {
                evaluatorsBackfilled = jdbcTemplate.update(
                        "UPDATE intern_lifecycles "
                                + "   SET evaluator_id = ? "
                                + " WHERE active_status = 'ACTIVE' "
                                + "   AND evaluator_id IS NULL",
                        evaluatorUserId);
                log.info("[SchemaFixup] {} — backfilled evaluator_id on {} active lifecycle(s)",
                        migKey, evaluatorsBackfilled);
            } catch (Exception e) {
                log.warn("[SchemaFixup] {} evaluator backfill failed (non-fatal): {}",
                        migKey, e.getMessage());
            }
        } else {
            log.info("[SchemaFixup] {} — skipping evaluator backfill "
                    + "(DEFAULT_EVALUATOR_EMAIL unset or unresolved)", migKey);
        }

        // If both fields are now non-null and the structure-complete flag
        // wasn't already set, flip it. Mirrors the runtime linker behavior.
        try {
            structureCompleted = jdbcTemplate.update(
                    "UPDATE intern_lifecycles "
                            + "   SET reporting_structure_complete = TRUE, "
                            + "       reporting_structure_completed_at = NOW() "
                            + " WHERE active_status = 'ACTIVE' "
                            + "   AND trainer_id IS NOT NULL "
                            + "   AND evaluator_id IS NOT NULL "
                            + "   AND (reporting_structure_complete IS NULL "
                            + "        OR reporting_structure_complete = FALSE)");
            if (structureCompleted > 0) {
                log.info("[SchemaFixup] {} — flipped reporting_structure_complete=TRUE "
                        + "on {} active lifecycle(s)", migKey, structureCompleted);
            }
        } catch (Exception e) {
            log.warn("[SchemaFixup] {} reporting_structure_complete flip failed: {}",
                    migKey, e.getMessage());
        }

        int totalTouched = trainersBackfilled + evaluatorsBackfilled;
        try {
            jdbcTemplate.update(
                    "INSERT INTO migration_log "
                            + "(id, migration_key, executed_at, rows_migrated, notes) "
                            + "VALUES (?, ?, NOW(), ?, ?) "
                            + "ON CONFLICT (migration_key) DO NOTHING",
                    java.util.UUID.randomUUID(), migKey, totalTouched,
                    "trainer_id: " + trainersBackfilled
                            + ", evaluator_id: " + evaluatorsBackfilled
                            + ", structure_complete: " + structureCompleted);
        } catch (Exception e) {
            log.warn("[SchemaFixup] insert migration_log({}) skipped (non-fatal): {}",
                    migKey, e.getMessage());
        }
    }

    /**
     * One-shot backfill — stamp {@code job_postings.job_id} on every row
     * missing one. Future postings are stamped at creation via
     * {@link com.anvicorp.api.service.JobIdGenerator}; this method just
     * fills the gap for rows that predate the column. Idempotent + gated
     * by a {@code migration_log} row keyed {@code BACKFILL_JOB_ID_V1};
     * existing job_id values are never touched.
     *
     * <p>Uses {@code nextval('skyzen_job_seq')} for each missing row so
     * the suffix is monotonic and collision-free even under concurrent
     * boots. The unique index is added in a separate try block so an
     * unexpected partial backfill (e.g. some rows already had IDs from a
     * prior partial run) doesn't crash startup.</p>
     */
    private void backfillJobIdV1() {
        final String migKey = "BACKFILL_JOB_ID_V1";
        try {
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM migration_log WHERE migration_key = ?",
                    Integer.class, migKey);
            if (existing != null && existing > 0) {
                log.info("[SchemaFixup] {} already applied — skip", migKey);
                return;
            }
        } catch (Exception e) {
            log.warn("[SchemaFixup] migration_log lookup failed for {} "
                    + "(non-fatal, continuing): {}", migKey, e.getMessage());
        }

        int backfilled = 0;
        try {
            // Per-row UPDATE so each NULL gets its own nextval() draw;
            // a single bulk UPDATE would reuse one sequence value across
            // multiple rows in some Postgres versions.
            backfilled = jdbcTemplate.update(
                    "UPDATE job_postings "
                            + "   SET job_id = 'ANVI-JOB-' "
                            + "       || EXTRACT(YEAR FROM created_at)::int "
                            + "       || '-' "
                            + "       || LPAD(nextval('skyzen_job_seq')::text, 6, '0') "
                            + " WHERE job_id IS NULL");
            log.info("[SchemaFixup] {} — stamped job_id on {} job_postings row(s)",
                    migKey, backfilled);
        } catch (Exception e) {
            log.warn("[SchemaFixup] {} backfill failed (non-fatal): {}",
                    migKey, e.getMessage());
        }

        try {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uk_job_postings_job_id "
                            + "  ON job_postings(job_id)");
        } catch (Exception e) {
            log.warn("[SchemaFixup] {} unique index add failed (non-fatal): {}",
                    migKey, e.getMessage());
        }

        try {
            jdbcTemplate.update(
                    "INSERT INTO migration_log "
                            + "(id, migration_key, executed_at, rows_migrated, notes) "
                            + "VALUES (?, ?, NOW(), ?, ?) "
                            + "ON CONFLICT (migration_key) DO NOTHING",
                    java.util.UUID.randomUUID(), migKey, backfilled,
                    "Stamped SKZ-JOB-YYYY-NNNNNN on " + backfilled
                            + " pre-existing job_postings row(s).");
        } catch (Exception e) {
            log.warn("[SchemaFixup] insert migration_log({}) skipped (non-fatal): {}",
                    migKey, e.getMessage());
        }
    }

    private java.util.UUID resolveEmailToUserId(String email, String label) {
        if (email == null || email.isBlank()) return null;
        try {
            java.util.List<java.util.Map<String, Object>> rows =
                    jdbcTemplate.queryForList(
                            "SELECT id FROM users WHERE email = ? LIMIT 1",
                            email.trim());
            if (rows.isEmpty()) {
                log.info("[SchemaFixup] BACKFILL_AUTO_LINK_V1 — {} email '{}' "
                        + "did not resolve to a user", label, email);
                return null;
            }
            Object id = rows.get(0).get("id");
            return id == null ? null : java.util.UUID.fromString(id.toString());
        } catch (Exception e) {
            log.warn("[SchemaFixup] BACKFILL_AUTO_LINK_V1 — {} lookup failed: {}",
                    label, e.getMessage());
            return null;
        }
    }

    /**
     * Phase 8.6.3 — realign every enum-backed CHECK constraint with its
     * current Java enum value set. Hibernate {@code ddl-auto=update} does
     * not refresh CHECK constraints when an enum grows, so old constraints
     * silently reject new values — most visibly blocking
     * {@code offer.status = SIGNED}, which leaked employee_id sequence
     * values on each retry before this sweep landed.
     *
     * <p>Idempotent + gated by a {@code migration_log} row keyed
     * {@code ALIGN_ENUM_CHECK_CONSTRAINTS_V1}. Detection-before-action:
     * for each (table, column) pair we query {@code pg_constraint}, and
     * if any existing CHECK already contains every required value the
     * constraint is left alone. Otherwise we drop the existing
     * whitelist-style CHECKs on the column and add a fresh
     * {@code {table}_{column}_check} with the full enum.
     *
     * <p>NOT-NULL and length CHECKs are left intact (heuristic: only
     * drop constraints whose definition contains a single-quoted token,
     * which is how Postgres prints {@code CHECK (col IN ('A','B'))}).
     *
     * <p>If a future phase grows another enum, bump to V2 by adding a
     * new method + new {@code migration_log} key. Don't append values
     * here — the gate would skip on already-aligned databases.
     */
    private void alignEnumCheckConstraintsV1() {
        final String migKey = "ALIGN_ENUM_CHECK_CONSTRAINTS_V1";
        try {
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM migration_log WHERE migration_key = ?",
                    Integer.class, migKey);
            if (existing != null && existing > 0) {
                log.info("[SchemaFixup] {} already applied — skip", migKey);
                return;
            }
        } catch (Exception e) {
            // migration_log table may not exist on a brand-new DB; the
            // ensureErmPhase8Schema() call above should have created it.
            // Continue defensively — worst case the alignment runs again.
            log.warn("[SchemaFixup] migration_log lookup failed for {} "
                    + "(non-fatal, continuing): {}", migKey, e.getMessage());
        }

        record ConstraintSpec(String table, String column, List<String> values) {}
        List<ConstraintSpec> specs = List.of(
                new ConstraintSpec("offers", "status", List.of(
                        "DRAFT", "SENT", "SIGNED", "VOIDED", "EXPIRED",
                        "DECLINED", "ACCEPTED", "REVOKED")),
                new ConstraintSpec("applications", "status", List.of(
                        "APPLIED", "HOLD", "INFO_REQUESTED",
                        "SCREENING_SENT", "SCREENING_COMPLETED", "SHORTLISTED",
                        "INTERVIEW_SCHEDULED", "INTERVIEWED",
                        "SELECTED_CONDITIONAL", "OFFERED", "ACCEPTED",
                        "ONBOARDING", "ACTIVE", "HIRED", "COMPLETED",
                        "REJECTED", "WITHDRAWN", "LAPSED", "NO_SHOW")),
                new ConstraintSpec("users", "lifecycle_status", List.of(
                        "REGISTERED", "EMAIL_VERIFIED", "APPLICATION_SUBMITTED",
                        "SHORTLISTED", "INTERVIEW_SCHEDULED", "INTERVIEW_COMPLETED",
                        "OFFER_SENT", "OFFER_SIGNED", "EMPLOYEE_ID_CREATED",
                        "ONBOARDING_ASSIGNED", "ONBOARDING_ACCEPTED",
                        "ACTIVE_INTERN", "INACTIVE_INTERN")),
                new ConstraintSpec("interviews", "status", List.of(
                        "SCHEDULED", "COMPLETED", "CANCELLED", "NO_SHOW")),
                new ConstraintSpec("interviews", "type", List.of(
                        "INITIAL_SCREEN", "TECHNICAL", "BEHAVIORAL",
                        "CULTURE_FIT", "FINAL_ROUND")),
                new ConstraintSpec("interviews", "decision", List.of(
                        "SELECTED", "HOLD", "REJECTED")),
                new ConstraintSpec("intern_lifecycles", "active_status", List.of(
                        "PROSPECTIVE", "ACTIVE", "INACTIVE")),
                new ConstraintSpec("projects", "status", List.of(
                        "NOT_STARTED", "IN_PROGRESS", "SUBMITTED", "RETURNED",
                        "TECH_APPROVED", "PENDING_VIVA", "COMPLETED")),
                new ConstraintSpec("engagements", "status", List.of(
                        "PENDING_COMPLIANCE", "READY_TO_START", "ACTIVE",
                        "COMPLETED", "TERMINATED", "BLOCKED_NO_AUTHORIZATION")),
                new ConstraintSpec("document_packets", "status", List.of(
                        "DRAFT", "ASSIGNED", "IN_PROGRESS", "ALL_SUBMITTED",
                        "COMPLETED", "CANCELLED")),
                new ConstraintSpec("document_tasks", "status", List.of(
                        "PENDING", "SUBMITTED", "UNDER_REVIEW", "ACCEPTED",
                        "REJECTED", "RESEND_REQUESTED", "WAIVED")),
                new ConstraintSpec("weekly_meetings", "status", List.of(
                        "SCHEDULED", "COMPLETED", "CANCELLED", "NO_SHOW")),
                new ConstraintSpec("project_submissions", "trainer_decision", List.of(
                        "ACCEPT", "REQUEST_REVISION", "ESCALATE", "NO_ACTION_YET"))
        );

        int touched = 0;
        int skipped = 0;
        int missing = 0;
        for (ConstraintSpec s : specs) {
            try {
                Boolean exists = jdbcTemplate.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM information_schema.columns "
                                + "WHERE table_name = ? AND column_name = ?)",
                        Boolean.class, s.table(), s.column());
                if (exists == null || !exists) {
                    log.info("[SchemaFixup] skip {}.{} — column not present yet",
                            s.table(), s.column());
                    missing++;
                    continue;
                }

                List<java.util.Map<String, Object>> constraints =
                        jdbcTemplate.queryForList(
                                "SELECT con.conname AS name, "
                                        + "pg_get_constraintdef(con.oid) AS def "
                                        + "FROM pg_constraint con "
                                        + "JOIN pg_class cl ON cl.oid = con.conrelid "
                                        + "JOIN pg_attribute att "
                                        + "  ON att.attrelid = cl.oid "
                                        + "  AND att.attnum = ANY(con.conkey) "
                                        + "WHERE cl.relname = ? AND att.attname = ? "
                                        + "  AND con.contype = 'c'",
                                s.table(), s.column());

                boolean aligned = constraints.stream().anyMatch(c -> {
                    String def = String.valueOf(c.get("def"));
                    return s.values().stream().allMatch(
                            v -> def.contains("'" + v + "'"));
                });
                if (aligned) {
                    log.info("[SchemaFixup] {}.{} already aligned — skip",
                            s.table(), s.column());
                    skipped++;
                    continue;
                }

                String before = constraints.stream()
                        .map(c -> c.get("name") + " => " + c.get("def"))
                        .reduce((a, b) -> a + " | " + b)
                        .orElse("(none)");

                for (java.util.Map<String, Object> c : constraints) {
                    String name = String.valueOf(c.get("name"));
                    String def = String.valueOf(c.get("def"));
                    // Heuristic: only drop value-whitelist constraints
                    // (definition contains a single-quoted token). Skip
                    // NOT NULL, length, FK-style references.
                    if (!def.contains("'")) continue;
                    try {
                        jdbcTemplate.execute(
                                "ALTER TABLE " + s.table()
                                        + " DROP CONSTRAINT IF EXISTS " + name);
                    } catch (Exception e) {
                        log.warn("[SchemaFixup] drop {}.{} ({}) failed: {}",
                                s.table(), s.column(), name, e.getMessage());
                    }
                }

                String inList = s.values().stream()
                        .map(v -> "'" + v + "'")
                        .reduce((a, b) -> a + "," + b)
                        .orElse("''");
                String newName = s.table() + "_" + s.column() + "_check";
                jdbcTemplate.execute(
                        "ALTER TABLE " + s.table()
                                + " ADD CONSTRAINT " + newName
                                + " CHECK (" + s.column()
                                + " IN (" + inList + "))");

                log.info("[SchemaFixup] Aligning {}.{} CHECK constraint: "
                                + "was [{}], now IN ({})",
                        s.table(), s.column(), before, inList);
                touched++;
            } catch (Exception e) {
                log.warn("[SchemaFixup] align {}.{} failed (non-fatal): {}",
                        s.table(), s.column(), e.getMessage());
            }
        }

        log.info("[SchemaFixup] Aligned {} enum CHECK constraints "
                        + "(skipped {} already-aligned, {} missing tables)",
                touched, skipped, missing);

        try {
            jdbcTemplate.update(
                    "INSERT INTO migration_log "
                            + "(id, migration_key, executed_at, rows_migrated, notes) "
                            + "VALUES (?, ?, NOW(), ?, ?) "
                            + "ON CONFLICT (migration_key) DO NOTHING",
                    java.util.UUID.randomUUID(), migKey, touched,
                    "Aligned " + touched + " constraints, skipped " + skipped
                            + " already-aligned, " + missing + " missing tables.");
        } catch (Exception e) {
            log.warn("[SchemaFixup] insert migration_log({}) skipped (non-fatal): {}",
                    migKey, e.getMessage());
        }
    }

    /**
     * Re-sync the {@code document_tasks.document_key} CHECK constraint
     * with the current {@link SkyzenDocument} enum value list. Runs on
     * every boot — NOT migration_log-gated — because the enum is still
     * growing (Phase E added 10 upload-only docs; future phases will
     * likely add more). Idempotent: if the existing constraint already
     * contains every current enum value, no-op.
     *
     * <p>Background: Hibernate auto-generates a CHECK constraint with
     * the enum values that exist when the column is first created.
     * {@code spring.jpa.hibernate.ddl-auto=update} does NOT refresh
     * existing CHECK constraints when the Java enum grows — so
     * inserting a PASSPORT_FRONT row hits a 23514
     * {@code check_violation}, which the assignPacket flow surfaces as
     * "Data integrity violation". This sync drops the stale constraint
     * and recreates it with the full current value list so every
     * SkyzenDocument value is accepted.</p>
     *
     * <p>Longer-term: the right call is probably to drop the CHECK
     * entirely and rely on application-layer enforcement
     * (Hibernate's @Enumerated(STRING) only ever writes a valid value;
     * out-of-band raw SQL is the only path to a bad row). Tracked as
     * a follow-up — for now we keep the safety net.</p>
     */
    private void syncDocumentKeyCheckConstraint() {
        final String table = "document_tasks";
        final String column = "document_key";
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM information_schema.columns "
                            + "WHERE table_name = ? AND column_name = ?)",
                    Boolean.class, table, column);
            if (exists == null || !exists) {
                log.info("[SchemaFixup] skip {}.{} — column not present yet",
                        table, column);
                return;
            }

            List<String> currentValues = java.util.Arrays
                    .stream(SkyzenDocument.values())
                    .map(Enum::name)
                    .toList();

            List<Map<String, Object>> constraints = jdbcTemplate.queryForList(
                    "SELECT con.conname AS name, "
                            + "pg_get_constraintdef(con.oid) AS def "
                            + "FROM pg_constraint con "
                            + "JOIN pg_class cl ON cl.oid = con.conrelid "
                            + "JOIN pg_attribute att "
                            + "  ON att.attrelid = cl.oid "
                            + "  AND att.attnum = ANY(con.conkey) "
                            + "WHERE cl.relname = ? AND att.attname = ? "
                            + "  AND con.contype = 'c'",
                    table, column);

            boolean aligned = constraints.stream().anyMatch(c -> {
                String def = String.valueOf(c.get("def"));
                return currentValues.stream().allMatch(
                        v -> def.contains("'" + v + "'"));
            });
            if (aligned) {
                log.debug("[SchemaFixup] {}.{} CHECK already covers every "
                                + "SkyzenDocument value — skip", table, column);
                return;
            }

            String before = constraints.stream()
                    .map(c -> c.get("name") + " => " + c.get("def"))
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("(none)");

            for (Map<String, Object> c : constraints) {
                String name = String.valueOf(c.get("name"));
                String def = String.valueOf(c.get("def"));
                // Same heuristic as V1 — only drop value-whitelist
                // constraints (definition contains a single-quoted
                // token). NOT NULL / length / FK constraints are
                // left intact.
                if (!def.contains("'")) continue;
                try {
                    jdbcTemplate.execute(
                            "ALTER TABLE " + table
                                    + " DROP CONSTRAINT IF EXISTS " + name);
                } catch (Exception e) {
                    log.warn("[SchemaFixup] drop {}.{} ({}) failed: {}",
                            table, column, name, e.getMessage());
                }
            }

            String inList = currentValues.stream()
                    .map(v -> "'" + v + "'")
                    .reduce((a, b) -> a + "," + b)
                    .orElse("''");
            String newName = table + "_" + column + "_check";
            jdbcTemplate.execute(
                    "ALTER TABLE " + table
                            + " ADD CONSTRAINT " + newName
                            + " CHECK (" + column
                            + " IN (" + inList + "))");

            log.info("[SchemaFixup] Re-synced {}.{} CHECK constraint to "
                            + "{} SkyzenDocument values. Previous: [{}]",
                    table, column, currentValues.size(), before);
        } catch (Exception e) {
            log.warn("[SchemaFixup] sync {}.{} CHECK failed (non-fatal): {}",
                    table, column, e.getMessage());
        }
    }

    /**
     * Remap a legacy role string to its new-taxonomy equivalent on the given
     * table ({@code user_roles} or {@code users}). Idempotent and silent on
     * a no-op row count. Missing table / column logged at debug only.
     */
    private void applyRoleRemap(String table, String from, String to) {
        try {
            int n = jdbcTemplate.update(
                    "UPDATE " + table + " SET role = ? WHERE role = ?",
                    to, from);
            if (n > 0) {
                log.info("[SchemaFixupRunner] role rename: {} {}.role rows updated {} → {}",
                        n, table, from, to);
            }
        } catch (Exception e) {
            log.debug("[SchemaFixupRunner] role rename {}.role {} → {} skipped: {}",
                    table, from, to, e.getMessage());
        }
    }

    /**
     * Backfill {@code project_repositories} from every legacy {@code projects}
     * row whose {@code resource_links_json} contains a GitHub repo URL but
     * has no matching {@code project_repositories} row. Idempotent via
     * {@code ON CONFLICT (project_id) DO NOTHING}.
     */
    private void backfillProjectRepositoriesFromResourceLinks() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT p.id AS id, p.resource_links_json AS rl, p.assigned_by AS assigned_by "
                            + "FROM projects p "
                            + "WHERE p.resource_links_json IS NOT NULL "
                            + "  AND length(p.resource_links_json) > 2 "
                            + "  AND NOT EXISTS ("
                            + "    SELECT 1 FROM project_repositories pr "
                            + "    WHERE pr.project_id = p.id"
                            + "  )");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] repo-backfill select failed (non-fatal): {}",
                    e.getMessage());
            return;
        }
        if (rows.isEmpty()) return;

        int scanned = 0;
        int created = 0;
        int skippedNoGithub = 0;
        int skippedFailed = 0;

        for (Map<String, Object> row : rows) {
            scanned++;
            UUID projectId;
            try {
                Object idObj = row.get("id");
                projectId = idObj instanceof UUID ? (UUID) idObj
                        : UUID.fromString(idObj.toString());
            } catch (Exception e) {
                skippedFailed++;
                log.debug("[SchemaFixupRunner] repo-backfill skipped row with unparseable id: {}",
                        e.getMessage());
                continue;
            }

            String rl = row.get("rl") == null ? null : row.get("rl").toString();
            List<String> urls;
            try {
                urls = objectMapper.readValue(rl, STRING_LIST_TYPE);
            } catch (Exception e) {
                skippedFailed++;
                log.debug("[SchemaFixupRunner] repo-backfill skipped project {} — unparseable resource_links_json",
                        projectId);
                continue;
            }
            if (urls == null || urls.isEmpty()) {
                skippedNoGithub++;
                continue;
            }

            String matchedUrl = null;
            String repoName = null;
            for (String u : urls) {
                if (u == null) continue;
                String trimmed = u.trim();
                if (trimmed.isEmpty()) continue;
                Matcher m = GITHUB_REPO_URL.matcher(trimmed);
                if (m.matches()) {
                    matchedUrl = trimmed;
                    repoName = m.group(2); // no .git
                    break;
                }
            }
            if (matchedUrl == null) {
                skippedNoGithub++;
                continue;
            }

            // linked_by_id is NOT NULL on the table; fall back to a system
            // sentinel via the project's assigned_by — the TE who allocated.
            UUID linkedBy;
            try {
                Object assignedByObj = row.get("assigned_by");
                linkedBy = assignedByObj == null ? null
                        : (assignedByObj instanceof UUID ? (UUID) assignedByObj
                                : UUID.fromString(assignedByObj.toString()));
            } catch (Exception ignored) {
                linkedBy = null;
            }
            if (linkedBy == null) {
                // No reasonable fallback — the NOT NULL constraint would
                // reject the insert. Skip silently with a debug note; the
                // TE can link this row later via the catalog API.
                skippedFailed++;
                log.debug("[SchemaFixupRunner] repo-backfill skipped project {} — no assigned_by to attribute the link",
                        projectId);
                continue;
            }

            try {
                int inserted = jdbcTemplate.update(
                        "INSERT INTO project_repositories "
                                + "  (id, project_id, repository_name, repository_url, "
                                + "   linked_by_id, created_at, updated_at) "
                                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, NOW(), NOW()) "
                                + "ON CONFLICT (project_id) DO NOTHING",
                        projectId, repoName, matchedUrl, linkedBy);
                if (inserted > 0) {
                    created++;
                    log.info("[SchemaFixupRunner] repo-backfill linked project {} → {}",
                            projectId, matchedUrl);
                }
            } catch (Exception e) {
                skippedFailed++;
                log.warn("[SchemaFixupRunner] repo-backfill insert failed for project {}: {}",
                        projectId, e.getMessage());
            }
        }

        log.info("[SchemaFixupRunner] repo-backfill done. scanned={} created={} no-github-url={} failed={}",
                scanned, created, skippedNoGithub, skippedFailed);

        // Phase 8 — exit_records + exit_feedback tables. ddl-auto=update will
        // also create these via the JPA entities, but this idempotent CREATE
        // ensures the columns + indexes match the entity contract on every
        // boot path (including environments that disable ddl-auto).
        ensureExitTables();

        // ERM Phase 0 — communication_templates. Same idempotent guarantee
        // as the exit tables above; ddl-auto would also create this, but the
        // explicit CREATE keeps the contract visible in source.
        ensureCommunicationTemplatesTable();

        // ERM Phase 1 — KPI/exception query indexes. These keep the dashboard
        // call under 500ms p95 as the dataset grows. All IF NOT EXISTS.
        ensureErmDashboardIndexes();

        // ERM Phase 2 — application inbox columns + decision log table +
        // additional indexes for the inbox/shortlist queries.
        ensureErmApplicationInboxSchema();

        // Re-apply-after-withdraw — swap the legacy unconditional UNIQUE on
        // applications(candidate_id, job_posting_id) for a partial one that
        // ignores WITHDRAWN rows, so an intern who withdrew can apply again
        // while the WITHDRAWN row stays put for ERM reporting.
        ensureApplicationsReapplyAfterWithdrawSchema();

        // ERM Phase 3 — interview scheduler + decision center columns +
        // event log table + 2 indexes.
        ensureErmInterviewSchema();

        // ERM Phase 4 — offer event log + offer + intern_lifecycles ALTERs +
        // partial UNIQUE on offers(application_id) WHERE archived_at IS NULL.
        ensureErmOfferAndNewHireSchema();

        // ERM Phase 5 — onboarding review headline + onboarding_review_logs
        // + work_authorization_records + everify_cases ERM columns +
        // case_number widened to TEXT (now AES-256-GCM encrypted) +
        // compliance/queue indexes + best-effort backfill of work-auth
        // records from candidates(expected_track, validity_date).
        ensureErmOnboardingAndComplianceSchema();

        // ERM Phase 6 — persistent ExceptionRecord + ExceptionEventLog so
        // the Phase 1 on-demand detection becomes a scheduled job + the
        // Escalations queue. Partial UNIQUE keeps "at most one OPEN per
        // (intern, type)" enforced by Postgres regardless of UPSERT race.
        ensureErmExceptionSchema();

        // ERM Phase 7 — exit_records ERM operational columns + the
        // exit_checklist_items table that converts the implicit Phase 8
        // checklist into 8 auditable rows per ExitRecord.
        ensureErmExitOperationsSchema();

        // Trainer Phase 0 — strip the weekly-materials concept (not in
        // Trainer doc spec), scaffold the doc-required columns on projects
        // + project_submissions, and add ProjectTemplate +
        // ProjectAssignmentEventLog tables.
        ensureTrainerPhase0Schema();

        // Trainer Phase 1 — two indexes that keep the Home dashboard +
        // Active Interns queries sub-500ms.
        ensureTrainerPhase1Indexes();

        // ERM Phase 8 — Document Template Library + per-intern Packet
        // workflow. Creates 4 new tables + migration_log, ALTERs
        // intern_lifecycles for the simplified I-9 §2 capture, and
        // (only after OnboardingMigrationRunner posts a success row in
        // migration_log) DROPs the legacy onboarding_* tables.
        ensureErmPhase8Schema();
        dropLegacyOnboardingTablesIfMigrated();

        // ERM Phase 8.2 — strip the DocumentTemplate management layer.
        // Adds document_tasks.document_key, migrates from template_id by
        // title-match against the SkyzenDocument enum, then drops the
        // template-related columns + the document_templates table.
        // Idempotent + gated by a migration_log row.
        migrateDocumentTemplatesToStaticPdfV1();

        // Heal users whose email_verified flipped TRUE before AuthService
        // started advancing lifecycle_status. Sweeps every boot — cheap +
        // safe because the WHERE clause filters to the broken state only.
        backfillEmailVerifiedLifecycleStatus();

        // Trainer Phase 2 — lifecycle-tracked projects key off
        // intern_lifecycle_id, not the legacy (engagement_id, intern_id)
        // pair. Relax those columns to nullable so the new flow doesn't
        // require an Engagement row that may not exist for prospective
        // interns. Idempotent.
        relaxProjectLegacyFkNotNull();

        // Trainer Phase 3 — review-state columns on project_submissions
        // (version, trainer_decision, trainer_feedback, reviewed_at,
        // reviewed_by_id, completion_status). Idempotent.
        ensureTrainerPhase3SubmissionColumns();

        // Trainer Phase 4 — per-trainer preference columns on the users
        // table. Idempotent ALTERs; all nullable so existing rows survive
        // without backfill and the settings page surfaces sane defaults
        // when a user hasn't picked anything yet.
        ensureTrainerPhase4PreferenceColumns();

        // Evaluator Phase 0 — refresh user_roles CHECK to include EVALUATOR
        // and create the i983_evaluations table explicitly so the contract
        // matches the entity even on environments with ddl-auto disabled.
        migrateEvaluatorPhase0SchemaV1();

        // JOBS_ADMIN Phase 0 — refresh user_roles CHECK a second time to
        // include the new JOBS_ADMIN value. Without this, INSERT of a
        // JOBS_ADMIN role trips CHECK constraint user_roles_role_check
        // with sqlState 23514 and the app fails to seed.
        rebuildUserRolesRoleCheckForJobsAdmin();

        // Jobs Admin posting form adds a combined Full-Time / Internship
        // job_type option. ddl-auto will not refresh the existing CHECK.
        rebuildJobPostingsJobTypeCheckForCombinedType();

        // Jobs Admin owns the company/about-company text on the posting
        // itself now, so job_postings no longer requires a StaffingEntity FK.
        relaxJobPostingsEntityAndJobIdForJobsAdmin();

        // Evaluator Phase 2 — add recommendation column on intern_evaluations
        // for the monthly evaluation rubric publish flow. Idempotent.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE intern_evaluations "
                            + "ADD COLUMN IF NOT EXISTS recommendation VARCHAR(32)");
            log.info("[SchemaFixupRunner] ensured intern_evaluations.recommendation column");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] intern_evaluations.recommendation ALTER "
                    + "skipped (non-fatal): {}", e.getMessage());
        }

        // Evaluator Phase 3 — additive columns on i983_evaluations for the
        // STEM OPT workflow: per-objective notes, supervisor assessment,
        // intern typed signature + response, DSO submission method/notes,
        // version + amendment metadata. All idempotent ALTER IF NOT EXISTS.
        String[] i983Alters = {
                "ALTER TABLE i983_evaluations ADD COLUMN IF NOT EXISTS objectives_achieved TEXT",
                "ALTER TABLE i983_evaluations ADD COLUMN IF NOT EXISTS supervisor_assessment TEXT",
                "ALTER TABLE i983_evaluations ADD COLUMN IF NOT EXISTS student_typed_signature VARCHAR(200)",
                "ALTER TABLE i983_evaluations ADD COLUMN IF NOT EXISTS intern_response TEXT",
                "ALTER TABLE i983_evaluations ADD COLUMN IF NOT EXISTS dso_submission_method VARCHAR(40)",
                "ALTER TABLE i983_evaluations ADD COLUMN IF NOT EXISTS dso_submission_notes TEXT",
                "ALTER TABLE i983_evaluations ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1",
                "ALTER TABLE i983_evaluations ADD COLUMN IF NOT EXISTS amended_at TIMESTAMP",
                "ALTER TABLE i983_evaluations ADD COLUMN IF NOT EXISTS amendment_reason TEXT"
        };
        for (String sql : i983Alters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] i983_evaluations Phase 3 ALTER skipped "
                        + "(non-fatal): {} — {}", sql, e.getMessage());
            }
        }
        log.info("[SchemaFixupRunner] ensured i983_evaluations Phase 3 columns");

        // Evaluator Phase 4 — per-Evaluator preference columns on users.
        // All additive + nullable; legacy rows surface defaults from the
        // settings page client-side. Idempotent ALTER IF NOT EXISTS.
        String[] evalPrefAlters = {
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS prefs_evaluator_default_duration SMALLINT",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS prefs_evaluator_reminder_frequency VARCHAR(16)",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS prefs_evaluator_notify_acknowledged BOOLEAN",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS prefs_evaluator_notify_dso_window BOOLEAN"
        };
        for (String sql : evalPrefAlters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] Evaluator Phase 4 prefs ALTER skipped "
                        + "(non-fatal): {} — {}", sql, e.getMessage());
            }
        }
        log.info("[SchemaFixupRunner] ensured users prefs_evaluator_* columns");
    }

    /** Trainer Phase 4 — additive trainer-only preference columns on the
     *  users table. */
    private void ensureTrainerPhase4PreferenceColumns() {
        String[] alters = {
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                        + "prefs_trainer_default_recurrence VARCHAR(16)",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                        + "prefs_trainer_default_duration SMALLINT",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                        + "prefs_trainer_review_priority VARCHAR(16)",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                        + "prefs_trainer_notify_stakeholders BOOLEAN",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                        + "prefs_trainer_email_frequency VARCHAR(16)",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                        + "prefs_trainer_notify_submissions BOOLEAN",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS "
                        + "prefs_trainer_notify_escalation_resolved BOOLEAN"
        };
        for (String sql : alters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.debug("[SchemaFixupRunner] Trainer Phase 4 ALTER skipped: {} — {}",
                        sql, e.getMessage());
            }
        }
    }

    /** Trainer Phase 3 — additive ALTERs for the doc §9 4-decision review
     *  flow. Every column nullable / defaulted so existing submission rows
     *  keep working without backfill. */
    private void ensureTrainerPhase3SubmissionColumns() {
        String[] alters = {
                "ALTER TABLE project_submissions "
                        + "ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1",
                "ALTER TABLE project_submissions "
                        + "ADD COLUMN IF NOT EXISTS trainer_decision VARCHAR(20)",
                "ALTER TABLE project_submissions "
                        + "ADD COLUMN IF NOT EXISTS trainer_feedback TEXT",
                "ALTER TABLE project_submissions "
                        + "ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP",
                "ALTER TABLE project_submissions "
                        + "ADD COLUMN IF NOT EXISTS reviewed_by_id UUID",
                "ALTER TABLE project_submissions "
                        + "ADD COLUMN IF NOT EXISTS completion_status VARCHAR(24)",
                "CREATE INDEX IF NOT EXISTS idx_project_submissions_pending "
                        + "ON project_submissions (project_id) "
                        + "WHERE trainer_decision IS NULL"
        };
        for (String sql : alters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.debug("[SchemaFixupRunner] Trainer Phase 3 ALTER skipped: {} — {}",
                        sql, e.getMessage());
            }
        }
    }

    /** Trainer Phase 2 — drop NOT NULL on projects.engagement_id +
     *  projects.intern_id so lifecycle-tracked projects (which key off
     *  intern_lifecycle_id) can land without an Engagement / Candidate
     *  row. Legacy paths that still populate both keep working.
     *
     *  <p>Previously this swallowed failures at DEBUG, so a silent ALTER
     *  failure (lock, permission, transient DB error) would leave
     *  engagement_id NOT NULL in production and every trainer project
     *  assignment would 23502. We now: (1) log each ALTER attempt at INFO,
     *  (2) log failures at WARN with the exception chain, (3) post-ALTER
     *  query information_schema.columns to VERIFY the column is actually
     *  nullable, and (4) log loudly when verification disagrees with our
     *  assumptions so the next deploy operator can see the relax didn't
     *  take effect.</p>
     */
    private void relaxProjectLegacyFkNotNull() {
        String[][] columns = {
                {"projects", "engagement_id"},
                {"projects", "intern_id"}
        };
        for (String[] col : columns) {
            relaxOneNotNull(col[0], col[1]);
        }
    }

    private void relaxOneNotNull(String table, String column) {
        String sql = "ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL";
        try {
            jdbcTemplate.execute(sql);
            log.info("[SchemaFixupRunner] DROP NOT NULL succeeded on {}.{}", table, column);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] DROP NOT NULL on {}.{} FAILED: {} — root: {}",
                    table, column, e.getMessage(), rootMessage(e), e);
        }
        // Verify regardless of the ALTER's reported outcome (it could have
        // succeeded silently or failed silently). information_schema returns
        // is_nullable = 'YES'/'NO'.
        try {
            String isNullable = jdbcTemplate.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_name = ? AND column_name = ?",
                    String.class, table, column);
            if (isNullable == null) {
                log.warn("[SchemaFixupRunner] {}.{} not found in information_schema — "
                        + "cannot verify nullability", table, column);
            } else if ("YES".equalsIgnoreCase(isNullable)) {
                log.info("[SchemaFixupRunner] verified {}.{} is nullable", table, column);
            } else {
                log.error("[SchemaFixupRunner] {}.{} is STILL NOT NULL after DROP NOT NULL "
                                + "attempt — trainer project assignments will continue to "
                                + "23502 until this is resolved (check DB user privileges + "
                                + "locks on the table).",
                        table, column);
            }
        } catch (Exception verifyErr) {
            log.warn("[SchemaFixupRunner] post-ALTER verify on {}.{} failed (non-fatal): {}",
                    table, column, verifyErr.getMessage());
        }
    }

    /**
     * Phase B2 — rebuild {@code timesheets_status_check} to allow VERIFIED
     * (and the rest of the current {@link com.anvicorp.api.enums.TimesheetStatus}
     * set). The legacy CHECK was created before the ERM-verify stage and
     * rejects every VERIFIED write with SQLSTATE 23514, which 500s the ERM
     * verify / verifyBatch endpoints.
     *
     * <p>Same posture as
     * {@link #relaxProjectLegacyFkNotNull()}: log INFO on success, WARN+chain
     * on failure (no DEBUG-and-forget), and post-rebuild verify against
     * {@code pg_constraint} so a silent rebuild miss is unmissable in the
     * next deploy's log.</p>
     */
    private void rebuildTimesheetsStatusCheck() {
        final String constraint = "timesheets_status_check";
        // 1) Drop the stale CHECK (no-op if not present).
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE timesheets DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} DROP failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        // 2) Add the new CHECK that matches the current TimesheetStatus enum
        //    verbatim: DRAFT, SUBMITTED, VERIFIED, APPROVED, REJECTED.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE timesheets ADD CONSTRAINT " + constraint
                            + " CHECK (status IN ('DRAFT','SUBMITTED','VERIFIED',"
                            + "'APPROVED','REJECTED'))");
            log.info("[SchemaFixupRunner] rebuilt {} to current TimesheetStatus enum "
                    + "(DRAFT,SUBMITTED,VERIFIED,APPROVED,REJECTED)", constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} ADD failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        // 3) Verify via pg_constraint. ERROR (not warn) if the constraint
        //    is still missing — that means the rebuild silently failed and
        //    the ERM verifyBatch will keep 500'ing until someone intervenes
        //    at the DB level.
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "WHERE t.relname = 'timesheets' AND c.conname = ?",
                    Integer.class, constraint);
            if (count != null && count > 0) {
                log.info("[SchemaFixupRunner] verified {} present on timesheets", constraint);
            } else {
                log.error("[SchemaFixupRunner] {} is MISSING on timesheets after rebuild "
                        + "attempt — ERM verifyBatch will continue to 23514 until this is "
                        + "resolved (check DB user privileges on the timesheets table).",
                        constraint);
            }
        } catch (Exception verifyErr) {
            log.warn("[SchemaFixupRunner] post-rebuild verify on {} failed (non-fatal): {}",
                    constraint, verifyErr.getMessage());
        }
    }

    /**
     * Rebuild {@code weekly_reports_status_check} to allow VERIFIED (and
     * the current {@link com.anvicorp.api.enums.WeeklyReportStatus} set).
     * Sister of {@link #rebuildTimesheetsStatusCheck} — same problem:
     * Hibernate's {@code ddl-auto=update} generated the CHECK when the
     * enum only had DRAFT/SUBMITTED/RETURNED/APPROVED, then refused to
     * modify it when VERIFIED was added, so every ERM verify UPDATE
     * fails with SQLSTATE 23514 → surfaced to the user as a generic
     * "A submitted value isn't allowed here."
     */
    /**
     * Seed one row per {@code SkyzenDocument} enum value into
     * {@code onboarding_document_templates}. Runs every boot but the
     * {@code ON CONFLICT DO NOTHING} on the unique key column skips
     * existing rows — admin-uploaded files and admin-added custom rows
     * are never disturbed, and re-runs are a no-op.
     */
    private void seedOnboardingTemplatesFromEnum() {
        try {
            int seeded = 0;
            int sortOrder = 10;
            for (com.anvicorp.api.erm.documents.SkyzenDocument d :
                    com.anvicorp.api.erm.documents.SkyzenDocument.values()) {
                sortOrder += 10;
                int rows = jdbcTemplate.update(
                        "INSERT INTO onboarding_document_templates "
                                + "(id, key_, title, category, sensitivity, description, "
                                + " active, sort_order, created_at, updated_at) "
                                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, NOW(), NOW()) "
                                + "ON CONFLICT (key_) DO NOTHING",
                        d.name(),
                        d.getTitle(),
                        d.getCategory(),
                        d.getSensitivity(),
                        d.getDescription(),
                        !d.isDeprecated(),
                        sortOrder);
                if (rows > 0) seeded++;
            }
            if (seeded > 0) {
                log.info("[SchemaFixupRunner] seeded {} onboarding_document_templates "
                        + "rows from SkyzenDocument enum", seeded);
            } else {
                log.info("[SchemaFixupRunner] onboarding_document_templates already "
                        + "populated — no seeding needed");
            }
        } catch (Exception e) {
            // Table may not exist yet on first-ever boot if ddl-auto hasn't
            // finished the CREATE — non-fatal, will succeed on next boot.
            log.warn("[SchemaFixupRunner] seedOnboardingTemplatesFromEnum failed "
                    + "(non-fatal): {} — root: {}",
                    e.getMessage(), rootMessage(e));
        }
    }

    /**
     * Rebuild {@code intern_evaluations_recording_approval_status_check}
     * so the manager approval gate values ({@code PENDING_APPROVAL},
     * {@code APPROVED}, {@code REVISION_REQUESTED}) are accepted.
     * ddl-auto emits CHECKs from raw String @Column fields inconsistently
     * across Hibernate versions — belt-and-suspenders same as the
     * timesheets / weekly_reports rebuilds.
     */
    private void rebuildRecordingApprovalStatusCheck() {
        final String constraint = "intern_evaluations_recording_approval_status_check";
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE intern_evaluations DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} DROP failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE intern_evaluations ADD CONSTRAINT " + constraint
                            + " CHECK (recording_approval_status IS NULL "
                            + "OR recording_approval_status IN ('PENDING_APPROVAL',"
                            + "'APPROVED','REVISION_REQUESTED'))");
            log.info("[SchemaFixupRunner] rebuilt {} to current recording-approval enum "
                    + "(PENDING_APPROVAL,APPROVED,REVISION_REQUESTED)", constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} ADD failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "WHERE t.relname = 'intern_evaluations' AND c.conname = ?",
                    Integer.class, constraint);
            if (count != null && count > 0) {
                log.info("[SchemaFixupRunner] verified {} present on intern_evaluations", constraint);
            } else {
                log.error("[SchemaFixupRunner] {} is MISSING on intern_evaluations after "
                        + "rebuild — recording-approval writes will 23514 until resolved.",
                        constraint);
            }
        } catch (Exception verifyErr) {
            log.warn("[SchemaFixupRunner] post-rebuild verify on {} failed (non-fatal): {}",
                    constraint, verifyErr.getMessage());
        }
    }

    private void rebuildWeeklyReportsStatusCheck() {
        final String constraint = "weekly_reports_status_check";
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE weekly_reports DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} DROP failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE weekly_reports ADD CONSTRAINT " + constraint
                            + " CHECK (status IN ('DRAFT','SUBMITTED','VERIFIED',"
                            + "'RETURNED','APPROVED'))");
            log.info("[SchemaFixupRunner] rebuilt {} to current WeeklyReportStatus enum "
                    + "(DRAFT,SUBMITTED,VERIFIED,RETURNED,APPROVED)", constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} ADD failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "WHERE t.relname = 'weekly_reports' AND c.conname = ?",
                    Integer.class, constraint);
            if (count != null && count > 0) {
                log.info("[SchemaFixupRunner] verified {} present on weekly_reports", constraint);
            } else {
                log.error("[SchemaFixupRunner] {} is MISSING on weekly_reports after rebuild "
                        + "attempt — ERM verify will continue to 23514 until this is "
                        + "resolved (check DB user privileges on the weekly_reports table).",
                        constraint);
            }
        } catch (Exception verifyErr) {
            log.warn("[SchemaFixupRunner] post-rebuild verify on {} failed (non-fatal): {}",
                    constraint, verifyErr.getMessage());
        }
    }

    /**
     * Rebuild {@code projects_status_check} to allow the full current
     * {@link com.anvicorp.api.enums.ProjectStatus} set. The original code
     * here was a bare {@code DROP CONSTRAINT IF EXISTS} — that broke the
     * moment Hibernate 6's schema-update re-emitted the CHECK from the
     * @Enumerated column, or the DROP silently failed and only logged WARN
     * (the engagement_id-relax lesson). The DROP+ADD+verify shape matches
     * {@link #rebuildTimesheetsStatusCheck} verbatim so future enum widenings
     * only need to touch the value list.
     */
    private void rebuildProjectsStatusCheck() {
        final String constraint = "projects_status_check";
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE projects DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} DROP failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD CONSTRAINT " + constraint
                            + " CHECK (status IN ('NOT_STARTED','IN_PROGRESS',"
                            + "'SUBMITTED','RETURNED','TECH_APPROVED',"
                            + "'PENDING_VIVA','COMPLETED'))");
            log.info("[SchemaFixupRunner] rebuilt {} to current ProjectStatus enum "
                    + "(NOT_STARTED,IN_PROGRESS,SUBMITTED,RETURNED,TECH_APPROVED,"
                    + "PENDING_VIVA,COMPLETED)", constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} ADD failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "WHERE t.relname = 'projects' AND c.conname = ?",
                    Integer.class, constraint);
            if (count != null && count > 0) {
                log.info("[SchemaFixupRunner] verified {} present on projects", constraint);
            } else {
                log.error("[SchemaFixupRunner] {} is MISSING on projects after rebuild "
                        + "attempt — COMPLETED / PENDING_VIVA / TECH_APPROVED writes will "
                        + "23514 until this is resolved (check DB user privileges on the "
                        + "projects table).", constraint);
            }
        } catch (Exception verifyErr) {
            log.warn("[SchemaFixupRunner] post-rebuild verify on {} failed (non-fatal): {}",
                    constraint, verifyErr.getMessage());
        }
    }

    /**
     * Sister of {@link #rebuildProjectsStatusCheck} for the newer
     * {@code project_assignments} table. {@link
     * com.anvicorp.api.enums.ProjectAssignmentStatus} carries the same 7
     * values ProjectStatus does, and the trainer + seeder paths write
     * SUBMITTED / COMPLETED / TECH_APPROVED into
     * {@code project_assignments.status}.
     */
    private void rebuildProjectAssignmentsStatusCheck() {
        final String constraint = "project_assignments_status_check";
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} DROP failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE project_assignments ADD CONSTRAINT " + constraint
                            + " CHECK (status IN ('ASSIGNED','IN_PROGRESS',"
                            + "'SUBMITTED','RETURNED','TECH_APPROVED',"
                            + "'PENDING_VIVA','COMPLETED'))");
            log.info("[SchemaFixupRunner] rebuilt {} to current ProjectAssignmentStatus enum "
                    + "(ASSIGNED,IN_PROGRESS,SUBMITTED,RETURNED,TECH_APPROVED,"
                    + "PENDING_VIVA,COMPLETED)", constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} ADD failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "WHERE t.relname = 'project_assignments' AND c.conname = ?",
                    Integer.class, constraint);
            if (count != null && count > 0) {
                log.info("[SchemaFixupRunner] verified {} present on project_assignments", constraint);
            } else {
                log.error("[SchemaFixupRunner] {} is MISSING on project_assignments after "
                        + "rebuild attempt — SUBMITTED / COMPLETED writes will 23514 until "
                        + "this is resolved (check DB user privileges on the "
                        + "project_assignments table).", constraint);
            }
        } catch (Exception verifyErr) {
            log.warn("[SchemaFixupRunner] post-rebuild verify on {} failed (non-fatal): {}",
                    constraint, verifyErr.getMessage());
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getClass().getName() + ": " + cur.getMessage();
    }

    /**
     * Pre-fix bug: {@code AuthService.verifyEmail} flipped
     * {@code email_verified=TRUE} but never advanced
     * {@code lifecycle_status} from {@code REGISTERED} to
     * {@code EMAIL_VERIFIED}, so the intern dashboard "Verify your email"
     * banner persisted forever for affected users. The fix shipped with
     * the AuthService → InternLifecycleService wiring covers new
     * verifications; this sweep heals every legacy row in one shot.
     * Idempotent — re-running matches zero rows once the backfill lands.
     */
    private void backfillEmailVerifiedLifecycleStatus() {
        try {
            int healed = jdbcTemplate.update(
                    "UPDATE users SET lifecycle_status = 'EMAIL_VERIFIED' "
                            + " WHERE email_verified = TRUE "
                            + "   AND lifecycle_status = 'REGISTERED'");
            if (healed > 0) {
                log.info("[SchemaFixupRunner] healed {} verified user(s) stuck "
                        + "at REGISTERED lifecycle_status", healed);
            }
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] email_verified lifecycle backfill "
                    + "skipped (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * ERM Phase 4 — offer-control + new-hire columns + event log table +
     * partial unique index that allows the void → clear-for-reoffer flow
     * to land a fresh active offer per application.
     */
    private void ensureErmOfferAndNewHireSchema() {
        String[] alters = {
                "ALTER TABLE offers ADD COLUMN IF NOT EXISTS void_reason_code VARCHAR(80)",
                "ALTER TABLE offers ADD COLUMN IF NOT EXISTS void_reason_text TEXT",
                "ALTER TABLE offers ADD COLUMN IF NOT EXISTS internal_notes TEXT",
                "ALTER TABLE offers ADD COLUMN IF NOT EXISTS reminder_count INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE offers ADD COLUMN IF NOT EXISTS last_reminder_at TIMESTAMP",
                "ALTER TABLE offers ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP",
                // Phase 8.6.2 — in-house signing captures the applicant's
                // typed name; nullable so legacy externally-signed rows are
                // preserved as-is. Idempotent ADD IF NOT EXISTS.
                "ALTER TABLE offers ADD COLUMN IF NOT EXISTS signed_by_typed_name VARCHAR(200)",
                // Phase 8.6.2.1 — drawn signature stored as a PNG data URL.
                // TEXT (unbounded in Postgres) accommodates the typical
                // 5-30 KB payload without overflow.
                "ALTER TABLE offers ADD COLUMN IF NOT EXISTS signed_signature_image TEXT",
                // Phase 8.6.2 fix — the original offers_status_check was
                // created with a stale whitelist that pre-dates the current
                // OfferStatus enum (DRAFT/SENT/SIGNED/VOIDED/EXPIRED/DECLINED
                // + deprecated ACCEPTED/REVOKED). Without this, the SIGNED
                // commit in finalizeIdmsSigning fails with 23514 and the
                // whole sign transaction rolls back. Drop + recreate with the
                // full set; idempotent because DROP IF EXISTS runs first.
                "ALTER TABLE offers DROP CONSTRAINT IF EXISTS offers_status_check",
                "ALTER TABLE offers ADD CONSTRAINT offers_status_check "
                        + "CHECK (status IN ('DRAFT','SENT','SIGNED','VOIDED',"
                        + "'EXPIRED','DECLINED','ACCEPTED','REVOKED'))",
                // Same risk class for applications.status — Phase 8.6.2 sets
                // ACCEPTED on in-house sign which was already in the enum but
                // may not be in older check constraints. Drop + recreate with
                // the full current set so no transition is rejected by stale
                // whitelist.
                "ALTER TABLE applications DROP CONSTRAINT IF EXISTS applications_status_check",
                "ALTER TABLE applications ADD CONSTRAINT applications_status_check "
                        + "CHECK (status IN ('APPLIED','HOLD','INFO_REQUESTED',"
                        + "'SCREENING_SENT','SCREENING_COMPLETED','SHORTLISTED',"
                        + "'INTERVIEW_SCHEDULED','INTERVIEWED','SELECTED_CONDITIONAL',"
                        + "'OFFERED','ACCEPTED','ONBOARDING','ACTIVE','HIRED',"
                        + "'COMPLETED','REJECTED','WITHDRAWN','LAPSED','NO_SHOW'))",
                "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS tentative_start_date DATE",
                "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS reporting_structure_complete BOOLEAN NOT NULL DEFAULT FALSE",
                "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS reporting_structure_completed_at TIMESTAMP",
                "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS reporting_structure_completed_by_id UUID"
        };
        for (String sql : alters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM Phase 4 ALTER skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE offers DROP CONSTRAINT IF EXISTS uq_offers_application_id");
            jdbcTemplate.execute(
                    "ALTER TABLE offers DROP CONSTRAINT IF EXISTS offers_application_id_key");
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_offers_active_per_app "
                            + "ON offers (application_id) WHERE archived_at IS NULL");
            log.info("[SchemaFixupRunner] ensured offers partial UNIQUE "
                    + "(application_id) WHERE archived_at IS NULL");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] partial unique index ensure failed (non-fatal): {}",
                    e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS offer_event_logs ("
                            + "  id UUID PRIMARY KEY,"
                            + "  offer_id UUID NOT NULL,"
                            + "  actor_user_id UUID NOT NULL,"
                            + "  event_type VARCHAR(40) NOT NULL,"
                            + "  reason_code VARCHAR(80),"
                            + "  reason_text TEXT,"
                            + "  payload_json TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_offer_event_logs_offer "
                            + "ON offer_event_logs(offer_id, created_at)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] offer_event_logs ensure failed (non-fatal): {}",
                    e.getMessage());
        }
        String[] idxs = {
                "CREATE INDEX IF NOT EXISTS idx_offers_status_sent_at "
                        + "ON offers (status, sent_at)",
                "CREATE INDEX IF NOT EXISTS idx_intern_lifecycles_active_reporting "
                        + "ON intern_lifecycles (active_status, reporting_structure_complete)"
        };
        for (String sql : idxs) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM Phase 4 index skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        log.info("[SchemaFixupRunner] ensured ERM Phase 4 offer + new-hire schema");
    }

    /**
     * ERM Phase 5 — onboarding review headline columns + immutable
     * onboarding_review_logs + work_authorization_records + everify_cases
     * ERM columns + case_number widened to TEXT (now AES-256-GCM encrypted).
     * Also ensures supporting indexes + a best-effort backfill of
     * work_authorization_records from candidates(expected_track,
     * validity_date) so the Compliance Tracker has a row per intern as
     * soon as an applicant declared work-auth on their application.
     */
    private void ensureErmOnboardingAndComplianceSchema() {
        // onboarding_items review headline + counters.
        String[] onbAlters = {
                "ALTER TABLE onboarding_items ADD COLUMN IF NOT EXISTS last_reviewed_at TIMESTAMP",
                "ALTER TABLE onboarding_items ADD COLUMN IF NOT EXISTS last_reviewed_by_id UUID",
                "ALTER TABLE onboarding_items ADD COLUMN IF NOT EXISTS last_review_reason_code VARCHAR(80)",
                "ALTER TABLE onboarding_items ADD COLUMN IF NOT EXISTS last_review_reason_text TEXT",
                "ALTER TABLE onboarding_items ADD COLUMN IF NOT EXISTS review_count INTEGER NOT NULL DEFAULT 0"
        };
        for (String sql : onbAlters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM Phase 5 onboarding_items ALTER skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }

        // everify_cases — widen case_number to TEXT (AES-256-GCM blob) +
        // ERM-only columns. Order matters: widen TYPE before any insert path
        // tries to write an encrypted blob.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE everify_cases ALTER COLUMN case_number TYPE TEXT");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] ERM Phase 5 widen everify_cases.case_number skipped (non-fatal): {}",
                    e.getMessage());
        }
        String[] everifyAlters = {
                "ALTER TABLE everify_cases ADD COLUMN IF NOT EXISTS erm_notes TEXT",
                "ALTER TABLE everify_cases ADD COLUMN IF NOT EXISTS expected_close_by DATE",
                "ALTER TABLE everify_cases ADD COLUMN IF NOT EXISTS last_updated_at TIMESTAMP",
                "ALTER TABLE everify_cases ADD COLUMN IF NOT EXISTS last_updated_by_id UUID"
        };
        for (String sql : everifyAlters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM Phase 5 everify_cases ALTER skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }

        // onboarding_review_logs — JPA also creates this, but the explicit
        // CREATE keeps the contract visible + survives ddl-auto=none envs.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS onboarding_review_logs ("
                            + "  id UUID PRIMARY KEY,"
                            + "  onboarding_item_id UUID NOT NULL,"
                            + "  actor_user_id UUID NOT NULL,"
                            + "  decision VARCHAR(20) NOT NULL,"
                            + "  reason_code VARCHAR(80),"
                            + "  reason_text TEXT,"
                            + "  previous_status VARCHAR(20) NOT NULL,"
                            + "  new_status VARCHAR(20) NOT NULL,"
                            + "  erm_comments_snapshot TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_onb_review_logs_item "
                            + "ON onboarding_review_logs (onboarding_item_id, created_at)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_onb_review_logs_actor "
                            + "ON onboarding_review_logs (actor_user_id, created_at)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] ERM Phase 5 onboarding_review_logs ensure failed (non-fatal): {}",
                    e.getMessage());
        }

        // work_authorization_records — one row per user. Backfilled below.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS work_authorization_records ("
                            + "  id UUID PRIMARY KEY,"
                            + "  user_id UUID NOT NULL,"
                            + "  work_auth_type VARCHAR(40) NOT NULL,"
                            + "  authorized_from DATE,"
                            + "  authorized_until DATE,"
                            + "  ead_card_number TEXT,"
                            + "  ead_expiration DATE,"
                            + "  i20_expiration DATE,"
                            + "  i983_required BOOLEAN NOT NULL DEFAULT FALSE,"
                            + "  i983_id UUID,"
                            + "  dso_name VARCHAR(200),"
                            + "  dso_email VARCHAR(150),"
                            + "  dso_phone VARCHAR(30),"
                            + "  erm_notes TEXT,"
                            + "  last_updated_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  last_updated_by_id UUID NOT NULL,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  CONSTRAINT uk_work_auth_records_user UNIQUE (user_id)"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_work_auth_records_type_expiration "
                            + "ON work_authorization_records (work_auth_type, authorized_until)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] ERM Phase 5 work_authorization_records ensure failed (non-fatal): {}",
                    e.getMessage());
        }

        // Queue + compliance indexes.
        String[] idxs = {
                "CREATE INDEX IF NOT EXISTS idx_onboarding_items_status_packet "
                        + "ON onboarding_items (status, packet_id)",
                "CREATE INDEX IF NOT EXISTS idx_onboarding_items_category_status "
                        + "ON onboarding_items (category, status)",
                "CREATE INDEX IF NOT EXISTS idx_everify_cases_status_close_by "
                        + "ON everify_cases (status, expected_close_by)"
        };
        for (String sql : idxs) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM Phase 5 index skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }

        // Best-effort backfill — only seed users that have a candidate row
        // with an expected_track AND no existing work_authorization_records
        // entry. last_updated_by_id falls back to the user themself so the
        // NOT NULL constraint holds without inventing a system actor.
        try {
            int seeded = jdbcTemplate.update(
                    "INSERT INTO work_authorization_records ("
                            + "  id, user_id, work_auth_type, authorized_until,"
                            + "  i983_required, last_updated_at, last_updated_by_id, created_at"
                            + ") "
                            + "SELECT gen_random_uuid(),"
                            + "       u.id,"
                            + "       CASE c.expected_track "
                            + "            WHEN 'CPT' THEN 'F1_CPT' "
                            + "            WHEN 'OPT' THEN 'F1_OPT' "
                            + "            WHEN 'STEM_OPT' THEN 'F1_STEM_OPT' "
                            + "            ELSE 'OTHER' END,"
                            + "       c.validity_date,"
                            + "       CASE WHEN c.expected_track IN ('CPT','OPT','STEM_OPT') THEN TRUE ELSE FALSE END,"
                            + "       NOW(), u.id, NOW() "
                            + "  FROM candidates c "
                            + "  JOIN users u ON u.id = c.user_id "
                            + " WHERE c.expected_track IS NOT NULL "
                            + "   AND NOT EXISTS (SELECT 1 FROM work_authorization_records w WHERE w.user_id = u.id)");
            if (seeded > 0) {
                log.info("[SchemaFixupRunner] ERM Phase 5 backfill seeded {} work_authorization_records rows",
                        seeded);
            }
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] ERM Phase 5 work-auth backfill skipped (non-fatal): {}",
                    e.getMessage());
        }

        log.info("[SchemaFixupRunner] ensured ERM Phase 5 onboarding + compliance schema");
    }

    /**
     * ERM Phase 6 — exception_records + exception_event_logs. The partial
     * UNIQUE index is the load-bearing piece — it guarantees at most one
     * OPEN/ASSIGNED/IN_PROGRESS row per (subject_user_id, exception_type)
     * regardless of UPSERT race. AUTO_RESOLVED/RESOLVED/DISMISSED rows are
     * excluded from the constraint so reopen-after-resolve flows work.
     */
    private void ensureErmExceptionSchema() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS exception_records ("
                            + "  id UUID PRIMARY KEY,"
                            + "  intern_lifecycle_id UUID NOT NULL,"
                            + "  subject_user_id UUID NOT NULL,"
                            + "  exception_type VARCHAR(50) NOT NULL,"
                            + "  severity VARCHAR(20) NOT NULL,"
                            + "  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',"
                            + "  opened_at TIMESTAMP NOT NULL,"
                            + "  last_seen_at TIMESTAMP NOT NULL,"
                            + "  assigned_to_id UUID,"
                            + "  assigned_at TIMESTAMP,"
                            + "  assigned_by_id UUID,"
                            + "  resolved_at TIMESTAMP,"
                            + "  resolved_by_id UUID,"
                            + "  resolution_note TEXT,"
                            + "  resolution_reason_code VARCHAR(80),"
                            + "  subject_resource_type VARCHAR(40),"
                            + "  subject_resource_id UUID,"
                            + "  payload_json TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] exception_records create failed (non-fatal): {}",
                    e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS exception_event_logs ("
                            + "  id UUID PRIMARY KEY,"
                            + "  exception_record_id UUID NOT NULL,"
                            + "  actor_user_id UUID,"
                            + "  event_type VARCHAR(40) NOT NULL,"
                            + "  previous_status VARCHAR(20),"
                            + "  new_status VARCHAR(20),"
                            + "  reason_code VARCHAR(80),"
                            + "  note TEXT,"
                            + "  payload_json TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] exception_event_logs create failed (non-fatal): {}",
                    e.getMessage());
        }
        String[] idxs = {
                "CREATE INDEX IF NOT EXISTS idx_exception_records_status_severity "
                        + "ON exception_records (status, severity, opened_at)",
                "CREATE INDEX IF NOT EXISTS idx_exception_records_assignee "
                        + "ON exception_records (assigned_to_id, status)",
                "CREATE INDEX IF NOT EXISTS idx_exception_records_subject "
                        + "ON exception_records (subject_user_id, status, exception_type)",
                "CREATE INDEX IF NOT EXISTS idx_exception_event_logs_record "
                        + "ON exception_event_logs (exception_record_id, created_at)"
        };
        for (String sql : idxs) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM Phase 6 index skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        try {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_exception_records_open_per_subject_type "
                            + "ON exception_records (subject_user_id, exception_type) "
                            + "WHERE status IN ('OPEN','ASSIGNED','IN_PROGRESS')");
            log.info("[SchemaFixupRunner] ensured exception_records partial UNIQUE "
                    + "(subject_user_id, exception_type) WHERE status IN OPEN/ASSIGNED/IN_PROGRESS");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] exception_records partial UNIQUE ensure failed (non-fatal): {}",
                    e.getMessage());
        }
        log.info("[SchemaFixupRunner] ensured ERM Phase 6 exception schema");
    }

    /**
     * ERM Phase 7 — exit_records ERM operations columns + the
     * exit_checklist_items table. Structured reason_code is added
     * alongside the legacy free-text {@code exit_reason} so the ERM flow
     * can enforce ReasonCode taxonomy while preserving Phase 8 intern
     * compatibility (legacy reads of {@code exit_reason} keep working).
     */
    private void ensureErmExitOperationsSchema() {
        String[] exitAlters = {
                "ALTER TABLE exit_records ADD COLUMN IF NOT EXISTS reason_code VARCHAR(80)",
                "ALTER TABLE exit_records ADD COLUMN IF NOT EXISTS last_working_day DATE",
                "ALTER TABLE exit_records ADD COLUMN IF NOT EXISTS asset_status_json TEXT",
                "ALTER TABLE exit_records ADD COLUMN IF NOT EXISTS final_timesheet_status VARCHAR(20)",
                "ALTER TABLE exit_records ADD COLUMN IF NOT EXISTS final_documents_archived_at TIMESTAMP",
                "ALTER TABLE exit_records ADD COLUMN IF NOT EXISTS access_revocation_completed_at TIMESTAMP",
                "ALTER TABLE exit_records ADD COLUMN IF NOT EXISTS manager_override_id UUID",
                "ALTER TABLE exit_records ADD COLUMN IF NOT EXISTS manager_override_reason TEXT",
                "ALTER TABLE exit_records ADD COLUMN IF NOT EXISTS manager_override_at TIMESTAMP"
        };
        for (String sql : exitAlters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM Phase 7 exit_records ALTER skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS exit_checklist_items ("
                            + "  id UUID PRIMARY KEY,"
                            + "  exit_record_id UUID NOT NULL,"
                            + "  item_key VARCHAR(60) NOT NULL,"
                            + "  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',"
                            + "  completed_at TIMESTAMP,"
                            + "  completed_by_id UUID,"
                            + "  note TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  CONSTRAINT uk_exit_checklist_items_record_key "
                            + "      UNIQUE (exit_record_id, item_key)"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_exit_checklist_items_record_status "
                            + "ON exit_checklist_items (exit_record_id, status)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] exit_checklist_items ensure failed (non-fatal): {}",
                    e.getMessage());
        }
        log.info("[SchemaFixupRunner] ensured ERM Phase 7 exit operations schema");
    }

    /**
     * Trainer Phase 0 — doc-spec'd clean slate. Drops the
     * weekly-materials tables (concept not in the Trainer doc), adds the
     * doc §7 assignment-form columns to {@code projects} and the doc
     * Feedback Form columns to {@code project_submissions}, creates the
     * {@code project_templates} + {@code project_assignment_event_logs}
     * tables, and indexes everything. The partial UNIQUE
     * {@code uq_projects_active_per_slot} enforces doc §7 "2 monthly
     * projects" at the DB level.
     */
    private void ensureTrainerPhase0Schema() {
        // 1) Drop weekly-materials tables — entity classes already gone.
        //    Order matters: child (acknowledgements) before parent.
        String[] drops = {
                "DROP TABLE IF EXISTS material_acknowledgements CASCADE",
                "DROP TABLE IF EXISTS weekly_materials CASCADE"
        };
        for (String sql : drops) {
            try {
                jdbcTemplate.execute(sql);
                log.info("[SchemaFixupRunner] {}", sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] Trainer Phase 0 drop skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }

        // 2) projects table — 10 ALTERs (9 doc-spec'd + intern_lifecycle_id
        //    to support the partial UNIQUE scope filter).
        String[] projectAlters = {
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS intern_lifecycle_id UUID",
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS project_number SMALLINT",
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS month_year VARCHAR(7)",
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS backdate_authorized_by_id UUID",
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS backdate_reason TEXT",
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS backdate_authorized_at TIMESTAMP",
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS learning_objective_label VARCHAR(300)",
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS i983_objective_index SMALLINT",
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS project_template_id UUID",
                "ALTER TABLE projects ADD COLUMN IF NOT EXISTS notify_stakeholders_internal "
                        + "BOOLEAN NOT NULL DEFAULT TRUE"
        };
        for (String sql : projectAlters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] Trainer Phase 0 projects ALTER skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        // Doc §7 "Project 1 or 2" — CHECK constraint enforced when value present.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE projects DROP CONSTRAINT IF EXISTS chk_projects_project_number");
            jdbcTemplate.execute(
                    "ALTER TABLE projects ADD CONSTRAINT chk_projects_project_number "
                            + "CHECK (project_number IS NULL OR project_number IN (1, 2))");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] Trainer Phase 0 project_number CHECK skipped (non-fatal): {}",
                    e.getMessage());
        }

        // 3) project_submissions table — 6 doc Feedback-Form ALTERs.
        String[] submissionAlters = {
                "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS technical_score SMALLINT",
                "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS communication_score SMALLINT",
                "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS blockers_note TEXT",
                "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS next_action VARCHAR(40)",
                "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS next_action_due_date DATE",
                "ALTER TABLE project_submissions ADD COLUMN IF NOT EXISTS reviewed_links_csv TEXT"
        };
        for (String sql : submissionAlters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] Trainer Phase 0 project_submissions ALTER skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }

        // 4) project_templates table (doc Files/Templates + the
        //    TrainingMaterial data object).
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS project_templates ("
                            + "  id UUID PRIMARY KEY,"
                            + "  title VARCHAR(200) NOT NULL,"
                            + "  technology_area VARCHAR(100) NOT NULL,"
                            + "  description TEXT,"
                            + "  instructions_md TEXT NOT NULL,"
                            + "  github_instructions_md TEXT,"
                            + "  learning_objective_label VARCHAR(300),"
                            + "  attached_document_ids JSONB,"
                            + "  created_by_id UUID NOT NULL,"
                            + "  published BOOLEAN NOT NULL DEFAULT FALSE,"
                            + "  published_at TIMESTAMP,"
                            + "  usage_count INTEGER NOT NULL DEFAULT 0,"
                            + "  archived_at TIMESTAMP,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] Trainer Phase 0 project_templates create failed (non-fatal): {}",
                    e.getMessage());
        }

        // 5) project_assignment_event_logs (doc §11 audit requirement).
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS project_assignment_event_logs ("
                            + "  id UUID PRIMARY KEY,"
                            + "  project_id UUID NOT NULL,"
                            + "  actor_user_id UUID NOT NULL,"
                            + "  event_type VARCHAR(40) NOT NULL,"
                            + "  payload_json TEXT,"
                            + "  comments TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] Trainer Phase 0 project_assignment_event_logs create failed (non-fatal): {}",
                    e.getMessage());
        }

        // 6) Indexes (5 regular + 1 partial UNIQUE).
        String[] idxs = {
                "CREATE INDEX IF NOT EXISTS idx_projects_lifecycle_month_number "
                        + "ON projects (intern_lifecycle_id, month_year, project_number)",
                "CREATE INDEX IF NOT EXISTS idx_projects_status_due_date "
                        + "ON projects (status, due_date)",
                "CREATE INDEX IF NOT EXISTS idx_project_submissions_status_submitted "
                        + "ON project_submissions (status, submitted_at)",
                "CREATE INDEX IF NOT EXISTS idx_project_templates_tech "
                        + "ON project_templates (technology_area, published, archived_at)",
                "CREATE INDEX IF NOT EXISTS idx_project_assignment_event_logs_project "
                        + "ON project_assignment_event_logs (project_id, created_at)"
        };
        for (String sql : idxs) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] Trainer Phase 0 index skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        // Partial UNIQUE — enforces "2 monthly projects" rule at DB level.
        try {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_projects_active_per_slot "
                            + "ON projects (intern_lifecycle_id, month_year, project_number) "
                            + "WHERE status <> 'CANCELLED'");
            log.info("[SchemaFixupRunner] ensured projects partial UNIQUE "
                    + "(intern_lifecycle_id, month_year, project_number) WHERE status<>'CANCELLED'");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] Trainer Phase 0 partial UNIQUE ensure failed (non-fatal): {}",
                    e.getMessage());
        }
        log.info("[SchemaFixupRunner] ensured Trainer Phase 0 schema");
    }

    /**
     * Trainer Phase 1 — two indexes that back the Home dashboard's KPI
     * queries + the Active Interns per-row state hydration. No schema
     * column changes; the partial UNIQUE +  intern_lifecycle_id /
     * project_number indexes from Phase 0 already cover the
     * projects-side queries. The timesheets-side index is intentionally
     * skipped — the table is keyed by candidate_id, not intern_lifecycle
     * _id, and the existing intern_id-keyed query is already indexed.
     */
    private void ensureTrainerPhase1Indexes() {
        String[] idxs = {
                "CREATE INDEX IF NOT EXISTS idx_weekly_meetings_lifecycle_scheduled "
                        + "ON weekly_meetings (intern_lifecycle_id, scheduled_for, status)",
                "CREATE INDEX IF NOT EXISTS idx_intern_evaluations_lifecycle_type_pub "
                        + "ON intern_evaluations (intern_lifecycle_id, evaluation_type, published_at DESC)"
        };
        for (String sql : idxs) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] Trainer Phase 1 index skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        log.info("[SchemaFixupRunner] ensured Trainer Phase 1 indexes");
    }

    /**
     * ERM Phase 8 — Document Template Library + per-intern Packet
     * workflow. All idempotent. Creates 5 tables (migration_log +
     * document_templates + document_packets + document_tasks +
     * document_task_review_logs), ALTERs intern_lifecycles with 3
     * simplified I-9 §2 columns, and indexes everything. The DROP of
     * the legacy onboarding_* tables runs separately in
     * {@link #dropLegacyOnboardingTablesIfMigrated()} only after the
     * OnboardingMigrationRunner posts a success row in migration_log.
     */
    private void ensureErmPhase8Schema() {
        // 1) migration_log — generic one-shot migration ledger.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS migration_log ("
                            + "  id UUID PRIMARY KEY,"
                            + "  migration_key VARCHAR(120) NOT NULL UNIQUE,"
                            + "  executed_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  rows_migrated INTEGER NOT NULL DEFAULT 0,"
                            + "  notes TEXT"
                            + ")");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] migration_log create failed (non-fatal): {}",
                    e.getMessage());
        }

        // 2) document_templates.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS document_templates ("
                            + "  id UUID PRIMARY KEY,"
                            + "  title VARCHAR(200) NOT NULL UNIQUE,"
                            + "  description TEXT,"
                            + "  category VARCHAR(40) NOT NULL,"
                            + "  template_file_id UUID,"
                            + "  file_kind VARCHAR(20) NOT NULL DEFAULT 'PDF',"
                            + "  sensitivity VARCHAR(40) NOT NULL DEFAULT 'NORMAL',"
                            + "  version INTEGER NOT NULL DEFAULT 1,"
                            + "  previous_version_file_id UUID,"
                            + "  is_active BOOLEAN NOT NULL DEFAULT TRUE,"
                            + "  instructions TEXT,"
                            + "  created_by_id UUID NOT NULL,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_document_templates_cat_active "
                            + "ON document_templates (category, is_active)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] document_templates create failed (non-fatal): {}",
                    e.getMessage());
        }

        // 3) document_packets — one active per intern_lifecycle via
        //    partial UNIQUE.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS document_packets ("
                            + "  id UUID PRIMARY KEY,"
                            + "  intern_lifecycle_id UUID NOT NULL,"
                            + "  assigned_by_id UUID NOT NULL,"
                            + "  status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED',"
                            + "  custom_instructions TEXT,"
                            + "  assigned_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  first_submission_at TIMESTAMP,"
                            + "  all_submitted_at TIMESTAMP,"
                            + "  completed_at TIMESTAMP,"
                            + "  cancelled_at TIMESTAMP,"
                            + "  cancellation_reason TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_document_packets_lifecycle_status "
                            + "ON document_packets (intern_lifecycle_id, status)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_document_packets_status_assigned "
                            + "ON document_packets (status, assigned_at)");
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_document_packets_active_per_lifecycle "
                            + "ON document_packets (intern_lifecycle_id) "
                            + "WHERE status <> 'CANCELLED'");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] document_packets create failed (non-fatal): {}",
                    e.getMessage());
        }

        // 4) document_tasks — one per (packet, template) combo.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS document_tasks ("
                            + "  id UUID PRIMARY KEY,"
                            + "  packet_id UUID NOT NULL,"
                            + "  template_id UUID,"
                            + "  template_snapshot_file_id UUID,"
                            + "  template_snapshot_version INTEGER,"
                            + "  task_instructions TEXT,"
                            + "  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',"
                            + "  uploaded_file_id UUID,"
                            + "  download_count INTEGER NOT NULL DEFAULT 0,"
                            + "  last_downloaded_at TIMESTAMP,"
                            + "  submitted_at TIMESTAMP,"
                            + "  reviewed_at TIMESTAMP,"
                            + "  reviewed_by_id UUID,"
                            + "  review_reason_code VARCHAR(80),"
                            + "  review_comments TEXT,"
                            + "  version INTEGER NOT NULL DEFAULT 1,"
                            + "  waived_at TIMESTAMP,"
                            + "  waived_by_id UUID,"
                            + "  waived_reason TEXT,"
                            + "  internal_note TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  CONSTRAINT uq_document_tasks_packet_template "
                            + "      UNIQUE (packet_id, template_id)"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_document_tasks_packet_status "
                            + "ON document_tasks (packet_id, status)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_document_tasks_status_submitted "
                            + "ON document_tasks (status, submitted_at)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_document_tasks_reviewer "
                            + "ON document_tasks (reviewed_by_id, reviewed_at)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] document_tasks create failed (non-fatal): {}",
                    e.getMessage());
        }

        // 5) document_task_review_logs.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS document_task_review_logs ("
                            + "  id UUID PRIMARY KEY,"
                            + "  task_id UUID NOT NULL,"
                            + "  actor_user_id UUID,"
                            + "  event_type VARCHAR(40) NOT NULL,"
                            + "  previous_status VARCHAR(20),"
                            + "  new_status VARCHAR(20),"
                            + "  reason_code VARCHAR(80),"
                            + "  comments TEXT,"
                            + "  payload_json TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_document_task_review_logs_task "
                            + "ON document_task_review_logs (task_id, created_at)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_document_task_review_logs_actor "
                            + "ON document_task_review_logs (actor_user_id, created_at)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] document_task_review_logs create failed (non-fatal): {}",
                    e.getMessage());
        }

        // 6) intern_lifecycles — simplified I-9 §2 capture columns
        //    (replaces structured I9Section2Form workflow). Idempotent.
        String[] alters = {
                "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS i9_section2_completed_at TIMESTAMP",
                "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS i9_section2_completed_by_id UUID",
                "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS i9_section2_documents_described VARCHAR(500)",
                "ALTER TABLE intern_lifecycles ADD COLUMN IF NOT EXISTS i9_section2_notes TEXT"
        };
        for (String sql : alters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM Phase 8 intern_lifecycles ALTER skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        log.info("[SchemaFixupRunner] ensured ERM Phase 8 schema");
    }

    /**
     * ERM Phase 8 — drop the legacy onboarding_* tables ONLY after
     * {@code OnboardingMigrationRunner} has posted a success row in
     * {@code migration_log}. Idempotent: if the row isn't there yet,
     * skip silently — next boot will retry after the migration runs.
     */
    private void dropLegacyOnboardingTablesIfMigrated() {
        boolean migrated;
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM migration_log "
                            + " WHERE migration_key = 'ONBOARDING_TO_DOCUMENT_PACKETS_V1'",
                    Integer.class);
            migrated = cnt != null && cnt > 0;
        } catch (Exception e) {
            // migration_log table may not exist on first boot — that's fine,
            // it gets created above. Just skip DROPs.
            migrated = false;
        }
        if (!migrated) {
            log.info("[SchemaFixupRunner] Phase 8 legacy DROP deferred — "
                    + "OnboardingMigrationRunner has not yet posted success");
            return;
        }
        String[] drops = {
                "DROP TABLE IF EXISTS onboarding_review_logs CASCADE",
                "DROP TABLE IF EXISTS onboarding_items CASCADE",
                "DROP TABLE IF EXISTS onboarding_packets CASCADE"
        };
        for (String sql : drops) {
            try {
                jdbcTemplate.execute(sql);
                log.info("[SchemaFixupRunner] {}", sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] Phase 8 legacy DROP skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        log.info("[SchemaFixupRunner] Phase 8 legacy onboarding tables dropped");
    }

    /**
     * ERM Phase 3 — interview scheduler + decision center storage. Adds the
     * new decision-context columns idempotently, creates
     * {@code interview_event_logs}, and indexes the scheduler queries.
     */
    private void ensureErmInterviewSchema() {
        String[] alters = {
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS panel_interviewer_ids TEXT",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS reschedule_count INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS last_reschedule_reason_code VARCHAR(80)",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS last_reschedule_reason_text TEXT",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS last_rescheduled_at TIMESTAMP",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS last_rescheduled_by_id UUID",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS cancellation_reason_code VARCHAR(80)",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS cancellation_reason_text TEXT",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS cancelled_by_id UUID",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS technical_score INTEGER",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS communication_score INTEGER",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS cultural_fit_score INTEGER",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS overall_recommendation VARCHAR(20)",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS decision_reason_code VARCHAR(80)",
                "ALTER TABLE interviews ADD COLUMN IF NOT EXISTS decision_reason_text TEXT"
        };
        for (String sql : alters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM interview ALTER skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS interview_event_logs ("
                            + "  id UUID PRIMARY KEY,"
                            + "  interview_id UUID NOT NULL,"
                            + "  actor_user_id UUID NOT NULL,"
                            + "  event_type VARCHAR(40) NOT NULL,"
                            + "  reason_code VARCHAR(80),"
                            + "  reason_text TEXT,"
                            + "  payload_json TEXT,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_interview_event_logs_interview_at "
                            + "ON interview_event_logs(interview_id, created_at)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] interview_event_logs ensure failed (non-fatal): {}",
                    e.getMessage());
        }
        String[] idxs = {
                "CREATE INDEX IF NOT EXISTS idx_interviews_status_scheduled "
                        + "ON interviews (status, scheduled_at)",
                "CREATE INDEX IF NOT EXISTS idx_interviews_application "
                        + "ON interviews (application_id)"
        };
        for (String sql : idxs) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM interview index skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        log.info("[SchemaFixupRunner] ensured ERM Phase 3 interview scheduler schema");
    }

    /**
     * Re-apply after WITHDRAWN — replace the legacy unconditional UNIQUE
     * {@code uk_application_candidate_job_posting} on
     * {@code applications(candidate_id, job_posting_id)} with a partial
     * unique index that ignores WITHDRAWN rows. Without this an intern who
     * withdrew from a posting can never apply to it again (the entity-level
     * "already applied" guard was updated to exclude WITHDRAWN, but the DB
     * constraint would still 23505 the fresh INSERT). WITHDRAWN rows must
     * stay put because {@code ErmReportsService} + the candidate-side "exit
     * status" UI read them.
     *
     * <p>Postgres-specific — ddl-auto=update can't express partial indexes,
     * so this runner owns the enforcement. Steps:</p>
     * <ol>
     *   <li>Drop any Hibernate-emitted unconditional variants of the
     *       constraint/index (both {@code ALTER TABLE ... DROP CONSTRAINT}
     *       and {@code DROP INDEX}, since Hibernate creates one or the
     *       other depending on ordering).</li>
     *   <li>Create the partial unique index
     *       {@code uk_application_candidate_job_posting_active} on
     *       {@code (candidate_id, job_posting_id) WHERE status <> 'WITHDRAWN'}.</li>
     *   <li>Verify via {@code pg_indexes}. ERROR-log if it didn't take —
     *       re-apply after withdrawal will keep 409'ing until resolved.</li>
     * </ol>
     */
    private void ensureApplicationsReapplyAfterWithdrawSchema() {
        // 1) Drop the legacy unconditional UNIQUE(candidate_id, job_posting_id).
        //    Hibernate may have created it as a constraint OR as a plain unique
        //    index depending on which ddl-auto pass ran first — belt-and-brace.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE applications "
                            + "DROP CONSTRAINT IF EXISTS uk_application_candidate_job_posting");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] legacy uk_application_candidate_job_posting "
                    + "constraint drop skipped (non-fatal): {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "DROP INDEX IF EXISTS uk_application_candidate_job_posting");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] legacy uk_application_candidate_job_posting "
                    + "index drop skipped (non-fatal): {}", e.getMessage());
        }

        // 2) Create the partial UNIQUE that excludes WITHDRAWN.
        try {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS "
                            + "uk_application_candidate_job_posting_active "
                            + "ON applications (candidate_id, job_posting_id) "
                            + "WHERE status <> 'WITHDRAWN'");
            log.info("[SchemaFixupRunner] ensured applications partial UNIQUE "
                    + "(candidate_id, job_posting_id) WHERE status <> 'WITHDRAWN'");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] applications partial UNIQUE ensure failed "
                    + "(non-fatal): {}", e.getMessage(), e);
        }

        // 3) Verify via pg_indexes. If it didn't take, re-apply after
        //    withdrawal will keep 23505'ing on the fresh INSERT.
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_indexes "
                            + "WHERE tablename = 'applications' AND indexname = ?",
                    Integer.class, "uk_application_candidate_job_posting_active");
            if (count != null && count > 0) {
                log.info("[SchemaFixupRunner] verified "
                        + "uk_application_candidate_job_posting_active present on applications");
            } else {
                log.error("[SchemaFixupRunner] uk_application_candidate_job_posting_active "
                        + "is MISSING on applications after ensure attempt — re-apply after "
                        + "withdrawal will continue to 23505 until this is resolved (check "
                        + "DB user privileges + whether a legacy uk_application_candidate_job_posting "
                        + "is still hanging around).");
            }
        } catch (Exception verifyErr) {
            log.warn("[SchemaFixupRunner] post-ensure verify on "
                    + "uk_application_candidate_job_posting_active failed (non-fatal): {}",
                    verifyErr.getMessage());
        }
    }

    /**
     * ERM Phase 2 — application inbox + decision-flow storage. Adds the 7
     * new decision-context columns idempotently, creates
     * {@code application_decision_logs}, and indexes the inbox queries.
     */
    private void ensureErmApplicationInboxSchema() {
        String[] alters = {
                "ALTER TABLE applications ADD COLUMN IF NOT EXISTS last_decision_reason_code VARCHAR(80)",
                "ALTER TABLE applications ADD COLUMN IF NOT EXISTS last_decision_reason_text TEXT",
                "ALTER TABLE applications ADD COLUMN IF NOT EXISTS last_decision_at TIMESTAMP",
                "ALTER TABLE applications ADD COLUMN IF NOT EXISTS last_decision_by_id UUID",
                "ALTER TABLE applications ADD COLUMN IF NOT EXISTS info_requested_fields_csv VARCHAR(500)",
                "ALTER TABLE applications ADD COLUMN IF NOT EXISTS info_requested_at TIMESTAMP",
                "ALTER TABLE applications ADD COLUMN IF NOT EXISTS internal_notes TEXT"
        };
        for (String sql : alters) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM application ALTER skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        // Hibernate may have created a CHECK constraint on applications.status
        // before HOLD + INFO_REQUESTED were added to the enum. Drop the stale
        // one so the new values persist cleanly. (SchemaFixupRunner already
        // drops it earlier in this run, but we belt-and-brace here too.)
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE applications DROP CONSTRAINT IF EXISTS applications_status_check");
        } catch (Exception e) {
            log.debug("[SchemaFixupRunner] applications_status_check re-drop skipped: {}",
                    e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS application_decision_logs ("
                            + "  id UUID PRIMARY KEY,"
                            + "  application_id UUID NOT NULL,"
                            + "  decided_by_id UUID NOT NULL,"
                            + "  decision VARCHAR(30) NOT NULL,"
                            + "  reason_code VARCHAR(80),"
                            + "  reason_text TEXT,"
                            + "  previous_stage VARCHAR(40) NOT NULL,"
                            + "  new_stage VARCHAR(40) NOT NULL,"
                            + "  applicant_visible_message TEXT,"
                            + "  decided_at TIMESTAMP NOT NULL,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_app_decision_logs_app_at "
                            + "ON application_decision_logs(application_id, decided_at)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] application_decision_logs ensure failed (non-fatal): {}",
                    e.getMessage());
        }
        String[] idxs = {
                "CREATE INDEX IF NOT EXISTS idx_applications_status_created "
                        + "ON applications (status, applied_at DESC)",
                "CREATE INDEX IF NOT EXISTS idx_applications_erm_owner_status "
                        + "ON applications (erm_owner_id, status)"
        };
        for (String sql : idxs) {
            try {
                jdbcTemplate.execute(sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM inbox index skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }
        log.info("[SchemaFixupRunner] ensured ERM Phase 2 application inbox schema");
    }

    /**
     * ERM Phase 1 — 8 indexes that back the dashboard KPI counts and the
     * exception detection joins. All idempotent.
     */
    private void ensureErmDashboardIndexes() {
        record IdxSpec(String name, String table, String cols) {}
        List<IdxSpec> idxs = List.of(
                new IdxSpec("idx_applications_status_erm",
                        "applications", "status, erm_owner_id"),
                new IdxSpec("idx_interviews_scheduled_status",
                        "interviews", "scheduled_at, status"),
                new IdxSpec("idx_offers_status_created_by",
                        "offers", "status, created_by"),
                new IdxSpec("idx_onboarding_packets_status_assigned",
                        "onboarding_packets", "status, assigned_at, assigned_by_id"),
                new IdxSpec("idx_intern_lifecycles_active_erm",
                        "intern_lifecycles", "active_status, erm_id"),
                new IdxSpec("idx_intern_evaluations_lifecycle_type_status",
                        "intern_evaluations",
                        "intern_lifecycle_id, evaluation_type, status, published_at DESC"),
                new IdxSpec("idx_timesheets_status_intern",
                        "timesheets", "status, intern_id"),
                new IdxSpec("idx_audit_logs_subject_timestamp_desc",
                        "audit_logs", "subject_user_id, timestamp DESC")
        );
        int created = 0;
        for (IdxSpec spec : idxs) {
            try {
                jdbcTemplate.execute(
                        "CREATE INDEX IF NOT EXISTS " + spec.name()
                                + " ON " + spec.table()
                                + " (" + spec.cols() + ")");
                created++;
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] ERM index {} on {}({}) failed (non-fatal): {}",
                        spec.name(), spec.table(), spec.cols(), e.getMessage());
            }
        }
        log.info("[SchemaFixupRunner] ensured {} ERM dashboard indexes (idempotent)", created);
    }

    /**
     * ERM Phase 0 — ERM-editable message template storage.
     * UNIQUE on (template_key, channel) so the service can resolve at render
     * time without scanning. Channel discriminates EMAIL vs IN_APP without
     * a schema change later.
     */
    private void ensureCommunicationTemplatesTable() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS communication_templates ("
                            + "  id UUID PRIMARY KEY,"
                            + "  template_key VARCHAR(80) NOT NULL,"
                            + "  channel VARCHAR(20) NOT NULL,"
                            + "  subject_template TEXT,"
                            + "  body_template TEXT NOT NULL,"
                            + "  variables_csv VARCHAR(500),"
                            + "  active BOOLEAN NOT NULL DEFAULT TRUE,"
                            + "  updated_by_id UUID,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uk_communication_templates_key_channel "
                            + "ON communication_templates(template_key, channel)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_communication_templates_active "
                            + "ON communication_templates(active)");
            log.info("[SchemaFixupRunner] ensured communication_templates table + indexes");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] communication_templates ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Phase 8 — terminal exit lifecycle storage. UNIQUE per intern_lifecycle
     * row enforces "one exit per lifecycle"; UNIQUE on (exit_record_id) on
     * exit_feedback enforces "one feedback per exit".
     */
    private void ensureExitTables() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS exit_records ("
                            + "  id UUID PRIMARY KEY,"
                            + "  intern_lifecycle_id UUID NOT NULL UNIQUE,"
                            + "  intern_id UUID NOT NULL,"
                            + "  exit_type VARCHAR(20) NOT NULL,"
                            + "  exit_date DATE NOT NULL,"
                            + "  exit_reason TEXT,"
                            + "  initiated_by_id UUID NOT NULL,"
                            + "  final_evaluation_id UUID,"
                            + "  rehire_eligible BOOLEAN NOT NULL DEFAULT TRUE,"
                            + "  access_revocation_done BOOLEAN NOT NULL DEFAULT FALSE,"
                            + "  access_revocation_attempted_at TIMESTAMP,"
                            + "  access_revocation_summary TEXT,"
                            + "  final_documents_archived BOOLEAN NOT NULL DEFAULT FALSE,"
                            + "  intern_visible_summary TEXT,"
                            + "  internal_notes TEXT,"
                            + "  amended_at TIMESTAMP,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_exit_records_intern "
                            + "ON exit_records(intern_id)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_exit_records_type_date "
                            + "ON exit_records(exit_type, exit_date)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_exit_records_initiator "
                            + "ON exit_records(initiated_by_id)");
            log.info("[SchemaFixupRunner] ensured exit_records table + indexes");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] exit_records ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }

        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS exit_feedback ("
                            + "  id UUID PRIMARY KEY,"
                            + "  exit_record_id UUID NOT NULL UNIQUE,"
                            + "  intern_id UUID NOT NULL,"
                            + "  overall_rating INTEGER NOT NULL,"
                            + "  learning_rating INTEGER NOT NULL,"
                            + "  mentorship_rating INTEGER NOT NULL,"
                            + "  work_environment_rating INTEGER NOT NULL,"
                            + "  what_went_well TEXT NOT NULL,"
                            + "  what_could_improve TEXT NOT NULL,"
                            + "  would_recommend BOOLEAN NOT NULL,"
                            + "  additional_comments TEXT,"
                            + "  submitted_at TIMESTAMP NOT NULL,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_exit_feedback_intern "
                            + "ON exit_feedback(intern_id)");
            log.info("[SchemaFixupRunner] ensured exit_feedback table + index");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] exit_feedback ensure failed (non-fatal): {}",
                    e.getMessage(), e);
        }
    }

    // ── ERM Phase 8.2 — strip DocumentTemplate → static PDF list ───────────

    private static final String PHASE_8_2_KEY =
            "DOCUMENT_TEMPLATE_TO_STATIC_PDF_V1";

    /**
     * ERM Phase 8.2 — replaces the dynamically-managed
     * {@code document_templates} table with the static {@code SkyzenDocument}
     * enum, persisted as {@code document_tasks.document_key} (VARCHAR).
     *
     * <p>Run order (all idempotent):</p>
     * <ol>
     *   <li>If the migration_log row is already present, skip.</li>
     *   <li>{@code ALTER TABLE document_tasks ADD COLUMN IF NOT EXISTS document_key VARCHAR(80)}.</li>
     *   <li>Drop the legacy {@code uq_document_tasks_packet_template}
     *       UNIQUE (which referenced {@code template_id} — would block
     *       the column drop).</li>
     *   <li>For each {@code document_tasks} row where document_key IS NULL
     *       and template_id IS NOT NULL: look up the template's title in
     *       {@code document_templates}, map it via the title→enum-key
     *       table below, and set document_key. Rows that don't map (no
     *       matching title) are skipped with a warning.</li>
     *   <li>Drop the now-unused {@code template_id},
     *       {@code template_snapshot_file_id},
     *       {@code template_snapshot_version} columns.</li>
     *   <li>Drop the {@code document_templates} table itself.</li>
     *   <li>Add a fresh UNIQUE on {@code (packet_id, document_key)}
     *       (the new one-task-per-document-per-packet invariant).</li>
     *   <li>Post the {@code DOCUMENT_TEMPLATE_TO_STATIC_PDF_V1} row in
     *       migration_log so subsequent boots skip this whole block.</li>
     * </ol>
     */
    private void migrateDocumentTemplatesToStaticPdfV1() {
        if (alreadyMigratedPhase8_2()) {
            log.info("[SchemaFixupRunner] Phase 8.2 migration {} already executed; skipping",
                    PHASE_8_2_KEY);
            return;
        }

        // 1) Add document_key column (NULLable while we backfill).
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE document_tasks "
                            + "ADD COLUMN IF NOT EXISTS document_key VARCHAR(80)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] Phase 8.2 add document_key skipped (non-fatal): {}",
                    e.getMessage());
        }

        // 2) Drop legacy UNIQUE that references the column we're about
        //    to remove. PostgreSQL: constraint name = uq_document_tasks_packet_template.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE document_tasks "
                            + "DROP CONSTRAINT IF EXISTS uq_document_tasks_packet_template");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] Phase 8.2 drop legacy UNIQUE skipped (non-fatal): {}",
                    e.getMessage());
        }

        // 3) Backfill document_key from template_id by title match.
        //    Only attempted if document_templates table still exists —
        //    on a clean database the table may already be gone (no rows
        //    to migrate, also fine).
        boolean templatesTableExists;
        try {
            jdbcTemplate.queryForObject(
                    "SELECT 1 FROM information_schema.tables "
                            + " WHERE table_name = 'document_templates'",
                    Integer.class);
            templatesTableExists = true;
        } catch (Exception e) {
            templatesTableExists = false;
        }
        int mapped = 0;
        int unmapped = 0;
        if (templatesTableExists) {
            for (var d : com.anvicorp.api.erm.documents.SkyzenDocument.values()) {
                try {
                    int n = jdbcTemplate.update(
                            "UPDATE document_tasks dt SET document_key = ? "
                                    + "  FROM document_templates t "
                                    + " WHERE dt.template_id = t.id "
                                    + "   AND dt.document_key IS NULL "
                                    + "   AND LOWER(TRIM(t.title)) = LOWER(?)",
                            d.name(), d.getTitle());
                    mapped += n;
                } catch (Exception e) {
                    log.warn("[SchemaFixupRunner] Phase 8.2 backfill {} failed (non-fatal): {}",
                            d.name(), e.getMessage());
                }
            }
            // Count orphans that we couldn't map.
            try {
                Integer remaining = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM document_tasks "
                                + " WHERE template_id IS NOT NULL AND document_key IS NULL",
                        Integer.class);
                unmapped = remaining == null ? 0 : remaining;
            } catch (Exception e) {
                unmapped = -1;
            }
            log.info("[SchemaFixupRunner] Phase 8.2 backfilled {} document_key rows; {} orphans skipped",
                    mapped, unmapped);
        } else {
            log.info("[SchemaFixupRunner] Phase 8.2 document_templates already gone; nothing to backfill");
        }

        // 4) Drop now-unused columns. Cascade in case the orphans block
        //    the column drop (we accept losing those rows' template_id —
        //    they were already unreachable post-Phase 8 anyway).
        String[] colDrops = {
                "ALTER TABLE document_tasks DROP COLUMN IF EXISTS template_id",
                "ALTER TABLE document_tasks DROP COLUMN IF EXISTS template_snapshot_file_id",
                "ALTER TABLE document_tasks DROP COLUMN IF EXISTS template_snapshot_version"
        };
        for (String sql : colDrops) {
            try {
                jdbcTemplate.execute(sql);
                log.info("[SchemaFixupRunner] Phase 8.2 {}", sql);
            } catch (Exception e) {
                log.warn("[SchemaFixupRunner] Phase 8.2 column drop skipped (non-fatal): {} — {}",
                        sql, e.getMessage());
            }
        }

        // 5) Drop the document_templates table itself.
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS document_templates CASCADE");
            log.info("[SchemaFixupRunner] Phase 8.2 dropped document_templates");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] Phase 8.2 drop document_templates skipped (non-fatal): {}",
                    e.getMessage());
        }

        // 6) Re-add a UNIQUE on the new (packet_id, document_key) pair.
        try {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_document_tasks_packet_document_key "
                            + "ON document_tasks (packet_id, document_key)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] Phase 8.2 packet+document_key UNIQUE skipped (non-fatal): {}",
                    e.getMessage());
        }

        // 7) Post the migration_log row so subsequent boots short-circuit.
        try {
            jdbcTemplate.update(
                    "INSERT INTO migration_log (migration_key, executed_at, notes) "
                            + " VALUES (?, NOW(), ?) "
                            + " ON CONFLICT (migration_key) DO NOTHING",
                    PHASE_8_2_KEY,
                    "Migrated template_id → document_key for " + mapped
                            + " tasks; " + unmapped + " orphans dropped with column.");
            log.info("[SchemaFixupRunner] Phase 8.2 migration complete; posted {} to migration_log",
                    PHASE_8_2_KEY);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] Phase 8.2 migration_log INSERT skipped (non-fatal): {}",
                    e.getMessage());
        }
    }

    private boolean alreadyMigratedPhase8_2() {
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM migration_log WHERE migration_key = ?",
                    Integer.class, PHASE_8_2_KEY);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Evaluator Phase 0 — clean slate scaffolding ────────────────────────

    private static final String EVALUATOR_PHASE_0_KEY =
            "EVALUATOR_PHASE_0_SCHEMA_V1";

    /**
     * Evaluator Phase 0 schema bootstrap. Idempotent + gated by a
     * {@code migration_log} row so the heavy operations only run once.
     *
     * <p>Operations:</p>
     * <ol>
     *   <li>Refresh {@code user_roles_role_check} to include {@code EVALUATOR}
     *       — the previous {@code ALIGN_ENUM_CHECK_CONSTRAINTS_V1} sweep
     *       didn't list this column, and the new role can't write without it.</li>
     *   <li>Create {@code i983_evaluations} explicitly. Hibernate
     *       {@code ddl-auto=update} also creates it from the new entity, but
     *       the explicit CREATE keeps the contract visible in source and
     *       protects environments that disable ddl-auto.</li>
     *   <li>Post the {@code migration_log} row so subsequent boots
     *       short-circuit.</li>
     * </ol>
     */
    private void migrateEvaluatorPhase0SchemaV1() {
        if (alreadyMigratedEvaluatorPhase0()) {
            log.info("[SchemaFixupRunner] {} already applied — skip",
                    EVALUATOR_PHASE_0_KEY);
            return;
        }

        // 1) user_roles CHECK refresh — include EVALUATOR. Drop any existing
        // whitelist CHECK(s) on (user_roles, role) first, then add a fresh
        // constraint with the current 7-role set. Heuristic mirrors
        // alignEnumCheckConstraintsV1: only drop CHECKs whose definition
        // contains a single-quoted token (the value-IN form).
        try {
            List<java.util.Map<String, Object>> existing =
                    jdbcTemplate.queryForList(
                            "SELECT con.conname AS name, "
                                    + "pg_get_constraintdef(con.oid) AS def "
                                    + "FROM pg_constraint con "
                                    + "JOIN pg_class cl ON cl.oid = con.conrelid "
                                    + "JOIN pg_attribute att "
                                    + "  ON att.attrelid = cl.oid "
                                    + "  AND att.attnum = ANY(con.conkey) "
                                    + "WHERE cl.relname = 'user_roles' "
                                    + "  AND att.attname = 'role' "
                                    + "  AND con.contype = 'c'");
            for (java.util.Map<String, Object> c : existing) {
                String def = String.valueOf(c.get("def"));
                if (!def.contains("'")) continue;
                String name = String.valueOf(c.get("name"));
                jdbcTemplate.execute(
                        "ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS " + name);
            }
            jdbcTemplate.execute(
                    "ALTER TABLE user_roles ADD CONSTRAINT user_roles_role_check "
                            + "CHECK (role IN ('INTERN','TRAINER','EVALUATOR',"
                            + "'REPORTING_MANAGER','MANAGER','ERM','SUPER_ADMIN'))");
            log.info("[SchemaFixupRunner] refreshed user_roles_role_check to "
                    + "include EVALUATOR");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] user_roles CHECK refresh skipped "
                    + "(non-fatal): {}", e.getMessage());
        }

        // 2) i983_evaluations explicit CREATE.
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS i983_evaluations ("
                            + "  id UUID PRIMARY KEY,"
                            + "  intern_lifecycle_id UUID NOT NULL,"
                            + "  i983_plan_id UUID,"
                            + "  evaluator_id UUID NOT NULL,"
                            + "  period_start_date DATE,"
                            + "  period_end_date DATE,"
                            + "  evaluation_type VARCHAR(32),"
                            + "  status VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED',"
                            + "  training_objectives_progress TEXT,"
                            + "  training_supervision_provided TEXT,"
                            + "  training_evaluation_outcomes TEXT,"
                            + "  employer_signature_required BOOLEAN NOT NULL DEFAULT TRUE,"
                            + "  student_signature_required BOOLEAN NOT NULL DEFAULT TRUE,"
                            + "  dso_submitted_to_school_at TIMESTAMP,"
                            + "  published_at TIMESTAMP,"
                            + "  acknowledged_at TIMESTAMP,"
                            + "  created_by_id UUID,"
                            + "  created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
                            + "  updated_at TIMESTAMP NOT NULL DEFAULT NOW()"
                            + ")");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_i983_eval_lifecycle_status "
                            + "ON i983_evaluations (intern_lifecycle_id, status)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_i983_eval_evaluator_status "
                            + "ON i983_evaluations (evaluator_id, status)");
            log.info("[SchemaFixupRunner] ensured i983_evaluations table + indexes");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] i983_evaluations CREATE skipped "
                    + "(non-fatal): {}", e.getMessage());
        }

        // 3) migration_log row so subsequent boots short-circuit.
        try {
            jdbcTemplate.update(
                    "INSERT INTO migration_log "
                            + "(id, migration_key, executed_at, rows_migrated, notes) "
                            + "VALUES (?, ?, NOW(), 0, ?) "
                            + "ON CONFLICT (migration_key) DO NOTHING",
                    java.util.UUID.randomUUID(),
                    EVALUATOR_PHASE_0_KEY,
                    "Evaluator Phase 0 scaffold — user_roles CHECK + "
                            + "i983_evaluations table.");
            log.info("[SchemaFixupRunner] {} applied", EVALUATOR_PHASE_0_KEY);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} migration_log INSERT skipped "
                    + "(non-fatal): {}", EVALUATOR_PHASE_0_KEY, e.getMessage());
        }
    }

    private boolean alreadyMigratedEvaluatorPhase0() {
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM migration_log WHERE migration_key = ?",
                    Integer.class, EVALUATOR_PHASE_0_KEY);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * JOBS_ADMIN Phase 0 — rebuild {@code user_roles_role_check} with the
     * current 8-role set. Hibernate ddl-auto will add enum values to Java,
     * but it will not alter an existing Postgres CHECK constraint, so boot
     * seeders hit SQLSTATE 23514 unless the constraint is refreshed.
     */
    private void rebuildUserRolesRoleCheckForJobsAdmin() {
        final String constraint = "user_roles_role_check";
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} DROP failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }

        try {
            jdbcTemplate.execute(
                    "ALTER TABLE user_roles ADD CONSTRAINT " + constraint
                            + " CHECK (role IN ('INTERN','TRAINER','EVALUATOR',"
                            + "'REPORTING_MANAGER','MANAGER','ERM',"
                            + "'SUPER_ADMIN','JOBS_ADMIN'))");
            log.info("[SchemaFixupRunner] rebuilt {} to include JOBS_ADMIN", constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} ADD failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }

        try {
            String def = jdbcTemplate.queryForObject(
                    "SELECT pg_get_constraintdef(c.oid) "
                            + "FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "WHERE t.relname = 'user_roles' AND c.conname = ?",
                    String.class, constraint);
            if (def != null && def.contains("JOBS_ADMIN")) {
                log.info("[SchemaFixupRunner] verified {} includes JOBS_ADMIN", constraint);
            } else {
                log.error("[SchemaFixupRunner] {} does not include JOBS_ADMIN after rebuild: {}",
                        constraint, def);
            }
        } catch (Exception verifyErr) {
            log.error("[SchemaFixupRunner] post-rebuild verify on {} failed: {}",
                    constraint, verifyErr.getMessage(), verifyErr);
        }
    }

    /**
     * Rebuild {@code job_postings.job_type} CHECK for the Jobs Admin posting
     * form's combined Full-Time / Internship option.
     */
    private void rebuildJobPostingsJobTypeCheckForCombinedType() {
        final String table = "job_postings";
        final String column = "job_type";
        final String constraint = "job_postings_job_type_check";
        try {
            List<java.util.Map<String, Object>> existing =
                    jdbcTemplate.queryForList(
                            "SELECT con.conname AS name, "
                                    + "pg_get_constraintdef(con.oid) AS def "
                                    + "FROM pg_constraint con "
                                    + "JOIN pg_class cl ON cl.oid = con.conrelid "
                                    + "JOIN pg_attribute att "
                                    + "  ON att.attrelid = cl.oid "
                                    + "  AND att.attnum = ANY(con.conkey) "
                                    + "WHERE cl.relname = ? "
                                    + "  AND att.attname = ? "
                                    + "  AND con.contype = 'c'",
                            table, column);
            for (java.util.Map<String, Object> c : existing) {
                String def = String.valueOf(c.get("def"));
                if (!def.contains("'")) continue;
                String name = String.valueOf(c.get("name"));
                jdbcTemplate.execute(
                        "ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + name);
            }
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {}.{} CHECK DROP failed: {} — root: {}",
                    table, column, e.getMessage(), rootMessage(e), e);
        }

        try {
            jdbcTemplate.execute(
                    "ALTER TABLE " + table + " ADD CONSTRAINT " + constraint
                            + " CHECK (" + column + " IN ('INTERNSHIP','CONTRACT',"
                            + "'FULL_TIME','FULL_TIME_INTERNSHIP'))");
            log.info("[SchemaFixupRunner] rebuilt {} to include FULL_TIME_INTERNSHIP",
                    constraint);
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] {} ADD failed: {} — root: {}",
                    constraint, e.getMessage(), rootMessage(e), e);
        }

        try {
            String def = jdbcTemplate.queryForObject(
                    "SELECT pg_get_constraintdef(c.oid) "
                            + "FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "WHERE t.relname = ? AND c.conname = ?",
                    String.class, table, constraint);
            if (def != null && def.contains("FULL_TIME_INTERNSHIP")) {
                log.info("[SchemaFixupRunner] verified {} includes FULL_TIME_INTERNSHIP",
                        constraint);
            } else {
                log.error("[SchemaFixupRunner] {} does not include FULL_TIME_INTERNSHIP "
                        + "after rebuild: {}", constraint, def);
            }
        } catch (Exception verifyErr) {
            log.error("[SchemaFixupRunner] post-rebuild verify on {} failed: {}",
                    constraint, verifyErr.getMessage(), verifyErr);
        }
    }

    /**
     * Jobs Admin posting form no longer uses StaffingEntity and now accepts a
     * manual Job ID. Keep existing rows intact, but relax the DB constraints
     * that were tied to the old generated-ID/entity-backed flow.
     */
    private void relaxJobPostingsEntityAndJobIdForJobsAdmin() {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE job_postings ALTER COLUMN entity_id DROP NOT NULL");
            log.info("[SchemaFixupRunner] relaxed job_postings.entity_id NOT NULL");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] job_postings.entity_id DROP NOT NULL skipped: {}",
                    e.getMessage());
        }

        try {
            jdbcTemplate.execute(
                    "ALTER TABLE job_postings ALTER COLUMN job_id TYPE VARCHAR(64)");
            log.info("[SchemaFixupRunner] widened job_postings.job_id to VARCHAR(64)");
        } catch (Exception e) {
            log.warn("[SchemaFixupRunner] job_postings.job_id widen skipped: {}",
                    e.getMessage());
        }
    }
}
