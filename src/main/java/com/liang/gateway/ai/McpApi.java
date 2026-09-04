package com.liang.gateway.ai;

import java.util.Map;
import reactor.core.publisher.Mono;

public interface McpApi {

    String PROTOCOL_VERSION = "2026-07-28";

    default Mono<McpDiscoverResult> discover(String serverPath) {
        return discover(serverPath, null);
    }

    Mono<McpDiscoverResult> discover(String serverPath, String protocolVersion);

    default Mono<McpToolsListResult> listTools(String serverPath) {
        return listTools(serverPath, null);
    }

    Mono<McpToolsListResult> listTools(String serverPath, String protocolVersion);

    default Mono<McpCallPrepare> prepareCall(String serverPath, String toolName, Map<String, Object> arguments) {
        return prepareCall(serverPath, toolName, arguments, null);
    }

    Mono<McpCallPrepare> prepareCall(
            String serverPath, String toolName, Map<String, Object> arguments, String protocolVersion);

    McpCallResult wrapCall(Integer httpStatus, String responseBody, boolean timedOut);
}
