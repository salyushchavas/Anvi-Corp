package com.anvicorp.api.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supervisor → allocate a project to one of their interns. The intern is
 * resolved by candidate id; the service-level gate confirms the supervisor
 * owns that intern's engagement (or is SUPER_ADMIN).
 *
 * Optional task titles populate the initial checklist; the intern can
 * extend or override after starting.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateProjectRequest {

    @NotBlank
    private String title;

    @NotNull
    private UUID candidateId;

    private String description;
    private String deliverables;
    private List<String> resourceLinks;
    private LocalDate startDate;
    private LocalDate dueDate;

    /** Optional initial checklist — supervisor can seed; intern can edit. */
    private List<String> taskTitles;
}
