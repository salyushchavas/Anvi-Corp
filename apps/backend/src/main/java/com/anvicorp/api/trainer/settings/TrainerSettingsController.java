package com.anvicorp.api.trainer.settings;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.trainer.settings.TrainerSettingsService.TrainerSettingsDto;
import com.anvicorp.api.trainer.settings.TrainerSettingsService.UpdateSettingsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Trainer Phase 4 — Settings HTTP surface. */
@RestController
@RequestMapping("/api/v1/trainer/settings")
@RequiredArgsConstructor
@Slf4j
public class TrainerSettingsController {

    private final TrainerSettingsService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public TrainerSettingsDto get(@AuthenticationPrincipal User caller) {
        return service.get(caller);
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public TrainerSettingsDto update(@RequestBody UpdateSettingsRequest req,
                                     @AuthenticationPrincipal User caller) {
        return service.update(req, caller);
    }
}
