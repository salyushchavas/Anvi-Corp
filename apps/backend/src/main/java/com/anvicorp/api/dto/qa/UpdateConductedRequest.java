package com.anvicorp.api.dto.qa;

import jakarta.validation.constraints.Size;

public record UpdateConductedRequest(
        @Size(max = 10000) String questionsAsked,
        @Size(max = 10000) String internResponses
) {}
