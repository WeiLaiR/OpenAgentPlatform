package com.weilair.openagent.system.service;

import com.weilair.openagent.system.persistence.mapper.DatabaseMetaMapper;
import com.weilair.openagent.system.persistence.model.DatabaseMetaDO;
import com.weilair.openagent.web.vo.SystemDatabaseVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Service;

@Service
public class SystemDatabaseService {

    private final ObjectProvider<DatabaseMetaMapper> databaseMetaMapperProvider;
    private final ObjectProvider<DataSourceProperties> dataSourcePropertiesProvider;

    public SystemDatabaseService(
            ObjectProvider<DatabaseMetaMapper> databaseMetaMapperProvider,
            ObjectProvider<DataSourceProperties> dataSourcePropertiesProvider
    ) {
        this.databaseMetaMapperProvider = databaseMetaMapperProvider;
        this.dataSourcePropertiesProvider = dataSourcePropertiesProvider;
    }

    public SystemDatabaseVO getDatabaseInfo() {
        DataSourceProperties properties = dataSourcePropertiesProvider.getIfAvailable();
        if (properties == null || properties.getUrl() == null || properties.getUrl().isBlank()) {
            return new SystemDatabaseVO("NOT_CONFIGURED", null, null, null, null);
        }

        DatabaseMetaMapper databaseMetaMapper = databaseMetaMapperProvider.getIfAvailable();
        if (databaseMetaMapper == null) {
            return new SystemDatabaseVO(
                    "NOT_CONFIGURED",
                    null,
                    null,
                    properties.getUrl(),
                    properties.getUsername()
            );
        }

        try {
            DatabaseMetaDO databaseMeta = databaseMetaMapper.selectDatabaseMeta();
            return new SystemDatabaseVO(
                    "UP",
                    databaseMeta != null ? databaseMeta.getDatabaseName() : null,
                    databaseMeta != null ? databaseMeta.getDatabaseVersion() : null,
                    properties.getUrl(),
                    properties.getUsername()
            );
        } catch (RuntimeException exception) {
            return new SystemDatabaseVO(
                    "DOWN",
                    null,
                    null,
                    properties.getUrl(),
                    properties.getUsername()
            );
        }
    }
}
