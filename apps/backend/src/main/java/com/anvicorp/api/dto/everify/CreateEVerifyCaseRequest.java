package com.anvicorp.api.dto.everify;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateEVerifyCaseRequest {

    @NotNull
    private UUID i9FormId;
}
