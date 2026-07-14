package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class CreateAssignmentRequest {

    @NotBlank(message = "title is required")
    private String title;

    private String description;

    @NotNull(message = "weekOf is required")
    private LocalDate weekOf;

    @NotNull(message = "dueDate is required")
    private LocalDate dueDate;
}
