package com.anvicorp.api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Issues Anvi Corp Employee IDs in the format {@code ANVI-EMP-YYYY-NNNNNN}.
 *
 * <p>NNNNNN is the next value from the Postgres sequence
 * {@code skyzen_employee_seq} (created by {@code SchemaFixupRunner}); the year
 * portion is the UTC year of issuance. The sequence name is left at its
 * Skyzen-era spelling to preserve the counter across the rebrand — only the
 * emitted string prefix moves. Two concurrent hire paths can never collide on
 * the suffix. Uniqueness is owned by the suffix; the year is informational and
 * does NOT reset year-over-year.</p>
 *
 * <p>Sibling of {@link ApplicantIdGenerator}. Extracted from the private
 * {@code nextEmployeeId} on {@code OfferIdmsSigningService} so both the
 * offer-signing path and the direct-onboarding path mint IDs the same way with
 * no duplicated JDBC.</p>
 */
@Component
@RequiredArgsConstructor
public class EmployeeIdGenerator {

    private final JdbcTemplate jdbcTemplate;

    public String nextEmployeeId() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT nextval('skyzen_employee_seq')", Long.class);
        long val = n == null ? 1000 : n;
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        return String.format("ANVI-EMP-%d-%06d", year, val);
    }
}
