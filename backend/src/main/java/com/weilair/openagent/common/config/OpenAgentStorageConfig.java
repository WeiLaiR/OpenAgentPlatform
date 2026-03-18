package com.weilair.openagent.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpenAgentStorageProperties.class)
public class OpenAgentStorageConfig {
}
