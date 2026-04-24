package com.weilair.openagent.mcp.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import com.weilair.openagent.chat.service.ToolRiskLevel;
import com.weilair.openagent.chat.service.ToolRuntimeToolMetadata;
import com.weilair.openagent.mcp.model.McpServerDO;
import com.weilair.openagent.mcp.model.McpToolSnapshotDO;
import com.weilair.openagent.mcp.persistence.mapper.McpServerMapper;
import com.weilair.openagent.mcp.persistence.mapper.McpToolSnapshotMapper;
import org.springframework.stereotype.Service;

@Service
public class McpToolRuntimeService {
    /**
     * 这一层负责把“平台里登记过的 MCP 配置”装配成聊天运行期真正可调用的 Tool 集合。
     * 核心约束仍然保持和前面的管理层一致：
     * 1. server 侧配置来源于 `mcp_server`
     * 2. 可暴露给模型的 tool 以 `mcp_tool_snapshot.enabled` 为准
     * 3. tool 的执行器仍然完全使用 LangChain4j 官方 `McpToolProvider`
     *
     * 当前服务只负责把“上游已经筛选好的 MCP Server 集合”转换成运行时 Tool。
     * 会话级绑定与请求覆盖的优先级已在更上游的 `ChatExecutionSpec` 中收口，
     * 这里不再假定所有启用的 server 都默认可见。
     */
    private static final int DEFAULT_SERVER_LIMIT = 100;
    private static final int DEFAULT_TOOL_LIMIT = 300;

    private final McpServerMapper mcpServerMapper;
    private final McpToolSnapshotMapper mcpToolSnapshotMapper;
    private final McpClientFactory mcpClientFactory;

    public McpToolRuntimeService(
            McpServerMapper mcpServerMapper,
            McpToolSnapshotMapper mcpToolSnapshotMapper,
            McpClientFactory mcpClientFactory
    ) {
        this.mcpServerMapper = mcpServerMapper;
        this.mcpToolSnapshotMapper = mcpToolSnapshotMapper;
        this.mcpClientFactory = mcpClientFactory;
    }

    public McpToolRuntime openRuntime(Long conversationId, String userMessage, List<Long> enabledServerIds) {
        if (enabledServerIds == null || enabledServerIds.isEmpty()) {
            return McpToolRuntime.empty();
        }

        List<McpServerDO> candidateServers = mcpServerMapper.selectList(null, 1, null, DEFAULT_SERVER_LIMIT).stream()
                .filter(server -> enabledServerIds.contains(server.getId()))
                .filter(server -> !"UNHEALTHY".equalsIgnoreCase(server.getHealthStatus()))
                .toList();
        if (candidateServers.isEmpty()) {
            return McpToolRuntime.empty();
        }

        Map<Long, McpServerDO> serverById = new LinkedHashMap<>();
        candidateServers.forEach(server -> serverById.put(server.getId(), server));

        Map<String, McpToolSnapshotDO> snapshotByServerAndOrigin = new LinkedHashMap<>();
        Map<String, ToolRuntimeToolMetadata> metadataByRuntimeToolName = new LinkedHashMap<>();
        mcpToolSnapshotMapper.selectList(null, null, 1, DEFAULT_TOOL_LIMIT).stream()
                .filter(tool -> serverById.containsKey(tool.getMcpServerId()))
                .forEach(tool -> {
                    snapshotByServerAndOrigin.put(runtimeKey(tool.getServerName(), tool.getOriginToolName()), tool);
                    metadataByRuntimeToolName.put(tool.getRuntimeToolName(), toToolMetadata(tool, serverById.get(tool.getMcpServerId())));
                });

        if (snapshotByServerAndOrigin.isEmpty()) {
            return McpToolRuntime.empty();
        }

        List<McpClient> clients = new ArrayList<>();
        for (McpServerDO server : candidateServers) {
            boolean hasEnabledTools = snapshotByServerAndOrigin.keySet().stream()
                    .anyMatch(key -> key.startsWith(server.getName() + "::"));
            if (!hasEnabledTools) {
                continue;
            }

            try {
                clients.add(mcpClientFactory.createClient(server));
            } catch (Exception ignored) {
                // 当前策略是“单个 server 配置异常不拖垮整个 Agent 轮次”，因此这里直接跳过该 server。
            }
        }

        if (clients.isEmpty()) {
            return McpToolRuntime.empty();
        }

        try {
            McpToolProvider toolProvider = McpToolProvider.builder()
                    .mcpClients(clients)
                    .failIfOneServerFails(false)
                    .filter((client, tool) -> snapshotByServerAndOrigin.containsKey(runtimeKey(client.key(), tool.name())))
                    .toolNameMapper((client, tool) -> snapshotByServerAndOrigin
                            .get(runtimeKey(client.key(), tool.name()))
                            .getRuntimeToolName())
                    .build();

            ToolProviderResult toolProviderResult = toolProvider.provideTools(
                    new ToolProviderRequest(conversationId, UserMessage.from(userMessage))
            );
            if (toolProviderResult.tools().isEmpty()) {
                closeQuietly(clients);
                return McpToolRuntime.empty();
            }

            return new McpToolRuntime(clients, toolProviderResult, metadataByRuntimeToolName);
        } catch (Exception exception) {
            closeQuietly(clients);
            throw exception;
        }
    }

    private ToolRuntimeToolMetadata toToolMetadata(McpToolSnapshotDO tool, McpServerDO server) {
        return new ToolRuntimeToolMetadata(
                tool.getId(),
                server == null ? tool.getMcpServerId() : server.getId(),
                server == null ? tool.getServerName() : server.getName(),
                tool.getRuntimeToolName(),
                tool.getOriginToolName(),
                tool.getToolTitle(),
                ToolRiskLevel.fromValue(tool.getRiskLevel()),
                Boolean.TRUE.equals(tool.getEnabled()),
                server == null ? null : server.getHealthStatus()
        );
    }

    private String runtimeKey(String serverName, String originToolName) {
        return serverName + "::" + originToolName;
    }

    private void closeQuietly(List<McpClient> clients) {
        for (McpClient client : clients) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 运行期收尾不应覆盖主流程异常。
            }
        }
    }

    public static final class McpToolRuntime implements AutoCloseable {
        private static final McpToolRuntime EMPTY = new McpToolRuntime(List.of(), ToolProviderResult.builder().build(), Map.of());

        private final List<McpClient> clients;
        private final ToolProviderResult toolProviderResult;
        private final Map<String, ToolRuntimeToolMetadata> toolMetadataByName;

        private McpToolRuntime(
                List<McpClient> clients,
                ToolProviderResult toolProviderResult,
                Map<String, ToolRuntimeToolMetadata> toolMetadataByName
        ) {
            this.clients = clients;
            this.toolProviderResult = toolProviderResult;
            this.toolMetadataByName = toolMetadataByName == null ? Map.of() : Map.copyOf(toolMetadataByName);
        }

        public static McpToolRuntime empty() {
            return EMPTY;
        }

        public static McpToolRuntime of(
                List<McpClient> clients,
                ToolProviderResult toolProviderResult,
                Map<String, ToolRuntimeToolMetadata> toolMetadataByName
        ) {
            return new McpToolRuntime(clients, toolProviderResult, toolMetadataByName);
        }

        public boolean hasTools() {
            return !toolProviderResult.tools().isEmpty();
        }

        public int toolCount() {
            return toolProviderResult.tools().size();
        }

        public List<ToolSpecification> toolSpecifications() {
            return new ArrayList<>(toolProviderResult.tools().keySet());
        }

        public List<String> toolNames() {
            return toolSpecifications().stream()
                    .map(ToolSpecification::name)
                    .toList();
        }

        public ToolExecutor toolExecutor(String toolName) {
            return toolProviderResult.toolExecutorByName(toolName);
        }

        public ToolProviderResult toolProviderResult() {
            return toolProviderResult;
        }

        public Map<String, ToolRuntimeToolMetadata> toolMetadataByName() {
            return toolMetadataByName;
        }

        public ToolRuntimeToolMetadata toolMetadata(String toolName) {
            return toolMetadataByName.get(toolName);
        }

        /**
         * 这里把已经解析完成的 `ToolProviderResult` 再包一层 `ToolProvider` 暴露出去，
         * 目的是让上层可以直接接入 AI Services 官方 `toolProvider(...)` 主线。
         *
         * 当前实现仍然是“先按平台配置装配一次工具快照，再在本轮会话里复用这份结果”，
         * 还不是最终的“会话级 server 绑定 + 请求级动态筛选”形态；
         * 但它已经把工具调用重新收口回 LangChain4j 官方 `ToolProvider` 抽象。
         */
        public ToolProvider toolProvider() {
            return request -> toolProviderResult;
        }

        @Override
        public void close() {
            for (McpClient client : clients) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // Agent 回合结束时关闭 MCP 连接即可，个别 close 失败不影响主流程。
                }
            }
        }
    }
}
