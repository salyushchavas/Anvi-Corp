package com.anvicorp.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Bootstrap payload for the SPA's auth context. Phase 3 step 6 adds
 * {@code expectedTrack} so the candidate sidebar can hide the I-983 Training
 * Plan tile for non-STEM-OPT candidates without an extra round-trip.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeResponse(
        String userId,
        String email,
        String fullName,
        String phoneNumber,
        List<String> roles,
        Instant createdAt,
        Boolean emailVerified,
        String applicantId,
        String expectedTrack,
        /** Mirrors {@link AuthResponse#mustChangePassword()} so a tab refresh
         *  still routes to the force-change screen if the user hasn't
         *  cleared the flag yet. */
        Boolean mustChangePassword
) {}
