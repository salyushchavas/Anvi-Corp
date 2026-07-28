package com.anvicorp.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Issues Anvi Corp Applicant IDs in the format {@code SKZ-INT-YYYY-NNNNNN}.
 *
 * NNNNNN is the next value from the Postgres sequence
 * {@code skyzen_applicant_seq} (created by {@code SchemaFixupRunner}), so two
 * concurrent verification requests can never collide on the suffix. The year
 * portion is the calendar year of issuance — the suffix does NOT reset
 * year-over-year (it's a global counter), which means 2027's first ID may be
 * {@code SKZ-INT-2027-001234} rather than {@code SKZ-INT-2027-000001}. That's
 * deliberate: uniqueness is owned by the suffix; the year is informational.
 */
@Component
@RequiredArgsConstructor
public class ApplicantIdGenerator {

    private final JdbcTemplate jdbcTemplate;

    public String nextApplicantId() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT nextval('skyzen_applicant_seq')", Long.class);
        long suffix = n != null ? n : 0L;
        return String.format("ANVI-INT-%d-%06d", LocalDate.now().getYear(), suffix);
    }
}
