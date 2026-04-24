package com.weilair.openagent.chat.persistence.mapper;

import java.util.List;

import com.weilair.openagent.chat.model.AgentToolConfirmationDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentToolConfirmationMapper {

    @Insert("""
            INSERT INTO agent_tool_confirmation (
              request_id,
              continuation_request_id,
              conversation_id,
              user_message_id,
              mode_code,
              memory_enabled,
              knowledge_base_ids_json,
              mcp_server_ids_json,
              user_message_text,
              tool_call_id,
              tool_name,
              tool_arguments_json,
              tool_title,
              server_name,
              risk_level,
              status_code,
              decision_reason,
              decision_at,
              executed_at,
              expires_at
            ) VALUES (
              #{requestId},
              #{continuationRequestId},
              #{conversationId},
              #{userMessageId},
              #{modeCode},
              #{memoryEnabled},
              #{knowledgeBaseIdsJson},
              #{mcpServerIdsJson},
              #{userMessageText},
              #{toolCallId},
              #{toolName},
              #{toolArgumentsJson},
              #{toolTitle},
              #{serverName},
              #{riskLevel},
              #{statusCode},
              #{decisionReason},
              #{decisionAt},
              #{executedAt},
              #{expiresAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentToolConfirmationDO confirmation);

    @Select("""
            SELECT
              id,
              request_id,
              continuation_request_id,
              conversation_id,
              user_message_id,
              mode_code,
              memory_enabled,
              knowledge_base_ids_json,
              mcp_server_ids_json,
              user_message_text,
              tool_call_id,
              tool_name,
              tool_arguments_json,
              tool_title,
              server_name,
              risk_level,
              status_code,
              decision_reason,
              decision_at,
              executed_at,
              expires_at,
              created_at,
              updated_at
            FROM agent_tool_confirmation
            WHERE id = #{confirmationId}
            """)
    AgentToolConfirmationDO selectById(@Param("confirmationId") Long confirmationId);

    @Select("""
            SELECT
              id,
              request_id,
              continuation_request_id,
              conversation_id,
              user_message_id,
              mode_code,
              memory_enabled,
              knowledge_base_ids_json,
              mcp_server_ids_json,
              user_message_text,
              tool_call_id,
              tool_name,
              tool_arguments_json,
              tool_title,
              server_name,
              risk_level,
              status_code,
              decision_reason,
              decision_at,
              executed_at,
              expires_at,
              created_at,
              updated_at
            FROM agent_tool_confirmation
            WHERE conversation_id = #{conversationId}
              AND status_code = 'PENDING'
            ORDER BY created_at DESC, id DESC
            """)
    List<AgentToolConfirmationDO> selectPendingByConversationId(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT
              id,
              request_id,
              continuation_request_id,
              conversation_id,
              user_message_id,
              mode_code,
              memory_enabled,
              knowledge_base_ids_json,
              mcp_server_ids_json,
              user_message_text,
              tool_call_id,
              tool_name,
              tool_arguments_json,
              tool_title,
              server_name,
              risk_level,
              status_code,
              decision_reason,
              decision_at,
              executed_at,
              expires_at,
              created_at,
              updated_at
            FROM agent_tool_confirmation
            WHERE request_id = #{requestId}
              AND tool_call_id = #{toolCallId}
              AND status_code = 'PENDING'
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentToolConfirmationDO selectPendingByRequestAndToolCall(
            @Param("requestId") String requestId,
            @Param("toolCallId") String toolCallId
    );

    @Update("""
            UPDATE agent_tool_confirmation
            SET continuation_request_id = #{continuationRequestId},
                status_code = #{targetStatus},
                decision_reason = #{decisionReason},
                decision_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{confirmationId}
              AND status_code = #{expectedStatus}
            """)
    int transitionStatus(
            @Param("confirmationId") Long confirmationId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("continuationRequestId") String continuationRequestId,
            @Param("decisionReason") String decisionReason
    );

    @Update("""
            UPDATE agent_tool_confirmation
            SET status_code = 'EXECUTED',
                executed_at = CURRENT_TIMESTAMP(3),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{confirmationId}
              AND status_code = 'APPROVED'
            """)
    int markExecuted(@Param("confirmationId") Long confirmationId);

    @Update("""
            UPDATE agent_tool_confirmation
            SET status_code = 'FAILED',
                decision_reason = #{decisionReason},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{confirmationId}
              AND status_code IN ('APPROVED', 'REJECTED')
            """)
    int markFailed(
            @Param("confirmationId") Long confirmationId,
            @Param("decisionReason") String decisionReason
    );

    @Update("""
            UPDATE agent_tool_confirmation
            SET status_code = 'EXPIRED',
                decision_reason = 'CONFIRMATION_EXPIRED',
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{confirmationId}
              AND status_code = 'PENDING'
            """)
    int markExpired(@Param("confirmationId") Long confirmationId);

    @Delete("""
            DELETE FROM agent_tool_confirmation
            WHERE conversation_id = #{conversationId}
            """)
    int deleteByConversationId(@Param("conversationId") Long conversationId);
}
