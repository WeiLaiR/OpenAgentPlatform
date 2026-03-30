package com.weilair.openagent.chat.service;

import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import com.weilair.openagent.chat.prompt.PromptTemplateCatalog;
import com.weilair.openagent.chat.prompt.PromptTemplateKey;
import com.weilair.openagent.knowledge.service.KnowledgeEmbeddingService;
import com.weilair.openagent.knowledge.service.KnowledgeTextSegmentEmbeddingStoreFactory;
import com.weilair.openagent.web.vo.RagSnippetVO;
import org.springframework.stereotype.Component;

@Component
public class RagRuntimeResolver {
    /**
     * 这里继续把 RAG 底座往 LangChain4j 官方主线回切：
     * 1. `RetrievalAugmentor` 继续使用官方 `DefaultRetrievalAugmentor`
     * 2. `ContentRetriever` 继续使用官方 `EmbeddingStoreContentRetriever`
     * 3. Prompt 模板继续使用官方 `PromptTemplate`
     *
     * 当前仍保留一层项目自定义 `EmbeddingStore<TextSegment>` 适配，
     * 用来桥接已落地的 Milvus/MySQL 检索面。
     * Prompt 层则不再单独硬编码，统一改为从 `PromptTemplateCatalog` 取 `rag-context`。
     */
    private static final int DEFAULT_TOP_K = 4;
    private static final double DEFAULT_MIN_SCORE = 0.55d;
    private static final List<String> DEFAULT_METADATA_KEYS = List.of(
            "knowledgeBaseId",
            "fileId",
            "segmentNo",
            "score",
            "sourceTitle",
            "sourcePath"
    );

    private final KnowledgeTextSegmentEmbeddingStoreFactory knowledgeTextSegmentEmbeddingStoreFactory;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;
    private final PromptTemplate ragPromptTemplate;

    public RagRuntimeResolver(
            KnowledgeTextSegmentEmbeddingStoreFactory knowledgeTextSegmentEmbeddingStoreFactory,
            KnowledgeEmbeddingService knowledgeEmbeddingService,
            PromptTemplateCatalog promptTemplateCatalog
    ) {
        this.knowledgeTextSegmentEmbeddingStoreFactory = knowledgeTextSegmentEmbeddingStoreFactory;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.ragPromptTemplate = promptTemplateCatalog.template(PromptTemplateKey.RAG_CONTEXT);
    }

    public RagRuntime resolve(ChatExecutionSpec executionSpec) {
        if (executionSpec == null || !executionSpec.ragEnabled() || executionSpec.knowledgeBaseIds().isEmpty()) {
            return RagRuntime.disabled();
        }

        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(knowledgeTextSegmentEmbeddingStoreFactory.create(executionSpec.knowledgeBaseIds()))
                .embeddingModel(knowledgeEmbeddingService.currentEmbeddingModel())
                .maxResults(DEFAULT_TOP_K)
                .minScore(DEFAULT_MIN_SCORE)
                .build();
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .contentInjector(DefaultContentInjector.builder()
                        .promptTemplate(ragPromptTemplate)
                        .metadataKeysToInclude(DEFAULT_METADATA_KEYS)
                        .build())
                .build();

        return new RagRuntime(
                executionSpec.knowledgeBaseIds(),
                DEFAULT_TOP_K,
                DEFAULT_MIN_SCORE,
                retrievalAugmentor
        );
    }

    public RagAugmentationResult augment(
            RagRuntime ragRuntime,
            Long conversationId,
            String requestId,
            UserMessage userMessage,
            List<ChatMessage> chatMemoryMessages
    ) {
        if (ragRuntime == null || !ragRuntime.enabled()) {
            return RagAugmentationResult.disabled(userMessage);
        }

        AugmentationRequest augmentationRequest = new AugmentationRequest(
                userMessage,
                dev.langchain4j.rag.query.Metadata.builder()
                        .chatMessage(userMessage)
                        .chatMemory(chatMemoryMessages)
                        .invocationContext(InvocationContext.builder()
                                .interfaceName("ChatOrchestrator")
                                .methodName("augmentWithRag")
                                .chatMemoryId(conversationId)
                                .methodArguments(List.of(requestId))
                                .invocationParameters(InvocationParameters.from(Map.of(
                                        "conversationId", conversationId,
                                        "requestId", requestId,
                                        "knowledgeBaseIds", ragRuntime.knowledgeBaseIds()
                                )))
                                .timestampNow()
                                .build())
                        .build()
        );

        AugmentationResult augmentationResult = ragRuntime.retrievalAugmentor().augment(augmentationRequest);
        UserMessage augmentedUserMessage = augmentationResult.chatMessage() instanceof UserMessage augmented
                ? augmented
                : userMessage;
        List<RagSnippetVO> ragSnippets = toRagSnippets(augmentationResult.contents());

        return new RagAugmentationResult(augmentedUserMessage, ragSnippets);
    }

    /**
     * AI Services / TokenStream 路径拿到的是官方 `Content` 列表，
     * 这里统一回转成平台当前已经在用的 `RagSnippetVO`，
     * 避免 orchestrator 再复制一份 metadata 解析逻辑。
     */
    public List<RagSnippetVO> toRagSnippets(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }

        return contents.stream()
                .map(this::toRagSnippet)
                .filter(snippet -> snippet != null)
                .toList();
    }

    private RagSnippetVO toRagSnippet(Content content) {
        if (content == null || content.textSegment() == null) {
            return null;
        }

        dev.langchain4j.data.document.Metadata metadata = content.textSegment().metadata();
        Float score = resolveContentScore(content);
        return new RagSnippetVO(
                metadata.getLong("knowledgeBaseId"),
                metadata.getLong("fileId"),
                metadata.getInteger("segmentNo"),
                score,
                metadata.getString("textPreview"),
                metadata.getString("fullText"),
                metadata.getInteger("tokenCount"),
                metadata.getInteger("pageNo"),
                metadata.getString("sourceTitle"),
                metadata.getString("sourcePath"),
                metadata.getString("milvusPrimaryKey")
        );
    }

    private Float resolveContentScore(Content content) {
        if (content.metadata() == null) {
            return null;
        }

        Object score = content.metadata().get(ContentMetadata.SCORE);
        if (score instanceof Number number) {
            return number.floatValue();
        }
        return null;
    }

    public record RagRuntime(
            List<Long> knowledgeBaseIds,
            int topK,
            double minScore,
            RetrievalAugmentor retrievalAugmentor
    ) {
        public RagRuntime {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        }

        public static RagRuntime disabled() {
            return new RagRuntime(List.of(), DEFAULT_TOP_K, DEFAULT_MIN_SCORE, null);
        }

        public boolean enabled() {
            return !knowledgeBaseIds.isEmpty() && retrievalAugmentor != null;
        }
    }

    public record RagAugmentationResult(
            UserMessage requestUserMessage,
            List<RagSnippetVO> ragSnippets
    ) {
        public RagAugmentationResult {
            ragSnippets = ragSnippets == null ? List.of() : List.copyOf(ragSnippets);
        }

        public static RagAugmentationResult disabled(UserMessage userMessage) {
            return new RagAugmentationResult(userMessage, List.of());
        }
    }
}
