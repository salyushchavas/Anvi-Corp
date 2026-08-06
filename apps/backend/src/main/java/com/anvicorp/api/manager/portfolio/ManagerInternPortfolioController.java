package com.anvicorp.api.manager.portfolio;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Manager Intern Portfolio HTTP surface — one place for a manager to
 * see every intern across every state (Account created, Onboarding,
 * Active, Exited) with a full read-only drill-down.
 *
 * <p>Read-only by design: no mutation endpoints here — actions live on
 * the ERM / evaluator / trainer surfaces that own the underlying
 * lifecycle. RBAC: MANAGER + SUPER_ADMIN. Server-side exclusion rules
 * enforce {@code il.deleted_at IS NULL} + {@code u.email_verified = true}
 * regardless of client filters.</p>
 */
@RestController
@RequestMapping("/api/v1/manager/intern-portfolio")
@RequiredArgsConstructor
public class ManagerInternPortfolioController {

    private final ManagerInternPortfolioService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerInternPortfolioDtos.PortfolioListPage list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return service.list(caller, search, stage, page, pageSize);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ManagerInternPortfolioDtos.PortfolioDetail detail(
            @PathVariable UUID userId,
            @AuthenticationPrincipal User caller) {
        return service.getDetail(caller, userId);
    }
}
