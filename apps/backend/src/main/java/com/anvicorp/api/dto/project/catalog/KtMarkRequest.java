package com.anvicorp.api.dto.project.catalog;

import jakarta.validation.constraints.Size;

/**
 * Trainer payload for {@code POST /api/v1/projects/{id}/kt-done}. Both
 * fields are optional — the act of POSTing is itself the "KT done" mark.
 * The link, when supplied, is validated as an absolute http/https URL
 * server-side; notes are free-text up to 5000 chars.
 */
public record KtMarkRequest(
        @Size(max = 2048) String meetingLink,
        @Size(max = 5000) String notes
) {}
