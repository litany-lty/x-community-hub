package com.xops.hub.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.provider.XDataProviderException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<byte[]> handleBadInput(IllegalArgumentException ex, HttpServletRequest request) {
        String msg = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "请求参数格式不正确";
        return jsonResponse(HttpStatus.BAD_REQUEST, Map.of(
                "error", "INVALID_INPUT",
                "message", msg,
                "timestamp", Instant.now().toString()
        ), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<byte[]> handleUnreadableRequest(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return jsonResponse(HttpStatus.BAD_REQUEST, Map.of(
                "error", "INVALID_INPUT",
                "message", "请求内容格式不正确，请检查发布时间等字段后重试",
                "timestamp", Instant.now().toString()
        ), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<byte[]> handleInvalidParameter(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "scheduledAtEpochMs".equals(ex.getName())
                ? "发布时间格式不正确，请重新选择时间"
                : "请求参数格式不正确，请检查输入后重试";
        return jsonResponse(HttpStatus.BAD_REQUEST, Map.of(
                "error", "INVALID_INPUT",
                "message", message,
                "timestamp", Instant.now().toString()
        ), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<byte[]> handleMaxUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return jsonResponse(HttpStatus.BAD_REQUEST, Map.of(
                "error", "INVALID_INPUT",
                "message", "上传图片体积超出限制（单张静态图最大 5MB，GIF 最大 15MB，总请求不超过 25MB）",
                "timestamp", Instant.now().toString()
        ), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<byte[]> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return jsonResponse(HttpStatus.BAD_REQUEST, Map.of(
                "error", "MISSING_PARAM",
                "message", "缺少必要请求参数：" + ex.getParameterName(),
                "timestamp", Instant.now().toString()
        ), request);
    }

    @ExceptionHandler(XDataProviderException.class)
    public ResponseEntity<byte[]> handleProviderError(XDataProviderException ex, HttpServletRequest request) {
        int code = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        String msg = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "上游数据接口响应异常";
        log.warn("Provider error (HTTP {}): {}", status.value(), msg);
        return jsonResponse(status, Map.of(
                "error", "PROVIDER_ERROR",
                "message", msg,
                "timestamp", Instant.now().toString()
        ), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> handleUnexpected(
            Exception ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (isClientDisconnect(ex) || (response != null && response.isCommitted())) {
            String method = request != null ? request.getMethod() : "";
            String uri = request != null ? request.getRequestURI() : "unknown";
            if ("POST".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
                log.warn("Client or proxy disconnected before completing {} {}: {}", method, uri, ex.toString());
            } else {
                log.debug("Client disconnected or response already committed on {}: {}", uri, ex.toString());
            }
            return null;
        }
        if (ex instanceof org.springframework.web.ErrorResponse errResp) {
            String msg = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : "请求路径或方法不受支持";
            return jsonResponse(errResp.getStatusCode(), Map.of(
                    "error", errResp.getStatusCode().toString(),
                    "message", msg,
                    "timestamp", Instant.now().toString()
            ), request);
        }
        String detail = (ex.getMessage() != null && !ex.getMessage().isBlank()) ? ex.getMessage() : ex.getClass().getSimpleName();
        log.error("Unexpected API exception: {}", detail, ex);
        return jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, Map.of(
                "error", "INTERNAL_ERROR",
                "message", "服务器处理请求时发生内部异常，请稍后重试",
                "timestamp", Instant.now().toString()
        ), request);
    }

    static boolean isClientDisconnect(Throwable error) {
        Throwable cur = error;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            if (cur instanceof AsyncRequestNotUsableException
                    || cur instanceof EOFException
                    || cur.getClass().getSimpleName().contains("ClientAbortException")) {
                return true;
            }
            if (cur instanceof IOException && hasDisconnectMessage(cur.getMessage())) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean hasDisconnectMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("broken pipe")
                || lower.contains("connection reset")
                || lower.contains("connection abort")
                || lower.contains("stream is closed");
    }

    private static ResponseEntity<byte[]> jsonResponse(
            HttpStatusCode status,
            Map<String, Object> body,
            HttpServletRequest request
    ) {
        if (request != null && "HEAD".equalsIgnoreCase(request.getMethod())) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new byte[0]);
        }
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            payload = "{\"error\":\"INTERNAL_ERROR\"}".getBytes(StandardCharsets.UTF_8);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }
}
