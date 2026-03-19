package com.weilair.openagent.mcp.service;

import dev.langchain4j.mcp.client.McpClient;
import com.weilair.openagent.mcp.model.McpServerDO;

public interface McpClientFactory {

    McpClient createClient(McpServerDO server);
}
