package com.weilair.openagent.conversation.persistence.mapper;

import java.util.List;

import com.weilair.openagent.conversation.model.ConversationMessageDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationMessageMapper {

    @Insert("""
            INSERT INTO conversation_message (
              conversation_id,
              user_id,
              role_code,
              message_type,
              content,
              request_id,
              parent_message_id,
              model_name,
              finish_reason,
              error_message
            ) VALUES (
              #{conversationId},
              #{userId},
              #{roleCode},
              #{messageType},
              #{content},
              #{requestId},
              #{parentMessageId},
              #{modelName},
              #{finishReason},
              #{errorMessage}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConversationMessageDO message);

    @Select("""
            SELECT
              id,
              conversation_id,
              user_id,
              role_code,
              message_type,
              content,
              request_id,
              parent_message_id,
              model_name,
              finish_reason,
              error_message,
              created_at
            FROM conversation_message
            WHERE conversation_id = #{conversationId}
            ORDER BY created_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<ConversationMessageDO> selectByConversationId(
            @Param("conversationId") Long conversationId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT
              cm.id,
              cm.conversation_id,
              cm.user_id,
              cm.role_code,
              cm.message_type,
              cm.content,
              cm.request_id,
              cm.parent_message_id,
              cm.model_name,
              cm.finish_reason,
              cm.error_message,
              cm.created_at
            FROM conversation_message cm
            INNER JOIN (
              SELECT
                CASE
                  WHEN role_code = 'USER' THEN CAST(id AS CHAR)
                  ELSE CAST(parent_message_id AS CHAR)
                END AS turn_key
              FROM conversation_message
              WHERE conversation_id = #{conversationId}
                AND role_code IN ('USER', 'ASSISTANT')
                AND message_type = 'TEXT'
              GROUP BY CASE
                WHEN role_code = 'USER' THEN CAST(id AS CHAR)
                ELSE CAST(parent_message_id AS CHAR)
              END
              HAVING SUM(CASE WHEN role_code = 'USER' THEN 1 ELSE 0 END) > 0
                 AND SUM(CASE WHEN role_code = 'ASSISTANT' AND COALESCE(finish_reason, '') <> 'error' THEN 1 ELSE 0 END) > 0
              ORDER BY MAX(created_at) DESC, MAX(id) DESC
              LIMIT #{turnLimit}
            ) recent_turns
              ON CASE
                WHEN cm.role_code = 'USER' THEN CAST(cm.id AS CHAR)
                ELSE CAST(cm.parent_message_id AS CHAR)
              END = recent_turns.turn_key
            WHERE cm.conversation_id = #{conversationId}
              AND cm.role_code IN ('USER', 'ASSISTANT')
              AND cm.message_type = 'TEXT'
              AND (cm.role_code <> 'ASSISTANT' OR COALESCE(cm.finish_reason, '') <> 'error')
            ORDER BY cm.created_at ASC, cm.id ASC
            """)
    List<ConversationMessageDO> selectRecentContextMessages(
            @Param("conversationId") Long conversationId,
            @Param("turnLimit") int turnLimit
    );

    @Delete("""
            DELETE FROM conversation_message
            WHERE conversation_id = #{conversationId}
            """)
    int deleteByConversationId(@Param("conversationId") Long conversationId);
}
