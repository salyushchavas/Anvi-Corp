package com.anvicorp.api.manager;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Manager Phase 1 — read-only pipeline list. Mirrors the ERM application
 *  list query but joins {@code intern_lifecycles} + the latest
 *  {@code interviews} row + the latest {@code offers} row so the Manager
 *  sees ERM owner, interview state, and expected start date in one row.
 *
 *  <p>Portfolio-wide for MANAGER + SUPER_ADMIN. Filters: technology,
 *  stage, ermOwner.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerPipelineService {

    /** ERM-style "post-shortlist" view excludes the very early funnel.
     *  Used as the default when the UI doesn't narrow on stage. */
    private static final Set<String> POST_SHORTLIST_STAGES = new LinkedHashSet<>(Arrays.asList(
            "SHORTLISTED",
            "INTERVIEW_SCHEDULED",
            "INTERVIEWED",
            "SELECTED_CONDITIONAL",
            "OFFERED",
            "ACCEPTED"));

    private final JdbcTemplate jdbc;

    public ManagerDtos.PipelineResponse list(
            User caller,
            String stage,
            String technology,
            UUID ermOwner,
            String search,
            int page,
            int pageSize) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        int offset = safePage * safeSize;

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (stage != null && !stage.isBlank() && !"ALL".equals(stage)) {
            where.append(" AND a.status = ? ");
            params.add(stage);
        } else {
            // Default: post-shortlist surface; the spec calls this section
            // "Applicant Pipeline" and folds Interviews + Offers into it.
            where.append(" AND a.status IN (");
            where.append(String.join(",", POST_SHORTLIST_STAGES.stream().map(s -> "?").toList()));
            where.append(") ");
            params.addAll(POST_SHORTLIST_STAGES);
        }
        // technology — JobPosting has no technology column yet; the field
        // stays available so the filter wire is ready when it lands.
        if (technology != null && !technology.isBlank()) {
            where.append(" AND LOWER(COALESCE(jp.technology, '')) = LOWER(?) ");
            params.add(technology);
        }
        if (ermOwner != null) {
            where.append(" AND a.erm_owner_id = ? ");
            params.add(ermOwner);
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase() + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? "
                    + " OR LOWER(u.email) LIKE ? "
                    + " OR LOWER(COALESCE(u.applicant_id,'')) LIKE ?) ");
            params.add(like); params.add(like); params.add(like);
        }

        long total = 0L;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) "
                            + "  FROM applications a "
                            + "  JOIN candidates c ON c.id = a.candidate_id "
                            + "  JOIN users u      ON u.id = c.user_id "
                            + "  JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + where,
                    Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ManagerPipeline] count failed: {}", e.getMessage());
        }

        String sql = ""
                + "SELECT a.id AS app_id, a.status, a.last_decision_at, a.applied_at, "
                + "       a.erm_owner_id, "
                + "       u.full_name, u.applicant_id, u.email, "
                + "       jp.title AS job_title, jp.job_type, "
                + "       (SELECT u2.full_name FROM users u2 WHERE u2.id = a.erm_owner_id) AS erm_owner_name, "
                + "       (SELECT i.status FROM interviews i "
                + "          WHERE i.application_id = a.id "
                + "          ORDER BY i.scheduled_at DESC LIMIT 1) AS latest_interview_status, "
                + "       (SELECT o.start_date FROM offers o "
                + "          WHERE o.application_id = a.id "
                + "          ORDER BY o.created_at DESC LIMIT 1) AS expected_start_date "
                + "  FROM applications a "
                + "  JOIN candidates c ON c.id = a.candidate_id "
                + "  JOIN users u      ON u.id = c.user_id "
                + "  JOIN job_postings jp ON jp.id = a.job_posting_id "
                + where
                + " ORDER BY a.last_decision_at DESC NULLS LAST, a.applied_at DESC NULLS LAST, a.id DESC "
                + " LIMIT " + safeSize + " OFFSET " + offset;

        List<ManagerDtos.PipelineRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(sql, params.toArray(), (rs, n) -> {
                Instant appliedAt = rs.getTimestamp("applied_at") != null
                        ? rs.getTimestamp("applied_at").toInstant() : null;
                long ageDays = appliedAt != null
                        ? ChronoUnit.DAYS.between(appliedAt, Instant.now()) : 0L;
                return new ManagerDtos.PipelineRow(
                        UUID.fromString(rs.getString("app_id")),
                        rs.getString("full_name"),
                        rs.getString("applicant_id"),
                        rs.getString("email"),
                        rs.getString("job_title"),
                        rs.getString("job_type"),
                        null, // technology — not on JobPosting today; wired for the future
                        rs.getString("status"),
                        rs.getString("latest_interview_status"),
                        rs.getTimestamp("last_decision_at") != null
                                ? rs.getTimestamp("last_decision_at").toInstant() : null,
                        ageDays,
                        nullableUuid(rs.getString("erm_owner_id")),
                        rs.getString("erm_owner_name"),
                        rs.getDate("expected_start_date") != null
                                ? rs.getDate("expected_start_date").toLocalDate() : null);
            });
        } catch (Exception e) {
            log.warn("[ManagerPipeline] list failed: {}", e.getMessage());
        }

        int totalPages = safeSize == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return new ManagerDtos.PipelineResponse(
                rows, safePage, safeSize, total, totalPages, loadFilterOptions());
    }

    /** Distinct ERM owners + the canonical stage + job-type lists.
     *  Tiny — caller hits this once per page mount, not per row. */
    public ManagerDtos.FilterOptions filterOptions() {
        return loadFilterOptions();
    }

    private ManagerDtos.FilterOptions loadFilterOptions() {
        List<String> stages = new ArrayList<>();
        for (ApplicationStatus s : ApplicationStatus.values()) stages.add(s.name());

        List<String> jobTypes = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT job_type FROM job_postings "
                            + " WHERE job_type IS NOT NULL "
                            + " ORDER BY job_type",
                    rs -> { jobTypes.add(rs.getString(1)); });
        } catch (Exception e) {
            log.warn("[ManagerPipeline] job_type distinct failed: {}", e.getMessage());
        }

        List<ManagerDtos.ErmOwnerOption> owners = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT DISTINCT u.id, u.full_name "
                            + "  FROM applications a "
                            + "  JOIN users u ON u.id = a.erm_owner_id "
                            + " WHERE a.erm_owner_id IS NOT NULL "
                            + " ORDER BY u.full_name",
                    rs -> {
                        owners.add(new ManagerDtos.ErmOwnerOption(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("full_name")));
                    });
        } catch (Exception e) {
            log.warn("[ManagerPipeline] erm owner distinct failed: {}", e.getMessage());
        }

        return new ManagerDtos.FilterOptions(stages, jobTypes, owners);
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
