package com.hyunchang.bioagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentResponse {
    private String sessionId;
    private String message;
}
