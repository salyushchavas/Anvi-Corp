package com.anvicorp.api.session.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in {@code GET /api/v1/me/sessions}. Device/browser is parsed from
 * the User-Agent server-side so every client gets the same display.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserSessionResponse {
    private UUID id;
    private Instant createdAt;
    private Instant lastUsedAt;
    private String userAgent;          // raw, for tooltip
    private String deviceLabel;        // "Chrome on macOS", parsed
    private String ip;
    /** True for the session the caller's current access token belongs to. */
    private boolean current;
}
