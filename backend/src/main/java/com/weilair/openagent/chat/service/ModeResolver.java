package com.weilair.openagent.chat.service;

import java.util.List;
import java.util.Objects;

import com.weilair.openagent.chat.model.ChatMode;
import com.weilair.openagent.conversation.model.ConversationDO;
import com.weilair.openagent.web.dto.ChatSendRequest;
import org.springframework.stereotype.Component;

@Component
public class ModeResolver {

    /**
     * 统一把“请求覆盖项 + 会话持久配置”解析为一份可执行规格。
     * 当前阶段先覆盖：
     * 1. mode: `enableRag` / `enableAgent`
     * 2. memory: 会话持久配置
     * 3. knowledge bases: 请求级检索范围
     *
     * 后续会话级知识库绑定、MCP 绑定和 memory 参数补齐后，
     * 也应继续在这里做优先级收口，而不是让下游 service 自己再判一次。
     */
    public ChatExecutionSpec resolve(
            ConversationDO conversation,
            ChatSendRequest request,
            boolean streaming
    ) {
        ChatMode mode = ChatMode.fromFlags(
                request.enableRag() != null ? request.enableRag() : conversation.getEnableRag(),
                request.enableAgent() != null ? request.enableAgent() : conversation.getEnableAgent()
        );

        return new ChatExecutionSpec(
                mode,
                streaming,
                Boolean.TRUE.equals(conversation.getMemoryEnabled()),
                resolveKnowledgeBaseIds(mode, request)
        );
    }

    private List<Long> resolveKnowledgeBaseIds(ChatMode mode, ChatSendRequest request) {
        if (mode == null || !mode.ragEnabled() || request.knowledgeBaseIds() == null) {
            return List.of();
        }
        return request.knowledgeBaseIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
