package com.weilair.openagent.common.response;

import com.weilair.openagent.common.request.RequestIdContext;

public record ApiResponse<T>(
        Integer code,
        String message,
        T data,
        Long timestamp,
        String requestId
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data, System.currentTimeMillis(), RequestIdContext.getRequestId());
    }
}
