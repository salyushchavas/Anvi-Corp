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
    public List<TrainerGroupSessionResponse> list(@AuthenticationPrincipal User caller) {
        return service.listForTrainer(caller);
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
