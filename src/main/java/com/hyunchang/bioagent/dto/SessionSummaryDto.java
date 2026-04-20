package com.hyunchang.bioagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class SessionSummaryDto {
    private String sessionId;
    private String preview;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
