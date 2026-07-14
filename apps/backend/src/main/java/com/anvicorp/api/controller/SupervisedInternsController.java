package com.anvicorp.api.controller;

import com.anvicorp.api.dto.supervised.InternSummaryResponse;
import com.anvicorp.api.dto.supervised.SupervisedOverviewResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.SupervisedInternsService;
import com.anvicorp.api.service.SupervisedOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/supervised")
@RequiredArgsConstructor
public class SupervisedInternsController {

    private final SupervisedInternsService supervisedInternsService;
    private final SupervisedOverviewService supervisedOverviewService;

    @GetMapping("/interns")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public List<InternSummaryResponse> listInterns(
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String search) {
        return supervisedInternsService.listHiredInterns(entityId, search);
    }

    /**
     * At-a-glance summary for the intern's My Work header. Always 200 with a
     * fully-formed payload (zeros + nulls when nothing exists). Ownership is
     * derived strictly from the authenticated principal — no client-supplied
     * id is accepted or read.
     */
    @GetMapping("/my/overview")
    @PreAuthorize("hasRole('INTERN')")
    public SupervisedOverviewResponse myOverview(@AuthenticationPrincipal User caller) {
        return supervisedOverviewService.forUser(caller);
    }
}
