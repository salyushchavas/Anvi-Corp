package com.anvicorp.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Issues Anvi Corp Job IDs in the format {@code SKZ-JOB-YYYY-NNNNNN}.
 *
 * <p>Mirrors {@link ApplicantIdGenerator} — NNNNNN is the next value from
 * the Postgres sequence {@code skyzen_job_seq} (created by
 * {@code SchemaFixupRunner}), so two concurrent posting-create requests
 * can never collide on the suffix. The year is informational; the suffix
 * is a global counter that does NOT reset year-over-year.</p>
 *
 * <p>Immutable once stamped — the {@code job_id} column on
 * {@code job_postings} is {@code updatable = false}.</p>
 */
@Component
@RequiredArgsConstructor
public class JobIdGenerator {

    private final JdbcTemplate jdbcTemplate;

    public String nextJobId() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT nextval('skyzen_job_seq')", Long.class);
        long suffix = n != null ? n : 0L;
        return String.format("SKZ-JOB-%d-%06d", LocalDate.now().getYear(), suffix);
    }
}
