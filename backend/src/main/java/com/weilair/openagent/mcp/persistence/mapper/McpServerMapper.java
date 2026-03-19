package com.weilair.openagent.mcp.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.weilair.openagent.mcp.model.McpServerDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface McpServerMapper {

    @Insert("""
            INSERT INTO mcp_server (
              owner_user_id,
              name,
              description,
              protocol_type,
              transport_type,
              endpoint,
              command_line,
              args_json,
              env_json,
              headers_json,
              auth_type,
              auth_config_json,
              enabled,
              health_status,
              risk_level,
              ext_json
            ) VALUES (
              #{ownerUserId},
              #{name},
              #{description},
              #{protocolType},
              #{transportType},
              #{endpoint},
              #{commandLine},
              #{argsJson},
              #{envJson},
              #{headersJson},
              #{authType},
              #{authConfigJson},
              #{enabled},
              #{healthStatus},
              #{riskLevel},
              #{extJson}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(McpServerDO server);

    @Select("""
            <script>
            SELECT
              ms.id,
              ms.owner_user_id,
              ms.name,
              ms.description,
              ms.protocol_type,
              ms.transport_type,
              ms.endpoint,
              ms.command_line,
              ms.args_json,
              ms.env_json,
              ms.headers_json,
              ms.auth_type,
              ms.auth_config_json,
              ms.enabled,
              ms.health_status,
              ms.risk_level,
              ms.ext_json,
              ms.last_connected_at,
              ms.last_sync_at,
              ms.created_at,
              ms.updated_at,
              (
                SELECT COUNT(1)
                FROM mcp_tool_snapshot mts
                WHERE mts.mcp_server_id = ms.id
              ) AS tool_count
            FROM mcp_server ms
            <where>
              <if test='enabled != null'>
                ms.enabled = #{enabled}
              </if>
              <if test='transportType != null and transportType != ""'>
                <if test='enabled != null'>AND</if>
                ms.transport_type = #{transportType}
              </if>
              <if test='keyword != null and keyword != ""'>
                <if test='enabled != null or (transportType != null and transportType != "")'>AND</if>
                (
                  ms.name LIKE CONCAT('%', #{keyword}, '%')
                  OR ms.description LIKE CONCAT('%', #{keyword}, '%')
                  OR ms.endpoint LIKE CONCAT('%', #{keyword}, '%')
                  OR ms.command_line LIKE CONCAT('%', #{keyword}, '%')
                )
              </if>
            </where>
            ORDER BY ms.updated_at DESC, ms.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<McpServerDO> selectList(
            @Param("keyword") String keyword,
            @Param("enabled") Integer enabled,
            @Param("transportType") String transportType,
            @Param("limit") int limit
    );

    @Select("""
            SELECT
              ms.id,
              ms.owner_user_id,
              ms.name,
              ms.description,
              ms.protocol_type,
              ms.transport_type,
              ms.endpoint,
              ms.command_line,
              ms.args_json,
              ms.env_json,
              ms.headers_json,
              ms.auth_type,
              ms.auth_config_json,
              ms.enabled,
              ms.health_status,
              ms.risk_level,
              ms.ext_json,
              ms.last_connected_at,
              ms.last_sync_at,
              ms.created_at,
              ms.updated_at,
              (
                SELECT COUNT(1)
                FROM mcp_tool_snapshot mts
                WHERE mts.mcp_server_id = ms.id
              ) AS tool_count
            FROM mcp_server ms
            WHERE ms.id = #{serverId}
            """)
    McpServerDO selectById(@Param("serverId") Long serverId);

    @Update("""
            UPDATE mcp_server
            SET name = #{name},
                description = #{description},
                protocol_type = #{protocolType},
                transport_type = #{transportType},
                endpoint = #{endpoint},
                command_line = #{commandLine},
                args_json = #{argsJson},
                env_json = #{envJson},
                headers_json = #{headersJson},
                auth_type = #{authType},
                auth_config_json = #{authConfigJson},
                enabled = #{enabled},
                health_status = #{healthStatus},
                risk_level = #{riskLevel},
                ext_json = #{extJson},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int update(McpServerDO server);

    @Update("""
            UPDATE mcp_server
            SET health_status = #{healthStatus},
                last_connected_at = #{lastConnectedAt},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{serverId}
            """)
    int updateConnectionState(
            @Param("serverId") Long serverId,
            @Param("healthStatus") String healthStatus,
            @Param("lastConnectedAt") LocalDateTime lastConnectedAt
    );

    @Update("""
            UPDATE mcp_server
            SET health_status = #{healthStatus},
                last_connected_at = #{lastConnectedAt},
                last_sync_at = #{lastSyncAt},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{serverId}
            """)
    int updateSyncState(
            @Param("serverId") Long serverId,
            @Param("healthStatus") String healthStatus,
            @Param("lastConnectedAt") LocalDateTime lastConnectedAt,
            @Param("lastSyncAt") LocalDateTime lastSyncAt
    );

    @Select("""
            SELECT COUNT(1)
            FROM mcp_server
            WHERE enabled = 1
              AND health_status = 'HEALTHY'
            """)
    int countHealthyServers();
}
