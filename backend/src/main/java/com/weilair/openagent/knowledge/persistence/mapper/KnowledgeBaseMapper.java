package com.weilair.openagent.knowledge.persistence.mapper;

import java.util.List;

import com.weilair.openagent.knowledge.model.KnowledgeBaseDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeBaseMapper {

    @Insert("""
            INSERT INTO knowledge_base (
              owner_user_id,
              name,
              description,
              status,
              embedding_model_name,
              embedding_dimension,
              milvus_database_name,
              milvus_collection_name,
              milvus_partition_name,
              parser_strategy,
              chunk_strategy,
              chunk_size,
              chunk_overlap
            ) VALUES (
              #{ownerUserId},
              #{name},
              #{description},
              #{status},
              #{embeddingModelName},
              #{embeddingDimension},
              #{milvusDatabaseName},
              #{milvusCollectionName},
              #{milvusPartitionName},
              #{parserStrategy},
              #{chunkStrategy},
              #{chunkSize},
              #{chunkOverlap}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeBaseDO knowledgeBase);

    @Select("""
            <script>
            SELECT
              kb.id,
              kb.owner_user_id,
              kb.name,
              kb.description,
              kb.status,
              kb.embedding_model_name,
              kb.embedding_dimension,
              kb.milvus_database_name,
              kb.milvus_collection_name,
              kb.milvus_partition_name,
              kb.parser_strategy,
              kb.chunk_strategy,
              kb.chunk_size,
              kb.chunk_overlap,
              (
                SELECT COUNT(1)
                FROM knowledge_file kf
                WHERE kf.knowledge_base_id = kb.id
              ) AS file_count,
              (
                SELECT COUNT(1)
                FROM knowledge_segment ks
                WHERE ks.knowledge_base_id = kb.id
              ) AS segment_count,
              kb.created_at,
              kb.updated_at
            FROM knowledge_base kb
            <where>
              <if test='status != null'>
                kb.status = #{status}
              </if>
              <if test='keyword != null and keyword != ""'>
                <if test='status != null'>AND</if>
                (
                  kb.name LIKE CONCAT('%', #{keyword}, '%')
                  OR kb.description LIKE CONCAT('%', #{keyword}, '%')
                )
              </if>
            </where>
            ORDER BY kb.created_at DESC, kb.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<KnowledgeBaseDO> selectList(
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("limit") int limit
    );

    @Select("""
            SELECT
              kb.id,
              kb.owner_user_id,
              kb.name,
              kb.description,
              kb.status,
              kb.embedding_model_name,
              kb.embedding_dimension,
              kb.milvus_database_name,
              kb.milvus_collection_name,
              kb.milvus_partition_name,
              kb.parser_strategy,
              kb.chunk_strategy,
              kb.chunk_size,
              kb.chunk_overlap,
              (
                SELECT COUNT(1)
                FROM knowledge_file kf
                WHERE kf.knowledge_base_id = kb.id
              ) AS file_count,
              (
                SELECT COUNT(1)
                FROM knowledge_segment ks
                WHERE ks.knowledge_base_id = kb.id
              ) AS segment_count,
              kb.created_at,
              kb.updated_at
            FROM knowledge_base kb
            WHERE kb.id = #{knowledgeBaseId}
            """)
    KnowledgeBaseDO selectById(@Param("knowledgeBaseId") Long knowledgeBaseId);

    @Update("""
            UPDATE knowledge_base
            SET milvus_partition_name = #{partitionName},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{knowledgeBaseId}
            """)
    int updatePartitionName(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("partitionName") String partitionName
    );
}
