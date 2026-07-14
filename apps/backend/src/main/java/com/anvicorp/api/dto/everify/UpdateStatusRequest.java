package com.anvicorp.api.dto.everify;

import com.anvicorp.api.enums.EVerifyStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateStatusRequest {

    @NotNull
    private EVerifyStatus status;

    /** Optional note; if provided, appended to existing notes with timestamp + actor prefix. */
    private String notes;
}
