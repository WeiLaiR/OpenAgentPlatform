package com.weilair.openagent.conversation.persistence.mapper;

import java.util.List;

import com.weilair.openagent.conversation.model.ConversationMcpBindingDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationMcpBindingMapper {

    @Select("""
            SELECT mcp_server_id
            FROM conversation_mcp_binding
            WHERE conversation_id = #{conversationId}
              AND selected = 1
            ORDER BY id ASC
            """)
    List<Long> selectSelectedServerIds(@Param("conversationId") Long conversationId);

    @Delete("""
            DELETE FROM conversation_mcp_binding
            WHERE conversation_id = #{conversationId}
            """)
    int deleteByConversationId(@Param("conversationId") Long conversationId);

    @Insert({
            "<script>",
            "INSERT INTO conversation_mcp_binding (",
            "  conversation_id,",
            "  mcp_server_id,",
            "  selected",
            ") VALUES ",
            "<foreach collection='bindings' item='binding' separator=','>",
            "(",
            "  #{binding.conversationId},",
            "  #{binding.mcpServerId},",
            "  #{binding.selected}",
            ")",
            "</foreach>",
            "</script>"
    })
    int insertBatch(@Param("bindings") List<ConversationMcpBindingDO> bindings);
}
