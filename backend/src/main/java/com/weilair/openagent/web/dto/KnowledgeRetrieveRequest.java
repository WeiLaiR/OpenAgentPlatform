package com.weilair.openagent.web.dto;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record KnowledgeRetrieveRequest(
        @NotBlank(message = "检索 query 不能为空")
        @Size(max = 2000, message = "检索 query 长度不能超过 2000 个字符")
        String query,
        @NotEmpty(message = "knowledgeBaseIds 不能为空")
        List<Long> knowledgeBaseIds,
        Integer topK,
        @DecimalMin(value = "0.0", message = "minScore 不能小于 0")
        @DecimalMax(value = "1.0", message = "minScore 不能大于 1")
        Double minScore
) {
}
