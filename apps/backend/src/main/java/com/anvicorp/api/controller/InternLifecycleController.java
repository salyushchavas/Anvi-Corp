package com.anvicorp.api.controller;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.InternActivationJob;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 — ERM override endpoint for manual intern activation (bypasses
 * the start-date gate). The scheduled {@link InternActivationJob} handles
 * the standard automatic flip.
 */
@RestController
@RequestMapping("/api/v1/intern-lifecycles")
@RequiredArgsConstructor
public class InternLifecycleController {

    private final InternLifecycleRepository internLifecycleRepository;
    private final UserRepository userRepository;
    private final InternActivationJob activationJob;

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public Map<String, Object> activate(@PathVariable UUID id,
                                         @AuthenticationPrincipal User actor) {
        InternLifecycle lc = internLifecycleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + id));
        User user = userRepository.findById(lc.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for lifecycle " + id));
        boolean advanced = activationJob.activateNow(user, actor != null ? actor.getId() : null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activated", advanced);
        body.put("userId", user.getId());
        body.put("lifecycleStatus", user.getLifecycleStatus());
        body.put("internLifecycleId", lc.getId());
        body.put("activeStatus", lc.getActiveStatus());
        body.put("startedAt", lc.getStartedAt());
        return body;
    }

    /**
     * ERM Pass 2 — set / clear the joining_date that gates auto-activation.
     * Accepts {@code {"joiningDate": "2026-07-01"}} or {@code null} to
     * clear. Server is permissive on lifecycle state: ERM can pre-set
     * the date earlier in the funnel (it just won't fire activation
     * until the lifecycle reaches ONBOARDING_ACCEPTED + offer is
     * signed), and clearing it is allowed pre-activation. Once the
     * intern is already ACTIVE_INTERN, the date is informational only —
     * we still save it so the audit trail is intact, but no scan flip
     * will happen.
     */
    @PostMapping("/{id}/joining-date")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public Map<String, Object> setJoiningDate(@PathVariable UUID id,
                                               @RequestBody Map<String, String> body) {
        InternLifecycle lc = internLifecycleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + id));
        String raw = body == null ? null : body.get("joiningDate");
        java.time.LocalDate parsed = null;
        if (raw != null && !raw.isBlank()) {
            try {
                parsed = java.time.LocalDate.parse(raw.trim());
            } catch (java.time.format.DateTimeParseException ex) {
                throw new BadRequestException(
                        "joiningDate must be ISO yyyy-MM-dd (got: " + raw + ")");
            }
        }
        lc.setJoiningDate(parsed);
        internLifecycleRepository.save(lc);

        // If the new date is today/past AND lifecycle is ready, try the
        // synchronous flip so ERM sees the activation right away instead
        // of waiting up to 10 minutes for the next scan.
        if (parsed != null && !parsed.isAfter(java.time.LocalDate.now())) {
            try {
                User user = userRepository.findById(lc.getUserId()).orElse(null);
                if (user != null) activationJob.tryActivateIfReady(user);
            } catch (Exception ignored) {
                /* non-fatal — the scan will retry */
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("internLifecycleId", lc.getId());
        resp.put("joiningDate", lc.getJoiningDate());
        return resp;
    }

    // ── Phase 5 — ERM wires trainer / evaluator / manager assignments ────

    @PostMapping("/{id}/assign-trainer")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public Map<String, Object> assignTrainer(@PathVariable UUID id,
                                              @RequestBody Map<String, String> body) {
        return assignRole(id, body, "trainerUserId", UserRole.TRAINER, "trainer_id");
    }

    @PostMapping("/{id}/assign-evaluator")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public Map<String, Object> assignEvaluator(@PathVariable UUID id,
                                                @RequestBody Map<String, String> body) {
        // EVALUATOR was renamed to TRAINER in some earlier refactor; the
        // doc-spec ROLE for "evaluator" here is REPORTING_MANAGER per the
        // six-role taxonomy. Accept either token from the wire and map back.
        return assignRole(id, body, "evaluatorUserId",
                UserRole.REPORTING_MANAGER, "evaluator_id");
    }

    @PostMapping("/{id}/assign-manager")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public Map<String, Object> assignManager(@PathVariable UUID id,
                                              @RequestBody Map<String, String> body) {
        return assignRole(id, body, "managerUserId", UserRole.MANAGER, "manager_id");
    }

    /**
     * Phase C — lightweight directory of active MANAGER users for the
     * ERM AssignManager dropdown. ERM-gated since the only caller today
     * is the ERM roster's "Assign manager" affordance for "no manager"
     * interns; SUPER_ADMIN bypasses too.
     */
    @GetMapping("/eligible-managers")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public java.util.List<Map<String, Object>> eligibleManagers() {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        try {
            for (User u : userRepository.findByRole(UserRole.MANAGER)) {
                if (u == null || u.getId() == null) continue;
                if (!Boolean.TRUE.equals(u.getActive())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("userId", u.getId());
                row.put("fullName", u.getFullName());
                row.put("email", u.getEmail());
                out.add(row);
            }
        } catch (Exception ignored) {}
        out.sort((a, b) -> {
            String an = String.valueOf(a.getOrDefault("fullName", ""));
            String bn = String.valueOf(b.getOrDefault("fullName", ""));
            return an.compareToIgnoreCase(bn);
        });
        return out;
    }

    private Map<String, Object> assignRole(UUID lifecycleId,
                                            Map<String, String> body,
                                            String bodyKey,
                                            UserRole requiredRole,
                                            String column) {
        String raw = body == null ? null : body.get(bodyKey);
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(bodyKey + " is required");
        }
        UUID targetUserId;
        try {
            targetUserId = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(bodyKey + " must be a UUID");
        }
        InternLifecycle lc = internLifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + targetUserId));
        if (target.getRoles() == null || !target.getRoles().contains(requiredRole)) {
            throw new BadRequestException(
                    "User " + targetUserId + " does not hold role " + requiredRole);
        }
        switch (column) {
            case "trainer_id"   -> lc.setTrainerId(targetUserId);
            case "evaluator_id" -> lc.setEvaluatorId(targetUserId);
            case "manager_id"   -> lc.setManagerId(targetUserId);
            default             -> throw new BadRequestException("unknown column " + column);
        }
        internLifecycleRepository.save(lc);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("lifecycleId", lc.getId());
        resp.put("userId", lc.getUserId());
        resp.put("trainerId", lc.getTrainerId());
        resp.put("evaluatorId", lc.getEvaluatorId());
        resp.put("managerId", lc.getManagerId());
        resp.put("ermId", lc.getErmId());
        return resp;
    }
}
