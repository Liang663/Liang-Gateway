package com.liang.gateway.ai;

import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

public interface McpApi {

    String PROTOCOL_VERSION = "2026-07-28";

    record Transport(String protocolVersion, String methodHeader, String nameHeader) {}

    sealed interface Outcome {
        record Reply(int httpStatus, Map<String, Object> jsonRpc) implements Outcome {
            public Reply {
                jsonRpc = jsonRpc == null ? Map.of() : jsonRpc;
            }
        }

        record NeedsOutbound(Object id, McpUpstream upstream) implements Outcome {
            public NeedsOutbound {
                Objects.requireNonNull(upstream, "upstream");
            }
        }
    }

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

    Mono<Outcome> handle(String serverPath, Transport transport, byte[] jsonRpcBody);

    Mono<Outcome.Reply> completeCall(Object id, Integer httpStatus, String body, boolean timedOut);
}
