package com.hyunchang.bioagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PubMed 구조화 초록의 한 섹션. PubMed XML의 &lt;AbstractText Label="..."&gt;에서 추출.
 * label이 없는(unstructured) 초록은 label=null로 표시한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbstractSection {
    private String label;
    private String text;
}
