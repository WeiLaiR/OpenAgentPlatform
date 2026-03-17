package com.weilair.openagent.chat.service;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import com.weilair.openagent.conversation.model.ConversationDO;
import com.weilair.openagent.conversation.model.ConversationMessageDO;
import com.weilair.openagent.conversation.service.ConversationService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ChatContextAssembler {
    /**
     * 当前只做“最近 5 轮正式消息回灌”的初版能力：
     * 1. 从 MySQL 中读取最近 5 个已经完成的 user/assistant turn
     * 2. 手工组装成 LangChain4j 的 ChatMessage 列表
     * 3. 再把本轮用户问题追加到消息尾部，交给模型继续生成
     *
     * 这不是最终形态。后续项目会升级到 ChatMemory + ChatMemoryStore，
     * 由 conversationId 真正承接“模型上下文视图”的职责。
     * 当前保留独立装配层，就是为了后续可以只替换这一层而不改聊天主链路。
     */
    private static final int INITIAL_CONTEXT_TURN_LIMIT = 5;

    private final ConversationService conversationService;

    public ChatContextAssembler(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 当前 conversation_message 仍然是“完整 history 表”，不是 LangChain4j ChatMemory。
     * 因此这里显式做一次裁剪，只把最近若干轮正式消息映射成模型上下文。
     */
    public List<ChatMessage> assemble(ConversationDO conversation, String currentUserMessage) {
        List<ChatMessage> messages = new ArrayList<>();

        // 当前会话虽然默认开启 memoryEnabled，但这里先把开关位保留下来，
        // 后续如果前端或会话配置支持关闭上下文，可以直接复用这条分支。
        if (Boolean.TRUE.equals(conversation.getMemoryEnabled())) {
            List<ConversationMessageDO> historyMessages =
                    conversationService.listRecentContextMessages(conversation.getId(), INITIAL_CONTEXT_TURN_LIMIT);
            for (ConversationMessageDO historyMessage : historyMessages) {
                ChatMessage chatMessage = toChatMessage(historyMessage);
                if (chatMessage != null) {
                    messages.add(chatMessage);
                }
            }
        }

        // 当前 user message 不提前写回查询结果中，避免把“本轮尚未完成的消息”混进历史裁剪逻辑。
        messages.add(UserMessage.from(currentUserMessage));
        return messages;
    }

    private ChatMessage toChatMessage(ConversationMessageDO message) {
        if (!StringUtils.hasText(message.getContent())) {
            return null;
        }

        if ("USER".equals(message.getRoleCode())) {
            return UserMessage.from(message.getContent());
        }
        if ("ASSISTANT".equals(message.getRoleCode())) {
            return AiMessage.from(message.getContent());
        }
        return null;
    }
}
