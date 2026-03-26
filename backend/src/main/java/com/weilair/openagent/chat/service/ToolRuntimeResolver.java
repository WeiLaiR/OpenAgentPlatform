package com.weilair.openagent.chat.service;

import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
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

    public ToolRuntimeResolver(McpToolRuntimeService mcpToolRuntimeService) {
        this.mcpToolRuntimeService = mcpToolRuntimeService;
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
                return ResolvedToolRuntime.available(runtime);
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

        private ResolvedToolRuntime(
                McpToolRuntime runtime,
                ToolRuntimeStatus status,
                String reason,
                String failureMessage
        ) {
            this.runtime = runtime == null ? McpToolRuntime.empty() : runtime;
            this.status = status;
            this.reason = reason;
            this.failureMessage = failureMessage;
        }

        public static ResolvedToolRuntime disabled() {
            return new ResolvedToolRuntime(McpToolRuntime.empty(), ToolRuntimeStatus.DISABLED, "AGENT_DISABLED", null);
        }

        public static ResolvedToolRuntime available(McpToolRuntime runtime) {
            return new ResolvedToolRuntime(runtime, ToolRuntimeStatus.AVAILABLE, null, null);
        }

        public static ResolvedToolRuntime unavailable(McpToolRuntime runtime, String reason, String failureMessage) {
            return new ResolvedToolRuntime(runtime, ToolRuntimeStatus.UNAVAILABLE, reason, failureMessage);
        }

        public static ResolvedToolRuntime loadFailed(String failureMessage) {
            return new ResolvedToolRuntime(McpToolRuntime.empty(), ToolRuntimeStatus.LOAD_FAILED, "LOAD_FAILED", failureMessage);
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
            return runtime.hasTools();
        }

        public int toolCount() {
            return runtime.toolCount();
        }

        public List<ToolSpecification> toolSpecifications() {
            return runtime.toolSpecifications();
        }

        public List<String> toolNames() {
            return runtime.toolNames();
        }

        public ToolProvider toolProvider() {
            return runtime.toolProvider();
        }

        public ToolExecutor toolExecutor(String toolName) {
            return runtime.toolExecutor(toolName);
        }

        @Override
        public void close() {
            runtime.close();
        }
    }
}
