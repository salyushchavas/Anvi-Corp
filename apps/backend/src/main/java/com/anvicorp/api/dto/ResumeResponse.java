package com.anvicorp.api.dto;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResumeResponse {
    private UUID id;
    private String fileName;
    private Long fileSize;
    private String contentType;
    private Boolean isDefault;
    private Instant createdAt;
}
