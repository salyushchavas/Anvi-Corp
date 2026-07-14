package com.anvicorp.api.dto.everify;

import com.anvicorp.api.enums.EVerifyClosureReason;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CloseCaseRequest {

    @NotNull
    private EVerifyClosureReason closureReason;

    private String notes;
}
