package com.weilair.openagent.conversation.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.weilair.openagent.conversation.model.ConversationDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationMapper {

    @Insert("""
            INSERT INTO conversation (
              user_id,
              title,
              mode_code,
              enable_rag,
              enable_agent,
              memory_enabled,
              status,
              last_message_at
            ) VALUES (
              #{userId},
              #{title},
              #{modeCode},
              #{enableRag},
              #{enableAgent},
              #{memoryEnabled},
              #{status},
              #{lastMessageAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConversationDO conversation);

    @Select("""
            SELECT
              id,
              user_id,
              title,
              mode_code,
              enable_rag,
              enable_agent,
              memory_enabled,
              status,
              last_message_at,
              created_at,
              updated_at
            FROM conversation
            WHERE id = #{conversationId}
              AND status = 1
            """)
    ConversationDO selectActiveById(@Param("conversationId") Long conversationId);

    @Select("""
            SELECT
              id,
              user_id,
              title,
              mode_code,
              enable_rag,
              enable_agent,
              memory_enabled,
              status,
              last_message_at,
              created_at,
              updated_at
            FROM conversation
            WHERE status = 1
            ORDER BY COALESCE(last_message_at, created_at) DESC, id DESC
            LIMIT #{limit}
            """)
    List<ConversationDO> selectRecent(@Param("limit") int limit);

    @Update("""
            UPDATE conversation
            SET last_message_at = #{lastMessageAt},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{conversationId}
            """)
    int updateLastMessageAt(
            @Param("conversationId") Long conversationId,
            @Param("lastMessageAt") LocalDateTime lastMessageAt
    );

    @Update("""
            UPDATE conversation
            SET enable_rag = #{enableRag},
                enable_agent = #{enableAgent},
                mode_code = #{modeCode},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{conversationId}
              AND status = 1
            """)
    int updateSettings(
            @Param("conversationId") Long conversationId,
            @Param("enableRag") Boolean enableRag,
            @Param("enableAgent") Boolean enableAgent,
            @Param("modeCode") String modeCode
    );
}
