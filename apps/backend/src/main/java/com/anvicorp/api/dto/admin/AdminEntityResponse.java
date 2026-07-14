package com.anvicorp.api.dto.admin;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminEntityResponse {
    private UUID id;
    private String name;
    private String address;
    private String country;
    private Boolean isActive;
    private Instant createdAt;
}
