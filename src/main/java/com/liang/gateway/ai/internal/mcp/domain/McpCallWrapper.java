package com.liang.gateway.ai.internal.mcp.domain;

import com.liang.gateway.ai.McpCallResult;
import org.springframework.http.HttpStatus;

public final class McpCallWrapper {

    private McpCallWrapper() {}

    public static McpCallResult wrap(Integer httpStatus, String responseBody, boolean timedOut) {
        if (timedOut) {
            return McpCallResult.error(McpFailureTexts.upstreamTimeout());
        }
        int status = httpStatus == null ? 0 : httpStatus;
        if (status >= 200 && status < 300) {
            return McpCallResult.ok(responseBody == null ? "" : responseBody);
        }
        HttpStatus resolved = HttpStatus.resolve(status);
        String reason = resolved == null ? "" : resolved.getReasonPhrase();
        return McpCallResult.error(McpFailureTexts.upstreamError(status, reason, responseBody));
    }
}
