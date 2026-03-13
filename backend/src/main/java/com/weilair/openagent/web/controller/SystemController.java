package com.weilair.openagent.web.controller;

import com.weilair.openagent.common.response.ApiResponse;
import com.weilair.openagent.system.service.SystemDatabaseService;
import com.weilair.openagent.system.service.SystemHealthService;
import com.weilair.openagent.web.vo.SystemDatabaseVO;
import com.weilair.openagent.web.vo.SystemHealthVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final SystemHealthService systemHealthService;
    private final SystemDatabaseService systemDatabaseService;

    public SystemController(
            SystemHealthService systemHealthService,
            SystemDatabaseService systemDatabaseService
    ) {
        this.systemHealthService = systemHealthService;
        this.systemDatabaseService = systemDatabaseService;
    }

    @GetMapping("/health")
    public ApiResponse<SystemHealthVO> health() {
        return ApiResponse.success(systemHealthService.getHealth());
    }

    @GetMapping("/database")
    public ApiResponse<SystemDatabaseVO> database() {
        return ApiResponse.success(systemDatabaseService.getDatabaseInfo());
    }
}
