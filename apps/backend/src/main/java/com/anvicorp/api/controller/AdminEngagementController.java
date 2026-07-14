package com.anvicorp.api.controller;

import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.service.EngagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SUPER_ADMIN-only engagement repair surface. Targeted on-demand fixes that
 * are too narrow for the boot-time {@code EngagementBackfillRunner} sweep
 * (which requires the {@code app.engagement.backfill-enabled} flag + a
 * restart).
 *
 * <p>{@code POST /api/v1/admin/engagements/heal} — body {@code {"email":"..."}}.
 * Creates the missing {@link com.anvicorp.api.entity.Engagement} for the
 * intern identified by email, reusing {@link
 * EngagementService#healForUserEmail}. Idempotent: a repeat call for the
 * same email returns {@code created=false} with the existing engagement id.
 * Intended for orphans created by the IDMS sign flow before
 * {@code OfferIdmsSigningService.finalizeIdmsSigning} was wired to create
 * the engagement at sign-time.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/engagements")
@RequiredArgsConstructor
public class AdminEngagementController {

    private final EngagementService engagementService;

    @PostMapping("/heal")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public EngagementService.HealResult heal(@RequestBody HealRequest req) {
        if (req == null || req.email() == null || req.email().isBlank()) {
            throw new BadRequestException("email is required");
        }
        return engagementService.healForUserEmail(req.email());
    }

    public record HealRequest(String email) {}
}
