package com.liang.gateway.access.internal.web;

import com.liang.gateway.access.internal.application.AccessBadRequestException;
import com.liang.gateway.access.internal.application.AccessNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AccessWebAdvice {

    @ExceptionHandler(AccessNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(AccessNotFoundException ex) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ExceptionHandler(AccessBadRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(AccessBadRequestException ex) {
        return envelope(HttpStatus.BAD_REQUEST, "request_error", ex.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String type, String message) {
        String safeMessage = message == null ? "" : message;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", Map.of("message", safeMessage, "type", type)));
    }
}
