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
            @AuthenticationPrincipal User caller) {
        return doubtRequestService.listForTrainer(caller, openOnly);
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
