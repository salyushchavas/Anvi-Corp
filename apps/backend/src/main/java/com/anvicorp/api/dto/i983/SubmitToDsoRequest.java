package com.anvicorp.api.dto.i983;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmitToDsoRequest {

    /** Optional internal note about the submission — recorded in the audit log, not sent to DSO. */
    private String submissionNotes;
}
