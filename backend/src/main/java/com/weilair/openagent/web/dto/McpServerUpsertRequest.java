package com.weilair.openagent.web.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record McpServerUpsertRequest(
        @NotBlank(message = "MCP Server 名称不能为空")
        @Size(max = 128, message = "MCP Server 名称长度不能超过 128 个字符")
        String name,
        @Size(max = 512, message = "描述长度不能超过 512 个字符")
        String description,
        @NotBlank(message = "transportType 不能为空")
        @Size(max = 32, message = "transportType 长度不能超过 32 个字符")
        String transportType,
        @Size(max = 512, message = "endpoint 长度不能超过 512 个字符")
        String endpoint,
        @Size(max = 512, message = "command 长度不能超过 512 个字符")
        String command,
        List<String> args,
        Map<String, String> env,
        Map<String, String> headers,
        @Size(max = 32, message = "authType 长度不能超过 32 个字符")
        String authType,
        Map<String, Object> authConfig,
        Boolean enabled,
        @Size(max = 32, message = "riskLevel 长度不能超过 32 个字符")
        String riskLevel
) {
}
