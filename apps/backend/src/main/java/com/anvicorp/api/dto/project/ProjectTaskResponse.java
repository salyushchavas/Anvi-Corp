package com.anvicorp.api.dto.project;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectTaskResponse {
    private UUID id;
    private String title;
    private Boolean done;
    private Integer sortOrder;
}
