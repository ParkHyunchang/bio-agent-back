package com.hyunchang.bioagent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SearchResponse {
    private List<PaperSummary> papers;
    private int total;
    private int page;
    private int size;
    private boolean tooBroad;
    private String correctedQuery;  // null이면 교정 없음
}
