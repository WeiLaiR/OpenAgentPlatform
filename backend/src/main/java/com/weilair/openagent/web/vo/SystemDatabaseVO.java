package com.weilair.openagent.web.vo;

public record SystemDatabaseVO(
        String status,
        String databaseName,
        String databaseVersion,
        String jdbcUrl,
        String username
) {
}
