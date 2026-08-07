package com.anvicorp.api.profile;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.profile.EducationDtos.EducationListResponse;
import com.anvicorp.api.profile.EducationDtos.EducationResponse;
import com.anvicorp.api.profile.EducationDtos.EducationUpsertRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * B2 profile expansion — CRUD for the intern's education entries.
 * All endpoints derive ownership from {@code @AuthenticationPrincipal};
 * no path variable ever carries a candidate id.
 */
@RestController
@RequestMapping("/api/v1/candidates/me/educations")
@RequiredArgsConstructor
public class EducationController {

    private final EducationService educationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public EducationListResponse list(@AuthenticationPrincipal User caller) {
        return educationService.listForUser(caller);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public EducationResponse create(
            @Valid @RequestBody EducationUpsertRequest req,
            @AuthenticationPrincipal User caller) {
        return educationService.create(caller, req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public EducationResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody EducationUpsertRequest req,
            @AuthenticationPrincipal User caller) {
        return educationService.update(caller, id, req);
    }

    @PostMapping("/{id}/set-primary")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public EducationResponse setPrimary(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return educationService.setPrimary(caller, id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal User caller) {
        educationService.delete(caller, id);
    }
}
