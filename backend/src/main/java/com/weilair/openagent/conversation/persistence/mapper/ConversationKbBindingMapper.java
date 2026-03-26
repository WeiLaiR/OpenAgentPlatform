package com.weilair.openagent.conversation.persistence.mapper;

import java.util.List;

import com.weilair.openagent.conversation.model.ConversationKbBindingDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationKbBindingMapper {

    @Select("""
            SELECT knowledge_base_id
            FROM conversation_kb_binding
            WHERE conversation_id = #{conversationId}
              AND selected = 1
            ORDER BY id ASC
            """)
    List<Long> selectSelectedKnowledgeBaseIds(@Param("conversationId") Long conversationId);

    @Delete("""
            DELETE FROM conversation_kb_binding
            WHERE conversation_id = #{conversationId}
            """)
    int deleteByConversationId(@Param("conversationId") Long conversationId);

    @Insert({
            "<script>",
            "INSERT INTO conversation_kb_binding (",
            "  conversation_id,",
            "  knowledge_base_id,",
            "  selected",
            ") VALUES ",
            "<foreach collection='bindings' item='binding' separator=','>",
            "(",
            "  #{binding.conversationId},",
            "  #{binding.knowledgeBaseId},",
            "  #{binding.selected}",
            ")",
            "</foreach>",
            "</script>"
    })
    int insertBatch(@Param("bindings") List<ConversationKbBindingDO> bindings);
}
