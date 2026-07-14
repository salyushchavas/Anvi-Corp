package com.anvicorp.api.controller;

import com.anvicorp.api.dto.compliance.ComplianceOverviewResponse;
import com.anvicorp.api.service.ComplianceOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/compliance")
@RequiredArgsConstructor
public class ComplianceOverviewController {

    private final ComplianceOverviewService service;

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER')")
    public ComplianceOverviewResponse getOverview() {
        return service.getOverview();
    }
}
