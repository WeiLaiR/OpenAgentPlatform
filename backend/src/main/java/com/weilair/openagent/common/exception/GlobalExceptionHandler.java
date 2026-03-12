package com.weilair.openagent.common.exception;

import com.weilair.openagent.chat.exception.ChatRequestNotFoundException;
import com.weilair.openagent.chat.exception.ChatServiceUnavailableException;
import com.weilair.openagent.common.response.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(40000, "请求参数不合法", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(40001, "请求参数校验失败", detail));
    }

    @ExceptionHandler(ChatRequestNotFoundException.class)
    public ResponseEntity<ApiError> handleChatRequestNotFound(ChatRequestNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(40400, "聊天请求不存在", exception.getMessage()));
    }

    @ExceptionHandler(ChatServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleChatServiceUnavailable(ChatServiceUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(50300, "聊天模型不可用", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(50000, "系统异常", exception.getMessage()));
    }
}
