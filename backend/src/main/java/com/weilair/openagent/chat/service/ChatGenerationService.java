package com.weilair.openagent.chat.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import com.weilair.openagent.chat.exception.ChatServiceUnavailableException;
import com.weilair.openagent.chat.model.ChatStreamSession;
import com.weilair.openagent.web.dto.ChatSendRequest;
import com.weilair.openagent.web.vo.ChatAnswerVO;
import com.weilair.openagent.web.vo.ChatRequestAcceptedVO;
import com.weilair.openagent.web.vo.ChatStreamCompletedVO;
import com.weilair.openagent.web.vo.ChatStreamStartedVO;
import com.weilair.openagent.web.vo.ChatStreamTokenVO;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ChatGenerationService {
    /**
     * 当前聊天主链路的最小实现：
     * 1. 同步问答直接调用 ChatModel
     * 2. 流式问答调用 StreamingChatModel
     * 3. 把 LangChain4j 的流式回调转换成前端可订阅的 SSE 事件
     *
     * 这里暂时还没有接 ChatMemory、RAG、Tools、MCP。
     * 也就是说，现在这层主要用于学习 LangChain4j 最基础的两种调用模式。
     *
     * 【当前实现与目标基线】
     * - 当前：Phase 1 最小可用版本，仅跑通单轮聊天与流式输出
     * - 后续：将升级为统一编排骨架，接入 ChatMemory、RAG Pipeline、MCP Tool Registry
     * - 参考：docs/模式编排与调用链设计.md 中的四种模式定义
     *
     * 【LangChain4j 核心概念在本类的体现】
     * - ChatModel：同步调用模型，直接返回完整答案
     * - StreamingChatModel：异步流式调用，通过回调逐步推送 token
     * - StreamingChatResponseHandler：框架提供的回调接口，用于接收增量响应
     */

    /** 同步聊天模型，用于 sendSync 方法 */
    private final ObjectProvider<ChatModel> chatModelProvider;

    /** 流式聊天模型，用于 submit 方法 */
    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;

    /** 流式会话存储，用于在 HTTP 返回后仍能通过 requestId 追踪生成状态 */
    private final ChatStreamSessionStore sessionStore;

    /**
     * 虚拟线程执行器，用于承载流式生成任务。
     *
     * 为什么用虚拟线程：
     * - 流式生成是 I/O 密集型任务，大部分时间在等待模型返回 token
     * - 虚拟线程可以高效处理大量并发流式请求，而不会阻塞平台线程
     * - Java 21 原生支持，与 Spring Boot 3.5.x 配合良好
     */
    private final ExecutorService executorService;

    /**
     * 构造函数注入所有依赖。
     *
     * 为什么用 ObjectProvider 而不是直接注入 Bean：
     * - LangChain4j 的 ChatModel / StreamingChatModel 是条件装配的
     * - 当用户未配置模型地址（OPENAGENT_CHAT_BASE_URL 等）时，应用仍需能启动
     * - ObjectProvider 允许 Bean 不存在，在调用时再判断并抛出明确错误
     * - 这样避免了一启动就失败，便于开发者先跑通其他模块
     *
     * @param chatModelProvider 同步模型的可选注入
     * @param streamingChatModelProvider 流式模型的可选注入
     * @param sessionStore 流式会话状态存储
     */
    public ChatGenerationService(
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectProvider<StreamingChatModel> streamingChatModelProvider,
            ChatStreamSessionStore sessionStore
    ) {
        this.chatModelProvider = chatModelProvider;
        this.streamingChatModelProvider = streamingChatModelProvider;
        this.sessionStore = sessionStore;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 同步聊天接口：用户发送消息，阻塞等待完整答案。
     *
     * 【LangChain4j 调用模式】
     * ChatModel.chat(String) 是最简单的同步调用方式：
     * - 输入：纯文本用户消息
     * - 输出：纯文本模型答案
     * - 特点：框架内部处理了消息封装，适合快速验证模型连通性
     *
     * 【当前实现边界】
     * - 未使用 ChatMemory：每次调用都是无状态的，不保留上下文
     * - 未使用 AI Services：直接调用底层 ChatModel，而非高级抽象
     * - 未做 Prompt 分层：直接透传用户原始输入
     *
     * 【后续演进方向】
     * - 接入 ChatMemoryProvider，支持多轮对话
     * - 通过 AI Services 封装，获得 @SystemMessage、@UserMessage 等能力
     * - 参考 docs/模式编排与调用链设计.md 中的"模式 A：纯 LLM"链路
     *
     * @param request 聊天请求，包含会话 ID 和用户消息
     * @return 完整答案 VO，包含文本内容、状态、时间戳等
     * @throws ChatServiceUnavailableException 当模型未配置时抛出
     */
    public ChatAnswerVO sendSync(ChatSendRequest request) {
        ChatModel chatModel = requireChatModel();
        // LangChain4j 的 ChatModel 在最简单用法下可以直接接收纯文本消息并返回纯文本答案。
        String answer = chatModel.chat(request.message());
        return new ChatAnswerVO(
                null,
                request.conversationId(),
                answer,
                "stop",
                false,
                false,
                0L
        );
    }

    /**
     * 流式聊天提交接口：创建生成任务，立即返回 requestId，前端再通过 SSE 订阅答案流。
     *
     * 【两步式流式调用设计】
     * 为什么不直接用 POST 返回流：
     * - 浏览器原生 EventSource 只支持 GET 请求
     * - 分离提交和订阅，让前端可以灵活选择订阅时机
     * - 便于实现重连、断点续传、多客户端订阅同一请求
     *
     * 【LangChain4j 流式调用流程】
     * 1. StreamingChatModel.chat(message, handler) 不阻塞，立即返回
     * 2. 框架通过 handler.onPartialResponse() 回调每个增量 token
     * 3. 生成完成后调用 handler.onCompleteResponse() 或 handler.onError()
     *
     * 【当前实现的事件流】
     * - message_start：流开始，前端可显示"正在输入"
     * - token：增量文本，前端逐字渲染
     * - message_end：流结束，包含完整答案和停止原因
     *
     * 【后续演进方向】
     * - 接入 RAG 后，补充 RAG_RETRIEVAL_STARTED/FINISHED 等事件
     * - 接入 Agent 后，补充 TOOL_CALL_REQUESTED/FINISHED 等事件
     * - 参考 docs/模式编排与调用链设计.md 中的 Trace 事件模型
     *
     * @param request 聊天请求
     * @return 请求已接受 VO，包含 requestId 供前端订阅
     * @throws ChatServiceUnavailableException 当流式模型未配置时抛出
     */
    public ChatRequestAcceptedVO submit(ChatSendRequest request) {
        StreamingChatModel streamingChatModel = requireStreamingChatModel();
        ChatStreamSession session = sessionStore.create(request.conversationId());

        // 先写入一个“message_start”事件，确保前端在真正收到 token 之前就能知道流已经建立。
        sessionStore.appendEvent(
                session,
                "message_start",
                new ChatStreamStartedVO(session.requestId(), session.conversationId())
        );

        // 流式生成是异步执行的：HTTP 请求先快速返回 requestId，前端再单独订阅 SSE。
        executorService.submit(() -> streamAnswer(streamingChatModel, session, request.message()));

        return new ChatRequestAcceptedVO(
                session.requestId(),
                request.conversationId(),
                session.status(),
                session.createdAt()
        );
    }

    /**
     * 容器销毁时优雅关闭虚拟线程执行器。
     *
     * 为什么要手动关闭：
     * - ExecutorService 不会自动关闭，可能导致 JVM 无法正常退出
     * - 虚拟线程虽然轻量，但仍在执行中的任务需要被正确终止
     * - shutdown() 会等待已提交任务完成，不再接受新任务
     */
    @PreDestroy
    void destroy() {
        executorService.shutdown();
    }

    /**
     * 流式生成的核心执行逻辑，在独立线程中运行。
     *
     * 【LangChain4j StreamingChatResponseHandler 详解】
     * 这是框架提供的核心回调接口，用于接收流式响应：
     *
     * - onPartialResponse(String token)
     *   每收到一个 token 就触发一次。token 通常是一个词或几个字符。
     *   我们在这里做两件事：累积到完整答案 + 推送到 SSE 事件流。
     *
     * - onCompleteResponse(ChatResponse response)
     *   生成完成时触发。ChatResponse 包含完整的 AiMessage 和元数据。
     *   LangChain4j 会尽量返回完整消息，但某些模型可能不提供，此时需回退到本地累积结果。
     *
     * - onError(Throwable error)
     *   发生错误时触发。我们将其转换为会话失败状态，并通知前端。
     *
     * 【当前实现与 AI Services 的对比】
     * - 当前：直接使用 StreamingChatModel + handler，是最底层的方式
     * - AI Services 方式：通过 @TokenStream 或 TokenStream.onPartialResponse() 获得类似能力
     * - 选择底层方式的原因：更直观地展示 LangChain4j 流式机制，便于学习
     *
     * 【后续演进方向】
     * - 接入 AI Services 后，可使用 beforeToolExecution / onToolExecuted 等更丰富的回调
     * - 接入 ChatModelListener 后，可记录完整的请求/响应元数据用于 Trace
     *
     * @param streamingChatModel 流式聊天模型
     * @param session 当前流式会话，用于状态追踪和事件发布
     * @param message 用户消息文本
     */
    private void streamAnswer(StreamingChatModel streamingChatModel, ChatStreamSession session, String message) {
        session.status("RUNNING");
        // StreamingChatModel 不直接返回完整字符串，而是通过 handler 持续回调 token / 完成 / 错误事件。
        streamingChatModel.chat(message, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                // 这里一边累计完整答案，一边把增量 token 推给 SSE。
                session.appendAnswer(partialResponse);
                sessionStore.appendEvent(
                        session,
                        "token",
                        new ChatStreamTokenVO(session.requestId(), partialResponse)
                );
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                AiMessage aiMessage = completeResponse.aiMessage();
                // 优先使用 LangChain4j 最终返回的完整 AiMessage；拿不到时再退回本地累积结果。
                String answer = aiMessage != null && aiMessage.text() != null ? aiMessage.text() : session.answer();
                sessionStore.appendEvent(
                        session,
                        "message_end",
                        new ChatStreamCompletedVO(session.requestId(), answer, "stop")
                );
                sessionStore.complete(session);
            }

            @Override
            public void onError(Throwable error) {
                sessionStore.fail(session, error.getMessage());
            }
        });
    }

    /**
     * 获取同步聊天模型，如果未配置则抛出明确异常。
     *
     * 【为什么要延迟检查而非启动时失败】
     * - 这是一个学习型项目，开发者可能只想先跑通其他模块
     * - 未配置模型时，健康检查、前端首页、MCP 管理等功能仍应可用
     * - 延迟检查让应用"能启动，但聊天时明确报错"，而非"一启动就挂"
     *
     * 【配置方式】
     * 需要设置以下环境变量或配置项：
     * - OPENAGENT_CHAT_BASE_URL：模型服务地址（OpenAI-compatible）
     * - OPENAGENT_CHAT_API_KEY：API 密钥
     * - OPENAGENT_CHAT_MODEL_NAME：模型名称
     *
     * 参考：docs/配置清单文档.md 中的模型配置章节
     *
     * @return 已配置的 ChatModel 实例
     * @throws ChatServiceUnavailableException 当模型未配置时抛出
     */
    private ChatModel requireChatModel() {
        // 用 ObjectProvider 是为了让 Bean 可选装配：未配置模型时应用仍能启动，但聊天接口会明确报错。
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new ChatServiceUnavailableException("ChatModel 未配置，请先设置 OPENAGENT_CHAT_BASE_URL、OPENAGENT_CHAT_API_KEY 和 OPENAGENT_CHAT_MODEL_NAME。");
        }
        return chatModel;
    }

    /**
     * 获取流式聊天模型，如果未配置则抛出明确异常。
     *
     * 【ChatModel 与 StreamingChatModel 的区别】
     * - ChatModel：同步阻塞，一次性返回完整答案，适合短回复或批处理场景
     * - StreamingChatModel：异步非阻塞，通过回调逐 token 返回，适合实时交互场景
     *
     * 【LangChain4j 的设计思路】
     * 框架将两者分离为不同接口，而非用同一个接口的不同方法：
     * - 强调两种调用模式的本质差异（阻塞 vs 回调）
     * - 允许实现类只支持其中一种（某些模型可能不支持流式）
     * - 方便在 AI Services 中按需注入不同实现
     *
     * 【本项目的选择】
     * - 同步接口（sendSync）使用 ChatModel，简单直接
     * - 流式接口（submit）使用 StreamingChatModel，配合 SSE
     * - 两者使用相同的配置源（OPENAGENT_CHAT_*），由框架自动创建两种实现
     *
     * @return 已配置的 StreamingChatModel 实例
     * @throws ChatServiceUnavailableException 当流式模型未配置时抛出
     */
    private StreamingChatModel requireStreamingChatModel() {
        StreamingChatModel streamingChatModel = streamingChatModelProvider.getIfAvailable();
        if (streamingChatModel == null) {
            throw new ChatServiceUnavailableException("StreamingChatModel 未配置，请先设置 OPENAGENT_CHAT_BASE_URL、OPENAGENT_CHAT_API_KEY 和 OPENAGENT_CHAT_MODEL_NAME。");
        }
        return streamingChatModel;
    }
}
