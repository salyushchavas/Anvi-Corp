package com.anvicorp.api.erm.settings;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** ERM Phase 7 — Settings HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm/settings")
@RequiredArgsConstructor
public class ErmSettingsController {

    private final ErmSettingsService service;

    @GetMapping("/templates")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmSettingsDtos.TemplateRow> listTemplates() {
        return service.listTemplates();
    }

    @GetMapping("/templates/{key}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmSettingsDtos.TemplateRow getTemplate(@PathVariable String key) {
        return service.getTemplate(key);
    }

    @PutMapping("/templates/{key}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmSettingsDtos.TemplateRow updateTemplate(
            @PathVariable String key,
            @RequestBody ErmSettingsDtos.TemplateUpdateRequest req,
            @AuthenticationPrincipal User caller) {
        return service.updateTemplate(key, req, caller);
    }

    @PostMapping("/templates/{key}/restore-default")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmSettingsDtos.TemplateRow restoreDefault(
            @PathVariable String key,
            @AuthenticationPrincipal User caller) {
        return service.restoreDefault(key, caller);
    }

    @PostMapping("/templates/{key}/preview")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmSettingsDtos.TemplatePreviewResponse preview(
            @PathVariable String key,
            @RequestBody(required = false) ErmSettingsDtos.TemplatePreviewRequest req) {
        return service.preview(key, req);
    }

    @GetMapping("/reason-codes")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmSettingsDtos.ReasonCodeGroup> reasonCodes() {
        return service.listReasonCodes();
    }

    @GetMapping("/workload")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<ErmSettingsDtos.WorkloadRow> workload(
            @AuthenticationPrincipal User caller) {
        return service.listWorkload(caller);
    }

    @GetMapping("/workload/me")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmSettingsDtos.WorkloadRow myWorkload(
            @AuthenticationPrincipal User caller) {
        return service.myWorkload(caller);
    }
}
