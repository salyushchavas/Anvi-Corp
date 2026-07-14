package com.anvicorp.api.dto.i983;

import com.anvicorp.api.enums.CompensationFrequency;
import com.anvicorp.api.enums.DegreeLevel;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * PATCH-style. Every field is optional; null means "no change". Only valid
 * when plan.status is DRAFT or AMENDMENT_REQUESTED.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateI983Request {

    // Section 1 — Student
    private String studentLastName;
    private String studentFirstName;
    private String studentMiddleName;
    private String sevisId;
    private String uscisNumber;
    private String studentEmail;
    private String degreeAwarded;
    private DegreeLevel degreeLevel;
    private String universityName;
    private String universityCipCode;
    private LocalDate dateOfDegreeAward;
    private LocalDate optStartDate;
    private LocalDate optEndDate;

    // Section 2 — Employer
    private String employerName;
    private String employerEin;
    private String employerAddress;
    private String employerWebsite;
    private String employerNaicsCode;
    private Integer employerNumberOfFullTimeEmployees;
    private String employerOfficialName;
    private String employerOfficialTitle;
    private String employerOfficialEmail;
    private String employerOfficialPhone;

    // Section 3 — Training Program
    private String jobTitle;
    private LocalDate trainingStartDate;
    private LocalDate trainingEndDate;
    private Integer hoursPerWeek;
    private BigDecimal compensationAmount;
    private CompensationFrequency compensationFrequency;
    private String compensationCurrency;
    private String supervisorName;
    private String supervisorTitle;
    private String supervisorEmail;
    private String supervisorPhone;

    // Section 4 — Narrative
    private String trainingProgramDescription;
    private String howTrainingRelatesToDegree;
    private String trainingGoalsAndObjectives;
    private String performanceEvaluationMethod;
    private String reportingRequirements;
    private String skillsKnowledgeLearned;
    private String resourcesEquipmentMaterials;
    private String supervisorCommitments;
}
