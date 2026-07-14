package com.anvicorp.api.dto.i9;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReopenRequest {

    @NotBlank
    private String reason;
}
