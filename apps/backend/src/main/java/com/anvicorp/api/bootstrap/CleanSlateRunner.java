package com.anvicorp.api.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 0 opt-in wipe of transactional data so the Phase 1 mode engine can
 * drive a fresh journey. Preserves user identity ({@code users},
 * {@code user_roles}, {@code password_reset_tokens}), the job catalog
 * ({@code job_postings}, {@code entities}), and the {@code audit_logs}
 * forensic trail.
 *
 * <h2>Operational</h2>
 * <ul>
 *   <li>Gated by {@code app.bootstrap.clean-slate-enabled=false} (default).
 *       Operator sets it to TRUE, redeploys, sets it back to FALSE — never
 *       leave the flag on in steady-state.</li>
 *   <li>Runs at {@code @Order(10)} — after the schema-fixup family so every
 *       table exists, before the test-data seeders so any newly-created
 *       test rows survive.</li>
 *   <li>Each DELETE is wrapped in its own try/catch so a missing table on
 *       a partial deployment doesn't halt the wipe.</li>
 * </ul>
 */
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class CleanSlateRunner implements ApplicationRunner {

    private static final String LOG_TAG = "[CleanSlateRunner]";

    /**
     * Tables to wipe, in FK-safe order (leaves first, roots last). Tables
     * that may not exist on every deployment fail silently with a debug
     * note via the per-table try/catch.
     */
    private static final List<String> WIPE_ORDER = List.of(
            // Notifications + materials engagement
            "sent_notifications",
            "material_acknowledgements",
            "weekly_reports",
            "weekly_materials",
            // Evaluations
            "evaluation_rubric_scores",
            "evaluation_self_reviews",
            "evaluations",
            "evaluation_sessions",
            // Q&A + timesheets
            "qa_sessions",
            "timesheet_days",
            "timesheets",
            // Workspace / submissions / project tasks
            "workspace_submitted_files",
            "workspace_submissions",
            "project_submissions",
            "project_workspace_files",
            "project_tasks",
            // Project graph
            "project_repositories",
            "project_assignments",
            "projects",
            // Onboarding / compliance
            "onboarding_tasks",
            "onboarding_packets",
            "intern_lifecycles",
            "i983_forms",
            "training_plans",
            "everify_cases",
            "i9_forms",
            // Offers + interviews + screenings + applications
            "offers",
            "offer_envelopes",
            "interview_scorecards",
            "interviews",
            "screening_answers",
            "screenings",
            "screening_questions",
            "applications",
            // Work assignments (legacy)
            "work_assignments",
            // Engagements + resumes + candidates
            "engagements",
            "resumes",
            "candidates"
            // INTENTIONALLY PRESERVED:
            //   users, user_roles, password_reset_tokens, user_sessions,
            //   job_postings, entities, audit_logs
    );

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.bootstrap.clean-slate-enabled:false}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("{} disabled — skipping.", LOG_TAG);
            return;
        }
        log.warn("{} ENABLED — wiping transactional data. Set "
                + "app.bootstrap.clean-slate-enabled=false and redeploy after this run.",
                LOG_TAG);

        Map<String, Long> deleted = new LinkedHashMap<>();
        long total = 0;
        int failed = 0;

        for (String table : WIPE_ORDER) {
            try {
                long n = jdbcTemplate.update("DELETE FROM " + table);
                deleted.put(table, n);
                total += n;
                if (n > 0) {
                    log.info("{} {}: deleted {} row(s)", LOG_TAG, table, n);
                }
            } catch (Exception e) {
                failed++;
                log.debug("{} {}: wipe skipped (table missing or in-use): {}",
                        LOG_TAG, table, e.getMessage());
            }
        }

        log.warn("{} done. tables_wiped={} total_rows_deleted={} skipped={}",
                LOG_TAG, deleted.size(), total, failed);
        log.warn("{} REMINDER: set app.bootstrap.clean-slate-enabled=false "
                + "and redeploy to prevent accidental re-wipe on next boot.", LOG_TAG);
    }
}
