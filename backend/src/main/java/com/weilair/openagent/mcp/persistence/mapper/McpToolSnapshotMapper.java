package com.weilair.openagent.mcp.persistence.mapper;

import java.util.List;

import com.weilair.openagent.mcp.model.McpToolSnapshotDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface McpToolSnapshotMapper {

    @Select("""
            SELECT CHARACTER_MAXIMUM_LENGTH
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'mcp_tool_snapshot'
              AND COLUMN_NAME = 'description'
            LIMIT 1
            """)
    Integer selectDescriptionColumnLimit();

    @Select("""
            <script>
            SELECT
              mts.id,
              mts.mcp_server_id,
              ms.name AS server_name,
              mts.runtime_tool_name,
              mts.origin_tool_name,
              mts.tool_title,
              mts.description,
              mts.input_schema_json,
              mts.output_schema_json,
              mts.enabled,
              mts.risk_level,
              mts.version_no,
              mts.sync_hash,
              mts.synced_at,
              mts.created_at,
              mts.updated_at
            FROM mcp_tool_snapshot mts
            INNER JOIN mcp_server ms ON ms.id = mts.mcp_server_id
            <where>
              <if test='serverId != null'>
                mts.mcp_server_id = #{serverId}
              </if>
              <if test='enabled != null'>
                <if test='serverId != null'>AND</if>
                mts.enabled = #{enabled}
              </if>
              <if test='keyword != null and keyword != ""'>
                <if test='serverId != null or enabled != null'>AND</if>
                (
                  mts.runtime_tool_name LIKE CONCAT('%', #{keyword}, '%')
                  OR mts.origin_tool_name LIKE CONCAT('%', #{keyword}, '%')
                  OR mts.tool_title LIKE CONCAT('%', #{keyword}, '%')
                  OR mts.description LIKE CONCAT('%', #{keyword}, '%')
                  OR ms.name LIKE CONCAT('%', #{keyword}, '%')
                )
              </if>
            </where>
            ORDER BY mts.synced_at DESC, mts.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<McpToolSnapshotDO> selectList(
            @Param("serverId") Long serverId,
            @Param("keyword") String keyword,
            @Param("enabled") Integer enabled,
            @Param("limit") int limit
    );

    @Select("""
            SELECT
              mts.id,
              mts.mcp_server_id,
              ms.name AS server_name,
              mts.runtime_tool_name,
              mts.origin_tool_name,
              mts.tool_title,
              mts.description,
              mts.input_schema_json,
              mts.output_schema_json,
              mts.enabled,
              mts.risk_level,
              mts.version_no,
              mts.sync_hash,
              mts.synced_at,
              mts.created_at,
              mts.updated_at
            FROM mcp_tool_snapshot mts
            INNER JOIN mcp_server ms ON ms.id = mts.mcp_server_id
            WHERE mts.id = #{toolId}
            """)
    McpToolSnapshotDO selectById(@Param("toolId") Long toolId);

    @Delete("""
            DELETE FROM mcp_tool_snapshot
            WHERE mcp_server_id = #{serverId}
            """)
    int deleteByServerId(@Param("serverId") Long serverId);

    @Insert("""
            <script>
            INSERT INTO mcp_tool_snapshot (
              mcp_server_id,
              runtime_tool_name,
              origin_tool_name,
              tool_title,
              description,
              input_schema_json,
              output_schema_json,
              enabled,
              risk_level,
              version_no,
              sync_hash,
              synced_at
            ) VALUES
            <foreach collection='tools' item='tool' separator=','>
              (
                #{tool.mcpServerId},
                #{tool.runtimeToolName},
                #{tool.originToolName},
                #{tool.toolTitle},
                #{tool.description},
                #{tool.inputSchemaJson},
                #{tool.outputSchemaJson},
                #{tool.enabled},
                #{tool.riskLevel},
                #{tool.versionNo},
                #{tool.syncHash},
                #{tool.syncedAt}
              )
            </foreach>
            </script>
            """)
    int insertBatch(@Param("tools") List<McpToolSnapshotDO> tools);

    @Update("""
            UPDATE mcp_tool_snapshot
            SET enabled = #{enabled},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{toolId}
            """)
    int updateEnabled(
            @Param("toolId") Long toolId,
            @Param("enabled") boolean enabled
    );
}
