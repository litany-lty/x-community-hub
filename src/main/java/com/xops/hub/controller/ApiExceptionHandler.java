package com.xops.hub.controller;

import com.xops.hub.provider.XDataProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException ex) {
        String msg = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "请求参数格式不正确";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "INVALID_INPUT",
                "message", msg,
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "MISSING_PARAM",
                "message", "缺少必要请求参数：" + ex.getParameterName(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(XDataProviderException.class)
    public ResponseEntity<Map<String, Object>> handleProviderError(XDataProviderException ex) {
        int code = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        String msg = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "上游数据接口响应异常";
        log.warn("Provider error (HTTP {}): {}", status.value(), msg);
        return ResponseEntity.status(status).body(Map.of(
                "error", "PROVIDER_ERROR",
                "message", msg,
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse errResp) {
            String msg = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "请求路径或方法不受支持";
            return ResponseEntity.status(errResp.getStatusCode()).body(Map.of(
                    "error", errResp.getStatusCode().toString(),
                    "message", msg,
                    "timestamp", Instant.now().toString()
            ));
        }
        String detail = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : ex.getClass().getSimpleName();
        log.error("Unexpected API exception: {}", detail, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", "服务器处理请求时发生异常：" + detail,
                "timestamp", Instant.now().toString()
        ));
    }
}
