package com.anvicorp.api.profile;

import com.anvicorp.api.enums.DegreeLevel;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the {@code /api/v1/candidates/me/educations} surface. Response
 * shape is stable — the frontend Profile Education tab renders the {@code
 * List<EducationResponse>} directly.
 */
public class EducationDtos {

    private EducationDtos() {}

    public record EducationResponse(
            UUID id,
            DegreeLevel degreeLevel,
            String institution,
            String fieldOfStudy,
            LocalDate graduationDate,
            boolean isPrimary
    ) {}

    public record EducationListResponse(List<EducationResponse> items) {}

    /**
     * Add / edit payload. Every field is optional at the DTO layer — the
     * service coerces empty strings to null via {@link #trim} and allows a
     * partially-filled row so the user can save early and iterate.
     * {@code makePrimary} on create promotes the new row to primary (and
     * demotes whichever row currently holds it).
     */
    public static class EducationUpsertRequest {
        private DegreeLevel degreeLevel;
        @Size(max = 200) private String institution;
        @Size(max = 200) private String fieldOfStudy;
        private LocalDate graduationDate;
        private Boolean makePrimary;

        public DegreeLevel getDegreeLevel() { return degreeLevel; }
        public void setDegreeLevel(DegreeLevel degreeLevel) { this.degreeLevel = degreeLevel; }

        public String getInstitution() { return institution; }
        public void setInstitution(String institution) { this.institution = institution; }

        public String getFieldOfStudy() { return fieldOfStudy; }
        public void setFieldOfStudy(String fieldOfStudy) { this.fieldOfStudy = fieldOfStudy; }

        public LocalDate getGraduationDate() { return graduationDate; }
        public void setGraduationDate(LocalDate graduationDate) { this.graduationDate = graduationDate; }

        public Boolean getMakePrimary() { return makePrimary; }
        public void setMakePrimary(Boolean makePrimary) { this.makePrimary = makePrimary; }
    }
}
