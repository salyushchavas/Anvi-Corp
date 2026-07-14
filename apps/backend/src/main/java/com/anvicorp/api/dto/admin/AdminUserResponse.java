package com.anvicorp.api.dto.admin;

import com.anvicorp.api.enums.UserRole;
import lombok.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminUserResponse {
    private UUID id;
    private String name;
    private String email;
    /** Multi-role accounts are rare today, but the entity allows it; expose the full set. */
    private Set<UserRole> roles;
    private Boolean active;
    private Instant createdAt;
    /**
     * Anvi Corp applicant / intern id (e.g. {@code SKZ-A-000123}). Only populated
     * once registration has minted one — null on freshly-created STAFF users
     * who never went through the applicant funnel.
     */
    private String applicantId;
}
