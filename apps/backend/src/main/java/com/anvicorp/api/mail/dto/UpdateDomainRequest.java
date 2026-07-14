package com.anvicorp.api.mail.dto;

/** Partial domain update. Null fields are left unchanged. */
public record UpdateDomainRequest(
        String displayName,
        Boolean active
) {
}
