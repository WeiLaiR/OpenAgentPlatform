package com.weilair.openagent.knowledge.persistence.mapper;

import java.util.List;

import com.weilair.openagent.knowledge.model.KnowledgeSegmentDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeSegmentMapper {

    @Select("""
            SELECT milvus_primary_key
            FROM knowledge_segment
            WHERE file_id = #{fileId}
            ORDER BY segment_no ASC
            """)
    List<String> selectMilvusPrimaryKeysByFileId(@Param("fileId") Long fileId);

    @Select("""
            <script>
            SELECT
              id,
              knowledge_base_id,
              file_id,
              segment_no,
              text_preview,
              full_text,
              token_count,
              page_no,
              source_title,
              source_path,
              metadata_json,
              milvus_primary_key,
              created_at
            FROM knowledge_segment
            WHERE milvus_primary_key IN
            <foreach collection='milvusPrimaryKeys' item='milvusPrimaryKey' open='(' separator=',' close=')'>
              #{milvusPrimaryKey}
            </foreach>
            </script>
            """)
    List<KnowledgeSegmentDO> selectByMilvusPrimaryKeys(@Param("milvusPrimaryKeys") List<String> milvusPrimaryKeys);

    @Select("""
            <script>
            SELECT
              id,
              knowledge_base_id,
              file_id,
              segment_no,
              text_preview,
              full_text,
              token_count,
              page_no,
              source_title,
              source_path,
              metadata_json,
              milvus_primary_key,
              created_at
            FROM knowledge_segment
            WHERE file_id = #{fileId}
            <if test='keyword != null and keyword != ""'>
              AND (
                text_preview LIKE CONCAT('%', #{keyword}, '%')
                OR full_text LIKE CONCAT('%', #{keyword}, '%')
                OR source_title LIKE CONCAT('%', #{keyword}, '%')
              )
            </if>
            ORDER BY segment_no ASC, id ASC
            LIMIT #{limit}
            </script>
            """)
    List<KnowledgeSegmentDO> selectByFileId(
            @Param("fileId") Long fileId,
            @Param("keyword") String keyword,
            @Param("limit") int limit
    );

    @Select("""
            SELECT
              id,
              knowledge_base_id,
              file_id,
              segment_no,
              text_preview,
              full_text,
              token_count,
              page_no,
              source_title,
              source_path,
              metadata_json,
              milvus_primary_key,
              created_at
            FROM knowledge_segment
            WHERE id = #{segmentId}
            """)
    KnowledgeSegmentDO selectById(@Param("segmentId") Long segmentId);

    @Update("""
            UPDATE knowledge_segment
            SET text_preview = #{textPreview},
                full_text = #{fullText},
                token_count = #{tokenCount},
                metadata_json = #{metadataJson}
            WHERE id = #{id}
            """)
    int updateEditableFields(KnowledgeSegmentDO segment);

    @Delete("""
            DELETE FROM knowledge_segment
            WHERE file_id = #{fileId}
            """)
    int deleteByFileId(@Param("fileId") Long fileId);

    @Insert("""
            <script>
            INSERT INTO knowledge_segment (
              knowledge_base_id,
              file_id,
              segment_no,
              text_preview,
              full_text,
              token_count,
              page_no,
              source_title,
              source_path,
              metadata_json,
              milvus_primary_key
            ) VALUES
            <foreach collection='segments' item='segment' separator=','>
              (
                #{segment.knowledgeBaseId},
                #{segment.fileId},
                #{segment.segmentNo},
                #{segment.textPreview},
                #{segment.fullText},
                #{segment.tokenCount},
                #{segment.pageNo},
                #{segment.sourceTitle},
                #{segment.sourcePath},
                #{segment.metadataJson},
                #{segment.milvusPrimaryKey}
              )
            </foreach>
            </script>
            """)
    int batchInsert(@Param("segments") List<KnowledgeSegmentDO> segments);
}
