package com.hyunchang.bioagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ExamItemDto {
    private String itemName;
    private String value;
    private String unit;
    private String referenceRange;
    @JsonProperty("isAbnormal")
    private Boolean isAbnormal;
}
