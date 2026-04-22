package com.hyunchang.bioagent.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class PaperReviewRecordDto {
    private Long id;
    private String pmid;
    private String paperTitle;
    private String queryText;
    private String reviewText;
    private LocalDateTime createdAt;
}
