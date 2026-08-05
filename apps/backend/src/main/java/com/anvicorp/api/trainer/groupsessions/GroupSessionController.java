package com.anvicorp.api.trainer.groupsessions;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.trainer.groupsessions.GroupSessionDtos.CreateGroupSessionRequest;
import com.anvicorp.api.trainer.groupsessions.GroupSessionDtos.InternGroupSessionResponse;
import com.anvicorp.api.trainer.groupsessions.GroupSessionDtos.TrainerGroupSessionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * HTTP surface for Trainer-hosted group sessions.
 *
 * <p>Trainer endpoints under {@code /api/v1/trainer/group-sessions/*}
 * carry the HOST start URL and full participant list. The intern
 * endpoint at {@code /api/v1/intern/group-sessions} intentionally
 * strips the host start URL.</p>
 */
@RestController
@RequiredArgsConstructor
public class GroupSessionController {

    private final GroupSessionService service;

    // ── Trainer ────────────────────────────────────────────────────────────

    @PostMapping("/api/v1/trainer/group-sessions")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public TrainerGroupSessionResponse create(
            @Valid @RequestBody CreateGroupSessionRequest req,
            @AuthenticationPrincipal User caller) {
        return service.create(req, caller);
    }

    @GetMapping("/api/v1/trainer/group-sessions")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public List<TrainerGroupSessionResponse> list(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @AuthenticationPrincipal User caller) {
        // Sticky-month pass-through: when the trainer picks a past month,
        // the frontend sends fromDate/toDate bounded to that month so the
        // list scopes to sessions SCHEDULED in that window. Missing params
        // = current month = byte-identical to the pre-scoping call.
        return service.listForTrainer(caller).stream()
                .filter(r -> fromDate == null || fromDate.isBlank()
                        || r.scheduledAt() == null
                        || !r.scheduledAt().isBefore(
                                java.time.LocalDate.parse(fromDate)
                                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
                .filter(r -> toDate == null || toDate.isBlank()
                        || r.scheduledAt() == null
                        || r.scheduledAt().isBefore(
                                java.time.LocalDate.parse(toDate).plusDays(1)
                                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
                .toList();
    }

    @GetMapping("/api/v1/trainer/group-sessions/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public TrainerGroupSessionResponse detail(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        return service.getDetail(id, caller);
    }

    @PostMapping("/api/v1/trainer/group-sessions/{id}/cancel")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public TrainerGroupSessionResponse cancel(
            @PathVariable UUID id, @AuthenticationPrincipal User caller) {
        return service.cancel(id, caller);
    }

    // ── Intern ─────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/intern/group-sessions")
    @PreAuthorize("hasRole('INTERN')")
    public List<InternGroupSessionResponse> mine(@AuthenticationPrincipal User caller) {
        return service.listForIntern(caller);
    }
}
