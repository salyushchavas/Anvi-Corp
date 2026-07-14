package com.anvicorp.api.dto.i983;

import lombok.*;

import java.util.UUID;

/**
 * Either {@code candidateId} OR {@code applicationId} must be present. The
 * service derives the missing one from the application's candidate FK when
 * candidateId is omitted.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateI983Request {

    /** Optional when applicationId is provided — service derives from the application. */
    private UUID candidateId;

    /** Optional — if known, helps auto-fill more from the application context. */
    private UUID applicationId;

    /** Optional — if known, snapshots compensation + dates from the offer. */
    private UUID offerId;
}
