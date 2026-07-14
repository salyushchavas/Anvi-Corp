package com.anvicorp.api.dto.i983;

import com.anvicorp.api.enums.DsoApprovalStatus;
import com.anvicorp.api.enums.I983Status;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class I983SummaryResponse {
    private UUID id;
    private UUID candidateId;
    private String candidateName;
    private String entityName;
    private String jobTitle;
    private I983Status status;
    private DsoApprovalStatus dsoApprovalStatus;
    /** Jackson serializes the boolean isEmployerSigned() getter as "employerSigned". */
    private boolean employerSigned;
    /** Jackson serializes the boolean isStudentSigned() getter as "studentSigned". */
    private boolean studentSigned;
    private LocalDate trainingStartDate;
    private Instant createdAt;
    private Instant updatedAt;
}
