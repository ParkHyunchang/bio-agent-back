package com.hyunchang.bioagent.dto;

import lombok.Data;

import java.util.List;

@Data
public class ReviewRequest {
    private String pmid;
    private String pmcid;
    private String queryText;
    private String paperTitle;
    private String abstractText;
    private List<String> authors;
    private String journal;
    private String pubDate;
}
