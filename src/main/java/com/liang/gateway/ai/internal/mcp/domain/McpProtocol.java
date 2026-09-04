package com.liang.gateway.ai.internal.mcp.domain;

import com.liang.gateway.ai.McpApi;
import com.liang.gateway.ai.McpProtocolVersionException;
import java.util.List;

public final class McpProtocol {

    public static final String VERSION = McpApi.PROTOCOL_VERSION;

    private McpProtocol() {}

    public static void requireSupported(String protocolVersion) {
        if (protocolVersion == null || protocolVersion.isBlank()) {
            return;
        }
        if (!VERSION.equals(protocolVersion)) {
            throw new McpProtocolVersionException(List.of(VERSION));
        }
    }
}
