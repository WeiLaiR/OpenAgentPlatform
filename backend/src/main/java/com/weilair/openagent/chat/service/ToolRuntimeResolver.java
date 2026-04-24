package com.weilair.openagent.chat.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import com.weilair.openagent.mcp.service.McpToolRuntimeService;
import com.weilair.openagent.mcp.service.McpToolRuntimeService.McpToolRuntime;
import org.springframework.stereotype.Component;

@Component
public class ToolRuntimeResolver {
    /**
     * 这一层先负责把“当前这轮聊天是否要挂 Tool runtime”从编排主类中剥离出去。
     * 目前底层仍然复用项目已有 `McpToolRuntimeService`，
     * 后续如果切到官方 `ToolProvider / McpToolProvider` 主线，也应优先在这里回切。
     */
    private final McpToolRuntimeService mcpToolRuntimeService;
    private final AgentToolRiskGuard agentToolRiskGuard;

    public ToolRuntimeResolver(
            McpToolRuntimeService mcpToolRuntimeService,
            AgentToolRiskGuard agentToolRiskGuard
    ) {
        this.mcpToolRuntimeService = mcpToolRuntimeService;
        this.agentToolRiskGuard = agentToolRiskGuard;
    }

    public ResolvedToolRuntime openRuntime(Long conversationId, ChatExecutionSpec executionSpec, String userMessage) {
        if (executionSpec == null || !executionSpec.agentEnabled()) {
            return ResolvedToolRuntime.disabled();
        }

        try {
            McpToolRuntime runtime = mcpToolRuntimeService.openRuntime(
                    conversationId,
                    userMessage,
                    executionSpec.mcpServerIds()
            );
            if (runtime.hasTools()) {
                return ResolvedToolRuntime.available(runtime, agentToolRiskGuard);
            }
            return ResolvedToolRuntime.unavailable(runtime, "NO_ENABLED_TOOLS", null);
        } catch (Exception exception) {
            return ResolvedToolRuntime.loadFailed(safeMessage(exception));
        }
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "未知错误";
        }
        return throwable.getMessage();
    }

    public enum ToolRuntimeStatus {
        DISABLED,
        AVAILABLE,
        UNAVAILABLE,
        LOAD_FAILED
    }

    public static final class ResolvedToolRuntime implements AutoCloseable {
        private final McpToolRuntime runtime;
        private final ToolRuntimeStatus status;
        private final String reason;
        private final String failureMessage;
        private final ToolProviderResult guardedToolProviderResult;
        private final Map<String, ToolRuntimeToolMetadata> toolMetadataByName;

        private ResolvedToolRuntime(
                McpToolRuntime runtime,
                ToolRuntimeStatus status,
                String reason,
                String failureMessage,
                ToolProviderResult guardedToolProviderResult,
                Map<String, ToolRuntimeToolMetadata> toolMetadataByName
        ) {
            this.runtime = runtime == null ? McpToolRuntime.empty() : runtime;
            this.status = status;
            this.reason = reason;
            this.failureMessage = failureMessage;
            this.guardedToolProviderResult = guardedToolProviderResult == null
                    ? ToolProviderResult.builder().build()
                    : guardedToolProviderResult;
            this.toolMetadataByName = toolMetadataByName == null ? Map.of() : Map.copyOf(toolMetadataByName);
        }

        public static ResolvedToolRuntime disabled() {
            return new ResolvedToolRuntime(
                    McpToolRuntime.empty(),
                    ToolRuntimeStatus.DISABLED,
                    "AGENT_DISABLED",
                    null,
                    ToolProviderResult.builder().build(),
                    Map.of()
            );
        }

        public static ResolvedToolRuntime available(McpToolRuntime runtime, AgentToolRiskGuard agentToolRiskGuard) {
            Map<String, ToolRuntimeToolMetadata> toolMetadataByName = runtime.toolMetadataByName();
            ToolProviderResult originalResult = runtime.toolProviderResult();
            ToolProviderResult.Builder builder = ToolProviderResult.builder();
            for (Map.Entry<ToolSpecification, ToolExecutor> entry : originalResult.tools().entrySet()) {
                ToolRuntimeToolMetadata metadata = toolMetadataByName.get(entry.getKey().name());
                builder.add(entry.getKey(), agentToolRiskGuard.wrap(entry.getValue(), metadata));
            }
            builder.immediateReturnToolNames(originalResult.immediateReturnToolNames());
            return new ResolvedToolRuntime(
                    runtime,
                    ToolRuntimeStatus.AVAILABLE,
                    null,
                    null,
                    builder.build(),
                    toolMetadataByName
            );
        }

        public static ResolvedToolRuntime unavailable(McpToolRuntime runtime, String reason, String failureMessage) {
            return new ResolvedToolRuntime(
                    runtime,
                    ToolRuntimeStatus.UNAVAILABLE,
                    reason,
                    failureMessage,
                    ToolProviderResult.builder().build(),
                    runtime == null ? Map.of() : runtime.toolMetadataByName()
            );
        }

        public static ResolvedToolRuntime loadFailed(String failureMessage) {
            return new ResolvedToolRuntime(
                    McpToolRuntime.empty(),
                    ToolRuntimeStatus.LOAD_FAILED,
                    "LOAD_FAILED",
                    failureMessage,
                    ToolProviderResult.builder().build(),
                    Map.of()
            );
        }

        public ToolRuntimeStatus status() {
            return status;
        }

        public String reason() {
            return reason;
        }

        public String failureMessage() {
            return failureMessage;
        }

        public boolean hasTools() {
            return !guardedToolProviderResult.tools().isEmpty();
        }

        public int toolCount() {
            return guardedToolProviderResult.tools().size();
        }

        public List<ToolSpecification> toolSpecifications() {
            return List.copyOf(guardedToolProviderResult.tools().keySet());
        }

        public List<String> toolNames() {
            return runtime.toolNames();
        }

        public ToolProvider toolProvider() {
            return request -> guardedToolProviderResult;
        }

        public ToolExecutor toolExecutor(String toolName) {
            return guardedToolProviderResult.toolExecutorByName(toolName);
        }

        public ToolRuntimeToolMetadata toolMetadata(String toolName) {
            return toolMetadataByName.get(toolName);
        }

        public Map<String, ToolRuntimeToolMetadata> toolMetadataByName() {
            return new LinkedHashMap<>(toolMetadataByName);
        }

        @Override
        public void close() {
            runtime.close();
        }
    }
}
