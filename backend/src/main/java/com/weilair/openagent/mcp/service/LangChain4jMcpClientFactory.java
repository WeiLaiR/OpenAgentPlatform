package com.weilair.openagent.mcp.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import com.weilair.openagent.mcp.config.OpenAgentMcpProperties;
import com.weilair.openagent.mcp.model.McpServerDO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LangChain4jMcpClientFactory implements McpClientFactory {
    /**
     * 这里直接使用 LangChain4j 官方 MCP client / transport，
     * 目的是把“平台里的 MCP 管理”严格建立在 LangChain4j 官方抽象之上，
     * 而不是自己另写一套连接协议。
     */

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };

    private final OpenAgentMcpProperties properties;
    private final ObjectMapper objectMapper;

    public LangChain4jMcpClientFactory(OpenAgentMcpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpClient createClient(McpServerDO server) {
        McpTransport transport = buildTransport(server);

        return DefaultMcpClient.builder()
                .key(server.getName())
                .clientName(properties.getClientName())
                .clientVersion(properties.getClientVersion())
                .protocolVersion(properties.getProtocolVersion())
                .initializationTimeout(properties.getInitializationTimeout())
                .toolExecutionTimeout(properties.getToolExecutionTimeout())
                .resourcesTimeout(properties.getResourcesTimeout())
                .promptsTimeout(properties.getPromptsTimeout())
                .pingTimeout(properties.getPingTimeout())
                .reconnectInterval(properties.getReconnectInterval())
                .transport(transport)
                .build();
    }

    private McpTransport buildTransport(McpServerDO server) {
        String transportType = normalizeTransportType(server.getTransportType());
        return switch (transportType) {
            case "STDIO" -> buildStdioTransport(server);
            case "STREAMABLE_HTTP" -> buildStreamableHttpTransport(server);
            default -> throw new IllegalArgumentException("暂不支持的 MCP transportType: " + server.getTransportType());
        };
    }

    private McpTransport buildStdioTransport(McpServerDO server) {
        List<String> command = new ArrayList<>();
        command.add(requireText(server.getCommandLine(), "STDIO 类型的 MCP Server 必须提供 command。"));
        command.addAll(readJson(server.getArgsJson(), STRING_LIST_TYPE, "args_json"));

        StdioMcpTransport.Builder builder = StdioMcpTransport.builder()
                .command(command)
                .logEvents(Boolean.TRUE.equals(properties.getLogEvents()));

        Map<String, String> environment = readJson(server.getEnvJson(), STRING_MAP_TYPE, "env_json");
        if (!environment.isEmpty()) {
            builder.environment(environment);
        }

        return builder.build();
    }

    private McpTransport buildStreamableHttpTransport(McpServerDO server) {
        Map<String, String> headers = new LinkedHashMap<>(readJson(server.getHeadersJson(), STRING_MAP_TYPE, "headers_json"));
        headers.putAll(resolveAuthHeaders(server));

        StreamableHttpMcpTransport.Builder builder = StreamableHttpMcpTransport.builder()
                .url(requireText(server.getEndpoint(), "STREAMABLE_HTTP 类型的 MCP Server 必须提供 endpoint。"))
                .timeout(properties.getHttpTimeout())
                .logRequests(Boolean.TRUE.equals(properties.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(properties.getLogResponses()))
                .subsidiaryChannel(Boolean.TRUE.equals(properties.getSubsidiaryChannel()));

        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }

        return builder.build();
    }

    private Map<String, String> resolveAuthHeaders(McpServerDO server) {
        String authType = StringUtils.hasText(server.getAuthType()) ? server.getAuthType().trim().toUpperCase() : "NONE";
        if ("NONE".equals(authType)) {
            return Map.of();
        }

        Map<String, Object> authConfig = readJson(server.getAuthConfigJson(), OBJECT_MAP_TYPE, "auth_config_json");
        Map<String, String> headers = new LinkedHashMap<>();

        if ("BEARER".equals(authType)) {
            String token = asText(authConfig.get("token"));
            if (!StringUtils.hasText(token)) {
                throw new IllegalArgumentException("BEARER 认证缺少 authConfig.token。");
            }
            headers.put("Authorization", "Bearer " + token);
            return headers;
        }

        if ("BASIC".equals(authType)) {
            String username = asText(authConfig.get("username"));
            String password = asText(authConfig.get("password"));
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                throw new IllegalArgumentException("BASIC 认证缺少 authConfig.username 或 authConfig.password。");
            }
            String credentials = Base64.getEncoder().encodeToString((username + ":" + password)
                    .getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + credentials);
            return headers;
        }

        if ("CUSTOM".equals(authType)) {
            String headerName = asText(authConfig.get("headerName"));
            String headerValue = asText(authConfig.get("headerValue"));
            if (StringUtils.hasText(headerName) && StringUtils.hasText(headerValue)) {
                headers.put(headerName, headerValue);
            }
        }

        return headers;
    }

    private String normalizeTransportType(String transportType) {
        return requireText(transportType, "transportType 不能为空。").trim().toUpperCase();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private <T> T readJson(String json, TypeReference<T> typeReference, String fieldName) {
        if (!StringUtils.hasText(json)) {
            return defaultValue(typeReference);
        }

        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP 配置字段 " + fieldName + " 不是合法 JSON: " + exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T defaultValue(TypeReference<T> typeReference) {
        if (typeReference == STRING_LIST_TYPE) {
            return (T) List.of();
        }
        return (T) Map.of();
    }
}
