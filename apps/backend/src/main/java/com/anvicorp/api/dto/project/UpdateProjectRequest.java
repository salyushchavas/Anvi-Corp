package com.anvicorp.api.dto.project;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Supervisor → edit an existing project. Null fields are left untouched.
 * Locked when the project is in COMPLETED — service returns 409.
 *
 * {@code taskTitles}, when non-null, replaces the project's task list with
 * the provided titles (existing tasks deleted). When null the list is
 * untouched.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateProjectRequest {
    private String title;
    private String description;
    private String deliverables;
    private List<String> resourceLinks;
    private LocalDate startDate;
    private LocalDate dueDate;
    private List<String> taskTitles;
}
