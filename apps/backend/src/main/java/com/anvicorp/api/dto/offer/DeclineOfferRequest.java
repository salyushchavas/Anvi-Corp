package com.anvicorp.api.dto.offer;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeclineOfferRequest {
    private String reason;
}
