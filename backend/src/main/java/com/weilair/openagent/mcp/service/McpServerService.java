package com.weilair.openagent.mcp.service;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import com.weilair.openagent.mcp.model.McpServerDO;
import com.weilair.openagent.mcp.model.McpToolSnapshotDO;
import com.weilair.openagent.mcp.persistence.mapper.McpServerMapper;
import com.weilair.openagent.mcp.persistence.mapper.McpToolSnapshotMapper;
import com.weilair.openagent.web.dto.McpServerUpsertRequest;
import com.weilair.openagent.web.vo.McpConnectionTestVO;
import com.weilair.openagent.web.vo.McpServerVO;
import com.weilair.openagent.web.vo.McpToolSnapshotVO;
import com.weilair.openagent.web.vo.McpToolSyncVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

@Service
public class McpServerService {
    /**
     * 这一层先落“数据库管理 + LangChain4j MCP client 联调”两件事：
     * 1. `mcp_server` / `mcp_tool_snapshot` 成为平台里的 MCP 真相来源
     * 2. 连接、健康检查、列工具全部通过 LangChain4j 官方 MCP client 完成
     *
     * 本阶段还不把 tool provider 正式接进聊天主链路，
     * 先把“可注册、可联通、可同步”这条管理闭环稳定下来。
     */

    private static final Long DEFAULT_USER_ID = 1L;
    private static final int DEFAULT_SERVER_LIMIT = 100;
    private static final int DEFAULT_TOOL_LIMIT = 300;
    private static final int DEFAULT_TOOL_DESCRIPTION_LIMIT = 1024;
    private static final String DEFAULT_PROTOCOL_TYPE = "ANTHROPIC_MCP";
    private static final String DEFAULT_AUTH_TYPE = "NONE";
    private static final String DEFAULT_RISK_LEVEL = "MEDIUM";
    private static final String HEALTH_UNKNOWN = "UNKNOWN";
    private static final String HEALTH_HEALTHY = "HEALTHY";
    private static final String HEALTH_UNHEALTHY = "UNHEALTHY";
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final McpServerMapper mcpServerMapper;
    private final McpToolSnapshotMapper mcpToolSnapshotMapper;
    private final McpClientFactory mcpClientFactory;
    private final ObjectMapper objectMapper;

    public McpServerService(
            McpServerMapper mcpServerMapper,
            McpToolSnapshotMapper mcpToolSnapshotMapper,
            McpClientFactory mcpClientFactory,
            ObjectMapper objectMapper
    ) {
        this.mcpServerMapper = mcpServerMapper;
        this.mcpToolSnapshotMapper = mcpToolSnapshotMapper;
        this.mcpClientFactory = mcpClientFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 管理页默认先看最近更新的 server，方便用户快速判断当前有哪些可用连接。
     */
    public List<McpServerVO> listServers(String keyword, Integer enabled, String transportType, Integer limit) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_SERVER_LIMIT : Math.min(limit, DEFAULT_SERVER_LIMIT);
        return mcpServerMapper.selectList(trimToNull(keyword), enabled, trimUpperToNull(transportType), effectiveLimit).stream()
                .map(this::toMcpServerVO)
                .toList();
    }

    /**
     * 新增 server 时先只固化注册信息，不在创建动作里隐式发起连接。
     * 这样录入和联调两个动作可以分开排查，用户也更容易定位问题。
     */
    @Transactional
    public McpServerVO createServer(McpServerUpsertRequest request) {
        McpServerDO server = new McpServerDO();
        applyUpsertRequest(server, request, false);

        try {
            mcpServerMapper.insert(server);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("MCP Server 名称已存在，请更换名称后重试。");
        }

        return toMcpServerVO(requireServer(server.getId()));
    }

    /**
     * 更新 server 时把健康状态重置为 UNKNOWN，提醒用户配置已经变更，需要重新连接或重新同步工具。
     */
    @Transactional
    public McpServerVO updateServer(Long serverId, McpServerUpsertRequest request) {
        McpServerDO server = requireServer(serverId);
        applyUpsertRequest(server, request, true);

        try {
            mcpServerMapper.update(server);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("MCP Server 名称已存在，请更换名称后重试。");
        }

        return toMcpServerVO(requireServer(serverId));
    }

    /**
     * 连接测试明确走 LangChain4j 的 MCP client health check + listTools，
     * 这样前端看到的不是“配置长得像对”，而是“LangChain4j 实际已经连得上”。
     */
    public McpConnectionTestVO testConnection(Long serverId) {
        McpServerDO server = requireServer(serverId);
        LocalDateTime checkedAt = LocalDateTime.now();

        try (McpClient client = mcpClientFactory.createClient(server)) {
            client.checkHealth();
            List<ToolSpecification> tools = client.listTools();
            mcpServerMapper.updateConnectionState(serverId, HEALTH_HEALTHY, checkedAt);

            return new McpConnectionTestVO(
                    server.getId(),
                    server.getName(),
                    HEALTH_HEALTHY,
                    tools.size(),
                    tools.stream().map(ToolSpecification::name).toList(),
                    toEpochMillis(checkedAt)
            );
        } catch (Exception exception) {
            mcpServerMapper.updateConnectionState(serverId, HEALTH_UNHEALTHY, null);
            throw new IllegalArgumentException("MCP 连接失败: " + safeMessage(exception));
        }
    }

    /**
     * 工具同步把远端工具快照重新覆盖到本地表里。
     * 当前不做历史版本追踪，先把“当前 server 暴露了哪些工具”这张快照表维护准确。
     */
    @Transactional
    public McpToolSyncVO syncTools(Long serverId) {
        McpServerDO server = requireServer(serverId);
        LocalDateTime syncedAt = LocalDateTime.now();

        try (McpClient client = mcpClientFactory.createClient(server)) {
            client.checkHealth();
            List<ToolSpecification> tools = client.listTools();
            List<McpToolSnapshotDO> snapshots = buildToolSnapshots(
                    server,
                    tools,
                    syncedAt,
                    resolveToolDescriptionLimit()
            );

            mcpToolSnapshotMapper.deleteByServerId(serverId);
            if (!snapshots.isEmpty()) {
                mcpToolSnapshotMapper.insertBatch(snapshots);
            }
            mcpServerMapper.updateSyncState(serverId, HEALTH_HEALTHY, syncedAt, syncedAt);

            return new McpToolSyncVO(
                    server.getId(),
                    server.getName(),
                    snapshots.size(),
                    snapshots.stream().map(McpToolSnapshotDO::getRuntimeToolName).toList(),
                    toEpochMillis(syncedAt)
            );
        } catch (Exception exception) {
            mcpServerMapper.updateConnectionState(serverId, HEALTH_UNHEALTHY, null);
            throw new IllegalArgumentException("MCP 工具同步失败: " + safeMessage(exception));
        }
    }

    /**
     * 工具列表默认展示最新同步的快照，便于前端管理页做启停和 schema 查看。
     */
    public List<McpToolSnapshotVO> listTools(Long serverId, String keyword, Integer enabled, Integer limit) {
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_TOOL_LIMIT : Math.min(limit, DEFAULT_TOOL_LIMIT);
        return mcpToolSnapshotMapper.selectList(serverId, trimToNull(keyword), enabled, effectiveLimit).stream()
                .map(this::toMcpToolSnapshotVO)
                .toList();
    }

    /**
     * 启停当前只控制“这个工具快照是否允许后续接入 runtime tool provider”。
     * 真正接进聊天主链路时，仍然会以这张快照表的 enabled 状态为准做过滤。
     */
    @Transactional
    public McpToolSnapshotVO updateToolEnabled(Long toolId, boolean enabled) {
        requireTool(toolId);
        mcpToolSnapshotMapper.updateEnabled(toolId, enabled);
        return toMcpToolSnapshotVO(requireTool(toolId));
    }

    public int countHealthyServers() {
        return mcpServerMapper.countHealthyServers();
    }

    public McpServerDO requireServer(Long serverId) {
        McpServerDO server = mcpServerMapper.selectById(serverId);
        if (server == null) {
            throw new IllegalArgumentException("MCP Server 不存在: " + serverId);
        }
        return server;
    }

    private McpToolSnapshotDO requireTool(Long toolId) {
        McpToolSnapshotDO tool = mcpToolSnapshotMapper.selectById(toolId);
        if (tool == null) {
            throw new IllegalArgumentException("MCP 工具不存在: " + toolId);
        }
        return tool;
    }

    private void applyUpsertRequest(McpServerDO server, McpServerUpsertRequest request, boolean updating) {
        String transportType = normalizeTransportType(request.transportType());
        validateUpsertRequest(request, transportType);

        server.setOwnerUserId(DEFAULT_USER_ID);
        server.setName(request.name().trim());
        server.setDescription(trimToNull(request.description()));
        server.setProtocolType(DEFAULT_PROTOCOL_TYPE);
        server.setTransportType(transportType);
        server.setEndpoint("STREAMABLE_HTTP".equals(transportType) ? trimToNull(request.endpoint()) : null);
        server.setCommandLine("STDIO".equals(transportType) ? trimToNull(request.command()) : null);
        server.setArgsJson(writeJson(normalizeStringList(request.args())));
        server.setEnvJson(writeJson(normalizeStringMap(request.env())));
        server.setHeadersJson(writeJson(normalizeStringMap(request.headers())));
        server.setAuthType(resolveAuthType(request.authType()));
        server.setAuthConfigJson(writeJson(normalizeObjectMap(request.authConfig())));
        server.setEnabled(resolveEnabled(request.enabled(), updating ? server.getEnabled() : null));
        server.setRiskLevel(resolveRiskLevel(request.riskLevel()));
        server.setHealthStatus(HEALTH_UNKNOWN);
    }

    private void validateUpsertRequest(McpServerUpsertRequest request, String transportType) {
        if ("STDIO".equals(transportType) && !StringUtils.hasText(request.command())) {
            throw new IllegalArgumentException("STDIO 类型的 MCP Server 必须提供 command。");
        }

        if ("STREAMABLE_HTTP".equals(transportType) && !StringUtils.hasText(request.endpoint())) {
            throw new IllegalArgumentException("STREAMABLE_HTTP 类型的 MCP Server 必须提供 endpoint。");
        }
    }

    private List<McpToolSnapshotDO> buildToolSnapshots(
            McpServerDO server,
            List<ToolSpecification> tools,
            LocalDateTime syncedAt,
            int descriptionLimit
    ) {
        return tools.stream()
                .map(tool -> toMcpToolSnapshot(server, tool, syncedAt, descriptionLimit))
                .toList();
    }

    private McpToolSnapshotDO toMcpToolSnapshot(
            McpServerDO server,
            ToolSpecification tool,
            LocalDateTime syncedAt,
            int descriptionLimit
    ) {
        McpToolSnapshotDO snapshot = new McpToolSnapshotDO();
        snapshot.setMcpServerId(server.getId());
        snapshot.setServerName(server.getName());
        snapshot.setRuntimeToolName(server.getName() + "__" + tool.name());
        snapshot.setOriginToolName(tool.name());
        snapshot.setToolTitle(resolveToolTitle(tool));
        snapshot.setDescription(trimToLength(tool.description(), descriptionLimit));
        snapshot.setInputSchemaJson(writeJson(normalizeJsonValue(tool.parameters())));
        snapshot.setOutputSchemaJson(null);
        snapshot.setEnabled(Boolean.TRUE);
        snapshot.setRiskLevel(server.getRiskLevel());
        snapshot.setVersionNo(null);
        snapshot.setSyncedAt(syncedAt);
        snapshot.setSyncHash(buildSyncHash(snapshot));
        return snapshot;
    }

    private String resolveToolTitle(ToolSpecification tool) {
        if (tool.metadata() == null || tool.metadata().isEmpty()) {
            return tool.name();
        }

        Object title = tool.metadata().get("title");
        return StringUtils.hasText(title == null ? null : String.valueOf(title))
                ? String.valueOf(title)
                : tool.name();
    }

    private String buildSyncHash(McpToolSnapshotDO snapshot) {
        String raw = snapshot.getRuntimeToolName()
                + "|" + nullSafe(snapshot.getDescription())
                + "|" + nullSafe(snapshot.getInputSchemaJson());
        return DigestUtils.md5DigestAsHex(raw.getBytes());
    }

    private int resolveToolDescriptionLimit() {
        Integer limit = mcpToolSnapshotMapper.selectDescriptionColumnLimit();
        if (limit == null || limit <= 0) {
            return DEFAULT_TOOL_DESCRIPTION_LIMIT;
        }
        return limit;
    }

    private McpServerVO toMcpServerVO(McpServerDO server) {
        return new McpServerVO(
                server.getId(),
                server.getName(),
                server.getDescription(),
                server.getProtocolType(),
                server.getTransportType(),
                server.getEndpoint(),
                server.getCommandLine(),
                parseJson(server.getArgsJson(), STRING_LIST_TYPE, List.of()),
                parseJson(server.getEnvJson(), STRING_MAP_TYPE, Map.of()),
                parseJson(server.getHeadersJson(), STRING_MAP_TYPE, Map.of()),
                server.getAuthType(),
                parseJson(server.getAuthConfigJson(), Object.class, null),
                server.getEnabled(),
                server.getHealthStatus(),
                server.getRiskLevel(),
                server.getToolCount() == null ? 0L : server.getToolCount(),
                toEpochMillis(server.getLastConnectedAt()),
                toEpochMillis(server.getLastSyncAt()),
                toEpochMillis(server.getCreatedAt()),
                toEpochMillis(server.getUpdatedAt())
        );
    }

    private McpToolSnapshotVO toMcpToolSnapshotVO(McpToolSnapshotDO tool) {
        return new McpToolSnapshotVO(
                tool.getId(),
                tool.getMcpServerId(),
                tool.getServerName(),
                tool.getRuntimeToolName(),
                tool.getOriginToolName(),
                tool.getToolTitle(),
                tool.getDescription(),
                parseJson(tool.getInputSchemaJson(), Object.class, null),
                parseJson(tool.getOutputSchemaJson(), Object.class, null),
                tool.getEnabled(),
                tool.getRiskLevel(),
                tool.getVersionNo(),
                tool.getSyncHash(),
                toEpochMillis(tool.getSyncedAt()),
                toEpochMillis(tool.getUpdatedAt())
        );
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    private Map<String, String> normalizeStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                normalized.put(key.trim(), value.trim());
            }
        });
        return normalized;
    }

    private Map<String, Object> normalizeObjectMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                normalized.put(key.trim(), value);
            }
        });
        return normalized;
    }

    private Boolean resolveEnabled(Boolean requestEnabled, Boolean currentValue) {
        if (requestEnabled != null) {
            return requestEnabled;
        }
        return currentValue != null ? currentValue : Boolean.TRUE;
    }

    private String resolveAuthType(String authType) {
        return StringUtils.hasText(authType) ? authType.trim().toUpperCase() : DEFAULT_AUTH_TYPE;
    }

    private String resolveRiskLevel(String riskLevel) {
        return StringUtils.hasText(riskLevel) ? riskLevel.trim().toUpperCase() : DEFAULT_RISK_LEVEL;
    }

    private String normalizeTransportType(String transportType) {
        String normalized = trimUpperToNull(transportType);
        if (!"STDIO".equals(normalized) && !"STREAMABLE_HTTP".equals(normalized)) {
            throw new IllegalArgumentException("transportType 仅支持 STDIO 或 STREAMABLE_HTTP。");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String trimToLength(String value, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (maxLength <= 0 || trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private String trimUpperToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("JSON 序列化失败: " + exception.getMessage());
        }
    }

    private <T> T parseJson(String rawJson, TypeReference<T> typeReference, T defaultValue) {
        if (!StringUtils.hasText(rawJson)) {
            return defaultValue;
        }

        try {
            return objectMapper.readValue(rawJson, typeReference);
        } catch (Exception exception) {
            return defaultValue;
        }
    }

    private <T> T parseJson(String rawJson, Class<T> targetType, T defaultValue) {
        if (!StringUtils.hasText(rawJson)) {
            return defaultValue;
        }

        try {
            return objectMapper.readValue(rawJson, targetType);
        } catch (Exception exception) {
            return defaultValue;
        }
    }

    private Long toEpochMillis(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String safeMessage(Exception exception) {
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return exception.getMessage();
        }
        return exception.getClass().getSimpleName();
    }

    /**
     * 当前版本的 LangChain4j schema 对象不是可直接交给 Jackson 的普通 Bean，
     * 所以这里做一层“仅用于持久化快照”的最小转换，把官方 schema 对象展开成普通 Map。
     * 这样数据库里仍然保存的是 LangChain4j tool schema 的结构化结果，而不是自定义 DSL。
     */
    private Object normalizeJsonValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof JsonSchemaElement schemaElement) {
            return normalizeJsonSchemaElement(schemaElement);
        }

        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            mapValue.forEach((key, nestedValue) -> normalized.put(String.valueOf(key), normalizeJsonValue(nestedValue)));
            return normalized;
        }

        if (value instanceof List<?> listValue) {
            return listValue.stream()
                    .map(this::normalizeJsonValue)
                    .toList();
        }

        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }

        return String.valueOf(value);
    }

    private Map<String, Object> normalizeJsonSchemaElement(JsonSchemaElement schemaElement) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", resolveJsonSchemaType(schemaElement));

        for (Method method : schemaElement.getClass().getMethods()) {
            if (shouldSkipSchemaMethod(method)) {
                continue;
            }

            try {
                Object methodValue = method.invoke(schemaElement);
                if (methodValue == null) {
                    continue;
                }
                if (methodValue instanceof List<?> listValue && listValue.isEmpty()) {
                    continue;
                }
                if (methodValue instanceof Map<?, ?> mapValue && mapValue.isEmpty()) {
                    continue;
                }

                schema.put(method.getName(), normalizeJsonValue(methodValue));
            } catch (Exception ignored) {
                // 这里只做 schema 快照持久化，个别无法安全反射的方法直接跳过，不影响主功能。
            }
        }

        return schema;
    }

    private boolean shouldSkipSchemaMethod(Method method) {
        if (method.getParameterCount() > 0 || Void.TYPE.equals(method.getReturnType())) {
            return true;
        }

        return switch (method.getName()) {
            case "getClass", "hashCode", "equals", "toString", "builder", "toBuilder", "wait", "notify", "notifyAll" -> true;
            default -> false;
        };
    }

    private String resolveJsonSchemaType(JsonSchemaElement schemaElement) {
        return switch (schemaElement.getClass().getSimpleName()) {
            case "JsonObjectSchema" -> "object";
            case "JsonArraySchema" -> "array";
            case "JsonStringSchema" -> "string";
            case "JsonIntegerSchema" -> "integer";
            case "JsonNumberSchema" -> "number";
            case "JsonBooleanSchema" -> "boolean";
            case "JsonEnumSchema" -> "enum";
            case "JsonAnyOfSchema" -> "anyOf";
            case "JsonReferenceSchema" -> "ref";
            case "JsonRawSchema" -> "raw";
            case "JsonNullSchema" -> "null";
            default -> schemaElement.getClass().getSimpleName();
        };
    }
}
