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
public class PaperDetail {
    private String pmid;
    private String pmcid;
    private String title;
    private List<String> authors;
    private String pubDate;
    private String journal;
    /** 평문 초록 (구조화된 경우 섹션을 합쳐 라벨 헤더와 함께 직렬화). */
    private String abstractText;
    /** 구조화된 초록(BACKGROUND/METHODS/RESULTS/CONCLUSIONS 등). 단일 섹션이면 null/빈 리스트. */
    private List<AbstractSection> abstractSections;
    private String fullText;
    /** PMC 본문이 컷오프되었는지 여부. true면 일부만 분석됨을 사용자에게 표시. */
    private boolean fullTextTruncated;
}
