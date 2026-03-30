package com.weilair.openagent.knowledge.persistence.mapper;

import java.util.List;

import com.weilair.openagent.knowledge.model.KnowledgeFileDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeFileMapper {

    @Insert("""
            INSERT INTO knowledge_file (
              knowledge_base_id,
              file_name,
              file_ext,
              file_size,
              storage_type,
              storage_uri,
              file_hash,
              parse_status,
              index_status,
              parser_name,
              parser_result_json,
              error_message,
              uploaded_by
            ) VALUES (
              #{knowledgeBaseId},
              #{fileName},
              #{fileExt},
              #{fileSize},
              #{storageType},
              #{storageUri},
              #{fileHash},
              #{parseStatus},
              #{indexStatus},
              #{parserName},
              #{parserResultJson},
              #{errorMessage},
              #{uploadedBy}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeFileDO knowledgeFile);

    @Select("""
            SELECT
              id,
              knowledge_base_id,
              file_name,
              file_ext,
              file_size,
              storage_type,
              storage_uri,
              file_hash,
              parse_status,
              index_status,
              parser_name,
              parser_result_json,
              error_message,
              uploaded_by,
              created_at,
              updated_at
            FROM knowledge_file
            WHERE id = #{fileId}
            """)
    KnowledgeFileDO selectById(@Param("fileId") Long fileId);

    @Select("""
            SELECT
              id,
              knowledge_base_id,
              file_name,
              file_ext,
              file_size,
              storage_type,
              storage_uri,
              file_hash,
              parse_status,
              index_status,
              parser_name,
              parser_result_json,
              error_message,
              uploaded_by,
              created_at,
              updated_at
            FROM knowledge_file
            WHERE knowledge_base_id = #{knowledgeBaseId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<KnowledgeFileDO> selectByKnowledgeBaseId(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE knowledge_file
            SET parse_status = #{parseStatus},
                index_status = #{indexStatus},
                parser_name = #{parserName},
                parser_result_json = #{parserResultJson},
                error_message = #{errorMessage},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{fileId}
            """)
    int updateProcessingState(
            @Param("fileId") Long fileId,
            @Param("parseStatus") String parseStatus,
            @Param("indexStatus") String indexStatus,
            @Param("parserName") String parserName,
            @Param("parserResultJson") String parserResultJson,
            @Param("errorMessage") String errorMessage
    );

    @Delete("""
            DELETE FROM knowledge_file
            WHERE id = #{fileId}
            """)
    int deleteById(@Param("fileId") Long fileId);
}
