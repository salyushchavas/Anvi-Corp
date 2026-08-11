package com.anvicorp.api.manager.termination;

import com.anvicorp.api.dto.exit.ExitDtos;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.service.ExitService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * B3 remainder — a Manager-scoped shortcut that terminates one of the
 * Manager's own direct-report interns through the same
 * {@link ExitService#initiate} path the ERM cockpit uses, with
 * {@code exitType} pinned to {@code TERMINATED}.
 *
 * <p><b>Why a wrapper.</b> The ERM's exit-initiate endpoint already
 * supports the TERMINATED type — but it lives under
 * {@code /api/v1/erm/exits/initiate} and is ERM/SUPER_ADMIN only. The
 * Manager needs a first-class action they can trigger from their own
 * dashboard without hopping into the ERM cockpit. This controller lets
 * that happen while reusing the same service layer, checklist seeding,
 * lifecycle advancement, audit, and event fan-out — every downstream
 * consumer of {@code ExitInitiatedEvent} sees Manager-triggered
 * terminations identically to ERM-triggered ones.</p>
 *
 * <p><b>Ownership gate.</b> A MANAGER can only terminate their own
 * direct reports (checked via {@code intern_lifecycles.manager_id}).
 * SUPER_ADMIN bypasses the ownership check but keeps the {@code
 * exitType=TERMINATED} pinning — for a broader picker use the ERM
 * cockpit's InitiateExitModal.</p>
 *
 * <p>Request shape mirrors the ERM's initiate modal for the terminate
 * path: {@code reasonCode} (must be an {@code EXIT_TERMINATED_*} code)
 * plus a free-text reason ≥ 30 chars ({@link ExitService} enforces the
 * length rule for the TERMINATED type). {@code exitDate} defaults to
 * today when omitted so a hallway-conversation termination doesn't need
 * the Manager to hunt for a date picker; {@code internVisibleSummary}
 * is optional.</p>
 */
@RestController
@RequestMapping("/api/v1/manager/interns")
@RequiredArgsConstructor
public class ManagerTerminationController {

    private final ExitService exitService;
    private final InternLifecycleRepository internLifecycleRepository;

    public record TerminateRequest(
            /** Required — must be an EXIT_TERMINATED_* reason code (checked
             *  in-service by the reason-code validator on ExitService.initiate).
             *  Bound to the {@link com.anvicorp.api.erm.ReasonCode} enum. */
            @NotBlank @Size(max = 80) String reasonCode,
            /** Required free-text elaboration — ExitService enforces ≥ 30
             *  chars for TERMINATED so a bare "performance issue" won't
             *  land in the record. */
            @NotBlank @Size(min = 30, max = 2000) String reasonText,
            /** Optional — defaults to today when omitted. Cannot be more
             *  than 90 days in the future (ExitService constraint). */
            LocalDate exitDate,
            /** Optional intern-visible summary written to the exit card
             *  the intern sees on their dashboard. Kept short and neutral;
             *  the reasonText itself is ERM-only. */
            @Size(max = 500) String internVisibleSummary,
            /** Optional — defaults to true. Setting false marks the intern
             *  as ineligible for future rehire in the exit record. */
            Boolean rehireEligible
    ) {}

    @PostMapping("/{lifecycleId}/terminate")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ExitDtos.ExitRecordResponse terminate(
            @PathVariable UUID lifecycleId,
            @RequestBody TerminateRequest req,
            @AuthenticationPrincipal User caller) {
        if (caller == null) throw new ForbiddenException("caller required");
        if (req == null) throw new BadRequestException("request body is required");
        if (req.reasonCode() == null || req.reasonCode().isBlank()) {
            throw new BadRequestException("reasonCode is required");
        }
        // Ownership gate — a MANAGER can only terminate their own direct
        // reports. SUPER_ADMIN bypasses.
        InternLifecycle lc = internLifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        boolean isSuperAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (!isSuperAdmin) {
            if (lc.getManagerId() == null || !lc.getManagerId().equals(caller.getId())) {
                throw new ForbiddenException(
                        "You can only terminate interns who report to you.");
            }
        }
        // Force exitType=TERMINATED; combine reasonCode + reasonText into
        // the exitReason field the service already validates (≥30 chars).
        // Preserve reasonCode as a machine-readable prefix so the exit
        // record still exposes the structured taxonomy alongside the
        // free-text rationale — matches the shape ERM's InitiateExitModal
        // produces when a reason code is chosen.
        String combinedReason = "[" + req.reasonCode() + "] " + req.reasonText().trim();
        LocalDate effective = req.exitDate() != null ? req.exitDate() : LocalDate.now();
        ExitDtos.CreateExitRecordRequest delegated =
                new ExitDtos.CreateExitRecordRequest(
                        lifecycleId,
                        "TERMINATED",
                        effective,
                        combinedReason,
                        req.internVisibleSummary(),
                        req.rehireEligible());
        return exitService.initiate(delegated, caller);
    }
}
