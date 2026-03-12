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

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<StreamingChatModel> streamingChatModelProvider;
    private final ChatStreamSessionStore sessionStore;
    private final ExecutorService executorService;

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

    public ChatAnswerVO sendSync(ChatSendRequest request) {
        ChatModel chatModel = requireChatModel();
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

    public ChatRequestAcceptedVO submit(ChatSendRequest request) {
        StreamingChatModel streamingChatModel = requireStreamingChatModel();
        ChatStreamSession session = sessionStore.create(request.conversationId());

        sessionStore.appendEvent(
                session,
                "message_start",
                new ChatStreamStartedVO(session.requestId(), session.conversationId())
        );

        executorService.submit(() -> streamAnswer(streamingChatModel, session, request.message()));

        return new ChatRequestAcceptedVO(
                session.requestId(),
                request.conversationId(),
                session.status(),
                session.createdAt()
        );
    }

    @PreDestroy
    void destroy() {
        executorService.shutdown();
    }

    private void streamAnswer(StreamingChatModel streamingChatModel, ChatStreamSession session, String message) {
        session.status("RUNNING");
        streamingChatModel.chat(message, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
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

    private ChatModel requireChatModel() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new ChatServiceUnavailableException("ChatModel 未配置，请先设置 OPENAGENT_CHAT_BASE_URL、OPENAGENT_CHAT_API_KEY 和 OPENAGENT_CHAT_MODEL_NAME。");
        }
        return chatModel;
    }

    private StreamingChatModel requireStreamingChatModel() {
        StreamingChatModel streamingChatModel = streamingChatModelProvider.getIfAvailable();
        if (streamingChatModel == null) {
            throw new ChatServiceUnavailableException("StreamingChatModel 未配置，请先设置 OPENAGENT_CHAT_BASE_URL、OPENAGENT_CHAT_API_KEY 和 OPENAGENT_CHAT_MODEL_NAME。");
        }
        return streamingChatModel;
    }
}
