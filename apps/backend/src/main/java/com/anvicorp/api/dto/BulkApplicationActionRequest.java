package com.anvicorp.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class BulkApplicationActionRequest {

    public enum BulkAction {
        SHORTLIST,
        REJECT
    }

    @NotEmpty(message = "ids must not be empty")
    private List<UUID> ids;

    @NotNull(message = "action is required")
    private BulkAction action;
}
