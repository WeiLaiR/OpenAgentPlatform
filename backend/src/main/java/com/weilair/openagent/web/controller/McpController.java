package com.weilair.openagent.web.controller;

import java.util.List;

import com.weilair.openagent.common.response.ApiResponse;
import com.weilair.openagent.mcp.service.McpServerService;
import com.weilair.openagent.web.dto.McpServerUpsertRequest;
import com.weilair.openagent.web.vo.McpConnectionTestVO;
import com.weilair.openagent.web.vo.McpServerVO;
import com.weilair.openagent.web.vo.McpToolSnapshotVO;
import com.weilair.openagent.web.vo.McpToolSyncVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {

    private final McpServerService mcpServerService;

    public McpController(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    @GetMapping("/servers")
    public ApiResponse<List<McpServerVO>> listServers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) String transportType,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(mcpServerService.listServers(keyword, enabled, transportType, limit));
    }

    @PostMapping("/servers")
    public ApiResponse<McpServerVO> createServer(@Valid @RequestBody McpServerUpsertRequest request) {
        return ApiResponse.success(mcpServerService.createServer(request));
    }

    @PutMapping("/servers/{serverId}")
    public ApiResponse<McpServerVO> updateServer(
            @PathVariable Long serverId,
            @Valid @RequestBody McpServerUpsertRequest request
    ) {
        return ApiResponse.success(mcpServerService.updateServer(serverId, request));
    }

    @PostMapping("/servers/{serverId}/connect")
    public ApiResponse<McpConnectionTestVO> testConnection(@PathVariable Long serverId) {
        return ApiResponse.success(mcpServerService.testConnection(serverId));
    }

    @PostMapping("/servers/{serverId}/sync-tools")
    public ApiResponse<McpToolSyncVO> syncTools(@PathVariable Long serverId) {
        return ApiResponse.success(mcpServerService.syncTools(serverId));
    }

    @GetMapping("/tools")
    public ApiResponse<List<McpToolSnapshotVO>> listTools(
            @RequestParam(required = false) Long serverId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(mcpServerService.listTools(serverId, keyword, enabled, limit));
    }

    @PostMapping("/tools/{toolId}/enable")
    public ApiResponse<McpToolSnapshotVO> enableTool(@PathVariable Long toolId) {
        return ApiResponse.success(mcpServerService.updateToolEnabled(toolId, true));
    }

    @PostMapping("/tools/{toolId}/disable")
    public ApiResponse<McpToolSnapshotVO> disableTool(@PathVariable Long toolId) {
        return ApiResponse.success(mcpServerService.updateToolEnabled(toolId, false));
    }
}
