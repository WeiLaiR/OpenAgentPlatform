package com.weilair.openagent.knowledge.service;

import java.util.List;

import com.weilair.openagent.ai.config.OpenAgentAiConfig;
import com.weilair.openagent.ai.config.OpenAgentEmbeddingProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEmbeddingService {
    /**
     * 这一层把 LangChain4j 的 EmbeddingModel 包成知识库索引可直接调用的服务：
     * - 索引链路不再感知具体模型供应商
     * - 后续检索链路也能复用同一套 embedding 入口
     */

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final OpenAgentEmbeddingProperties embeddingProperties;

    public KnowledgeEmbeddingService(
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            OpenAgentEmbeddingProperties embeddingProperties
    ) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.embeddingProperties = embeddingProperties;
    }

    public List<float[]> embedAll(List<String> texts) {
        EmbeddingModel embeddingModel = requireEmbeddingModel();
        List<TextSegment> segments = texts.stream()
                .map(TextSegment::from)
                .toList();

        Response<List<Embedding>> response;
        try {
            response = embeddingModel.embedAll(segments);
        } catch (RuntimeException exception) {
            throw wrapEmbeddingException(exception);
        }
        List<Embedding> embeddings = response.content();
        if (embeddings == null || embeddings.size() != texts.size()) {
            throw new IllegalStateException("Embedding 结果数量异常，无法继续写入向量库。");
        }

        return embeddings.stream()
                .map(Embedding::vector)
                .toList();
    }

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel 未配置，请先设置 openagent.ai.embedding.base-url / model-name。");
        }
        return embeddingModel;
    }

    private RuntimeException wrapEmbeddingException(RuntimeException exception) {
        String message = safeMessage(exception);
        if (message.contains("404") && message.toUpperCase().contains("NOT FOUND")) {
            String suggestedBaseUrl = OpenAgentAiConfig.normalizeOpenAiCompatibleBaseUrl(embeddingProperties.getBaseUrl());
            return new IllegalStateException(
                    "Embedding 服务调用返回 404，请检查 openagent.ai.embedding.base-url 是否指向 OpenAI 兼容前缀。"
                            + " 当前建议值: " + suggestedBaseUrl
                            + "，服务应能响应 POST " + suggestedBaseUrl + "/embeddings。"
                            + " 原始错误: " + message,
                    exception
            );
        }
        return new IllegalStateException("Embedding 服务调用失败: " + message, exception);
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "未知错误";
        }
        return throwable.getMessage();
    }
}
