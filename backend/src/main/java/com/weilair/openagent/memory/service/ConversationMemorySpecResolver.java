package com.weilair.openagent.memory.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import com.weilair.openagent.ai.config.OpenAgentChatProperties;
import com.weilair.openagent.ai.config.OpenAgentMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * LangChain4j 1.12.1 当前只直接提供 OpenAI 系列 TokenCountEstimator。
 * 因此这里按“官方 estimator 优先，失败时官方 MessageWindow 降级”的顺序解析 memory 策略，
 * 避免非 OpenAI 模型仅因 tokenizer 无法识别而在启动阶段失败。
 */
@Service
public class ConversationMemorySpecResolver {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemorySpecResolver.class);

    private final ConversationMemorySpec memorySpec;

    public ConversationMemorySpecResolver(
            OpenAgentChatProperties chatProperties,
            OpenAgentMemoryProperties memoryProperties
    ) {
        this.memorySpec = resolveMemorySpec(chatProperties, memoryProperties);
    }

    public ConversationMemorySpec getMemorySpec() {
        return memorySpec;
    }

    static ConversationMemorySpec resolveMemorySpec(
            OpenAgentChatProperties chatProperties,
            OpenAgentMemoryProperties memoryProperties
    ) {
        List<String> tokenizerCandidates = resolveTokenizerCandidates(chatProperties);
        RuntimeException lastFailure = null;

        for (String candidate : tokenizerCandidates) {
            try {
                TokenCountEstimator tokenCountEstimator = new OpenAiTokenCountEstimator(candidate);
                return new ConversationMemorySpec(
                        ConversationMemorySpec.TOKEN_WINDOW,
                        null,
                        memoryProperties.getMaxTokens(),
                        candidate,
                        memoryProperties.getAlwaysKeepSystemMessageFirst(),
                        tokenCountEstimator
                );
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }

        if (!tokenizerCandidates.isEmpty()) {
            log.warn(
                    "当前 chat 模型 [{}] 无法匹配 LangChain4j 官方 OpenAI TokenCountEstimator，" +
                            "ChatMemory 将降级为 MessageWindowChatMemory。可选地通过 openagent.ai.chat.tokenizer-model-name " +
                            "显式指定一个兼容的 OpenAI tokenizer 参考模型名。",
                    chatProperties.getModelName()
            );
            if (lastFailure != null && log.isDebugEnabled()) {
                log.debug("OpenAI TokenCountEstimator 解析失败详情", lastFailure);
            }
        } else {
            log.warn("未配置 chat.modelName，ChatMemory 将降级为 MessageWindowChatMemory。");
        }

        return new ConversationMemorySpec(
                ConversationMemorySpec.MESSAGE_WINDOW,
                memoryProperties.getMaxMessages(),
                null,
                null,
                memoryProperties.getAlwaysKeepSystemMessageFirst(),
                null
        );
    }

    static List<String> resolveTokenizerCandidates(OpenAgentChatProperties chatProperties) {
        Set<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, chatProperties.getTokenizerModelName());
        addCandidate(candidates, chatProperties.getModelName());
        return new ArrayList<>(candidates);
    }

    private static void addCandidate(Set<String> candidates, String value) {
        if (StringUtils.hasText(value)) {
            candidates.add(value.trim());
        }
    }
}
