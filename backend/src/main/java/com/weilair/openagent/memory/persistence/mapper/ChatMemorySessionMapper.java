package com.weilair.openagent.memory.persistence.mapper;

import com.weilair.openagent.memory.model.ChatMemorySessionDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChatMemorySessionMapper {

    @Select("""
            SELECT
              id,
              conversation_id,
              memory_id,
              memory_type,
              max_messages,
              max_tokens,
              tokenizer_name,
              status,
              created_at,
              updated_at
            FROM chat_memory_session
            WHERE memory_id = #{memoryId}
            LIMIT 1
            """)
    ChatMemorySessionDO selectByMemoryId(@Param("memoryId") String memoryId);

    @Insert("""
            INSERT IGNORE INTO chat_memory_session (
              conversation_id,
              memory_id,
              memory_type,
              max_messages,
              max_tokens,
              tokenizer_name,
              status
            ) VALUES (
              #{conversationId},
              #{memoryId},
              #{memoryType},
              #{maxMessages},
              #{maxTokens},
              #{tokenizerName},
              #{status}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(ChatMemorySessionDO session);

    @Update("""
            UPDATE chat_memory_session
            SET
              memory_type = #{memoryType},
              max_messages = #{maxMessages},
              max_tokens = #{maxTokens},
              tokenizer_name = #{tokenizerName},
              status = #{status}
            WHERE id = #{id}
            """)
    int updateMetadata(ChatMemorySessionDO session);
}
