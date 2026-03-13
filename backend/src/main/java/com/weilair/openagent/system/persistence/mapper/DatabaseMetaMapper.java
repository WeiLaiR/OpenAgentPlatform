package com.weilair.openagent.system.persistence.mapper;

import com.weilair.openagent.system.persistence.model.DatabaseMetaDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DatabaseMetaMapper {

    @Select("""
            SELECT
                DATABASE() AS databaseName,
                VERSION() AS databaseVersion
            """)
    DatabaseMetaDO selectDatabaseMeta();
}
