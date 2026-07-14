package com.anvicorp.api.dto.everify;

import com.anvicorp.api.enums.PhotoMatchResult;
import lombok.*;

/**
 * PATCH-style — every field is optional; null means "no change".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCaseRequest {
    private String caseNumber;
    private Boolean photoMatchRequired;
    private PhotoMatchResult photoMatchResult;
    private Boolean additionalVerificationRequired;
    private String notes;
}
