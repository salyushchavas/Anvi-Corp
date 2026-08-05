package com.anvicorp.api.controller;

import com.anvicorp.api.dto.doubt.DoubtDtos;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.intern.DoubtRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Trainer-side doubt-session API. TRAINER (+ SUPER_ADMIN) only. */
@RestController
@RequestMapping("/api/v1/trainer/doubts")
@RequiredArgsConstructor
public class TrainerDoubtController {

    private final DoubtRequestService doubtRequestService;

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public List<DoubtDtos.DoubtResponse> list(
            @RequestParam(value = "open", defaultValue = "true") boolean openOnly,
            @RequestParam(value = "fromDate", required = false) String fromDate,
            @RequestParam(value = "toDate", required = false) String toDate,
            @AuthenticationPrincipal User caller) {
        // Sticky-month pass-through: past-month view bounds the doubt's
        // createdAt to that month's window; current-month leaves both
        // params absent (byte-identical to the pre-scoping call).
        java.time.Instant fromInstant = (fromDate == null || fromDate.isBlank())
                ? null
                : java.time.LocalDate.parse(fromDate)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        java.time.Instant toInstant = (toDate == null || toDate.isBlank())
                ? null
                : java.time.LocalDate.parse(toDate).plusDays(1)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        return doubtRequestService.listForTrainer(caller, openOnly).stream()
                .filter(r -> fromInstant == null
                        || r.createdAt() == null
                        || !r.createdAt().isBefore(fromInstant))
                .filter(r -> toInstant == null
                        || r.createdAt() == null
                        || r.createdAt().isBefore(toInstant))
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public DoubtDtos.DoubtResponse one(@PathVariable UUID id,
                                        @AuthenticationPrincipal User caller) {
        return doubtRequestService.getOneForTrainer(id, caller);
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public DoubtDtos.DoubtResponse reply(@PathVariable UUID id,
                                          @RequestBody DoubtDtos.ReplyRequest req,
                                          @AuthenticationPrincipal User caller) {
        return doubtRequestService.reply(id, req, caller);
    }

    @PostMapping("/{id}/schedule-session")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public DoubtDtos.DoubtResponse schedule(@PathVariable UUID id,
                                             @RequestBody DoubtDtos.ScheduleSessionRequest req,
                                             @AuthenticationPrincipal User caller) {
        return doubtRequestService.scheduleSession(id, req, caller);
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public DoubtDtos.DoubtResponse resolve(@PathVariable UUID id,
                                            @AuthenticationPrincipal User caller) {
        return doubtRequestService.resolve(id, caller);
    }
}
