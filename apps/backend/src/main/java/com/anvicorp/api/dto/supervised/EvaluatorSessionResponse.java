package com.anvicorp.api.dto.supervised;

import com.anvicorp.api.enums.EvaluationSessionStatus;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluatorSessionResponse {
    private UUID sessionId;
    private UUID candidateId;
    private String internName;
    private LocalDateTime scheduledAt;
    private EvaluationSessionStatus status;
    private Integer overallRating;
    private Instant completedAt;
}
