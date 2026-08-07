package com.anvicorp.api.erm.idms;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Pipeline-restore — the intern's offer-letter landing endpoint.
 *
 * <p>Distinct namespace from {@code /api/v1/intern/agreements} so the
 * offer-family carries its own contract even though the underlying
 * fill/sign/submit posts still go to the generic agreements routes
 * (they act on any {@link DocumentInstance} by id).</p>
 *
 * <p>Returns the intern's <b>current</b> offer-family instance detail
 * (newest non-VOIDED {@code templateKey LIKE 'OFFER_%'} row), or
 * {@code {"offer": null}} when no offer exists yet. The response shape
 * mirrors {@link DocumentInstanceDtos.InstanceDetail} so the frontend
 * fill/sign page can render off the same DTO as the agreements detail.</p>
 */
@RestController
@RequestMapping("/api/v1/intern/offer-letter")
@RequiredArgsConstructor
public class InternOfferLetterController {

    private final DocumentInstanceService instanceService;

    @GetMapping
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> currentOffer(@AuthenticationPrincipal User caller) {
        if (caller == null) throw new ForbiddenException("Sign in first.");
        Map<String, Object> body = new HashMap<>();
        body.put("offer",
                instanceService.findCurrentOfferLetterForIntern(caller).orElse(null));
        return body;
    }
}
