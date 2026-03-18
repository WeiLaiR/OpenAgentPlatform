package com.weilair.openagent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openagent.storage")
public class OpenAgentStorageProperties {

    private String type = "LOCAL";
    private final Local local = new Local();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Local getLocal() {
        return local;
    }

    public static class Local {

        private String baseDir;

        public String getBaseDir() {
            return baseDir;
        }

        public void setBaseDir(String baseDir) {
            this.baseDir = baseDir;
        }
    }
}
