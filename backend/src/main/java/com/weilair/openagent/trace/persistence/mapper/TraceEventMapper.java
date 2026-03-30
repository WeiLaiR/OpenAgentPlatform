package com.weilair.openagent.trace.persistence.mapper;

import java.util.List;

import com.weilair.openagent.trace.model.TraceEventDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TraceEventMapper {

    @Insert("""
            INSERT INTO trace_event (
              conversation_id,
              request_id,
              message_id,
              event_type,
              event_stage,
              event_source,
              event_payload_json,
              success_flag,
              cost_millis
            ) VALUES (
              #{conversationId},
              #{requestId},
              #{messageId},
              #{eventType},
              #{eventStage},
              #{eventSource},
              CAST(#{eventPayloadJson} AS JSON),
              #{successFlag},
              #{costMillis}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TraceEventDO traceEvent);

    @Select("""
            SELECT
              id,
              conversation_id,
              request_id,
              message_id,
              event_type,
              event_stage,
              event_source,
              event_payload_json,
              success_flag,
              cost_millis,
              created_at
            FROM trace_event
            WHERE request_id = #{requestId}
            ORDER BY created_at ASC, id ASC
            """)
    List<TraceEventDO> selectByRequestId(@Param("requestId") String requestId);

    @Delete("""
            DELETE FROM trace_event
            WHERE conversation_id = #{conversationId}
            """)
    int deleteByConversationId(@Param("conversationId") Long conversationId);
}
