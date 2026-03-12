package com.weilair.openagent.common.response;

import com.weilair.openagent.common.request.RequestIdContext;

public record ApiError(
        Integer code,
        String message,
        String detail,
        String requestId,
        Long timestamp
) {

    public static ApiError of(Integer code, String message, String detail) {
        return new ApiError(code, message, detail, RequestIdContext.getRequestId(), System.currentTimeMillis());
    }
}
