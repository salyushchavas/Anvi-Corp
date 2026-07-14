package com.anvicorp.api.dto.supervised;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluatorOption {
    private UUID id;
    private String name;
}
