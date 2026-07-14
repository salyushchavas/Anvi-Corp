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

/** Intern-side doubt-session API. INTERN role only. */
@RestController
@RequestMapping("/api/v1/intern/doubts")
@RequiredArgsConstructor
public class InternDoubtController {

    private final DoubtRequestService doubtRequestService;

    @PostMapping
    @PreAuthorize("hasRole('INTERN')")
    public DoubtDtos.DoubtResponse create(@RequestBody DoubtDtos.CreateDoubtRequest req,
                                           @AuthenticationPrincipal User caller) {
        return doubtRequestService.createForIntern(req, caller);
    }

    @GetMapping
    @PreAuthorize("hasRole('INTERN')")
    public List<DoubtDtos.DoubtResponse> mine(@AuthenticationPrincipal User caller) {
        return doubtRequestService.listMine(caller);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('INTERN')")
    public DoubtDtos.DoubtResponse one(@PathVariable UUID id,
                                        @AuthenticationPrincipal User caller) {
        return doubtRequestService.getOneForIntern(id, caller);
    }
}
