package com.weilair.openagent.memory.persistence.mapper;

import java.util.List;

import com.weilair.openagent.memory.model.ChatMemoryMessageDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatMemoryMessageMapper {

    @Select("""
            SELECT
              id,
              memory_session_id,
              message_order,
              role_code,
              message_json,
              token_count,
              created_at
            FROM chat_memory_message
            WHERE memory_session_id = #{memorySessionId}
            ORDER BY message_order ASC, id ASC
            """)
    List<ChatMemoryMessageDO> selectByMemorySessionId(@Param("memorySessionId") Long memorySessionId);

    @Delete("""
            DELETE FROM chat_memory_message
            WHERE memory_session_id = #{memorySessionId}
            """)
    int deleteByMemorySessionId(@Param("memorySessionId") Long memorySessionId);

    @Insert({
            "<script>",
            "INSERT INTO chat_memory_message (",
            "  memory_session_id,",
            "  message_order,",
            "  role_code,",
            "  message_json,",
            "  token_count",
            ") VALUES ",
            "<foreach collection='messages' item='message' separator=','>",
            "(",
            "  #{message.memorySessionId},",
            "  #{message.messageOrder},",
            "  #{message.roleCode},",
            "  #{message.messageJson},",
            "  #{message.tokenCount}",
            ")",
            "</foreach>",
            "</script>"
    })
    int insertBatch(@Param("messages") List<ChatMemoryMessageDO> messages);
}
