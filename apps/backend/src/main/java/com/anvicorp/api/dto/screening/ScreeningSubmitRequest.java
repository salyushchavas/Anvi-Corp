package com.anvicorp.api.dto.screening;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
public class ScreeningSubmitRequest {

    @NotEmpty
    @Valid
    private List<AnswerInput> answers;

    @Data
    @NoArgsConstructor
    public static class AnswerInput {
        @NotNull
        private UUID questionId;

        /** Required for SINGLE_CHOICE; ignored otherwise. */
        private Integer choiceIndex;

        /** Required for FREE_TEXT; ignored otherwise. */
        private String freeText;
    }
}
