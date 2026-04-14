package com.hyunchang.bioagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperSummary {
    private String pmid;
    private String title;
    private List<String> authors;
    private String pubDate;
    private String journal;
}
