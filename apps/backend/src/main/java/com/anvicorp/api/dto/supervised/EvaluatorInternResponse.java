package com.anvicorp.api.dto.supervised;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluatorInternResponse {
    private UUID candidateId;
    private String name;
    /** Job title of the intern's HIRED application. */
    private String position;
    private String entityName;
}
