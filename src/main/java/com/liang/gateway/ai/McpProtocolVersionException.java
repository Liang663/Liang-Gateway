package com.liang.gateway.ai;

import java.util.List;

public class McpProtocolVersionException extends RuntimeException {

    private final List<String> supported;

    public McpProtocolVersionException(List<String> supported) {
        super("Unsupported MCP protocol version");
        this.supported = supported == null ? List.of() : List.copyOf(supported);
    }

    public List<String> supported() {
        return supported;
    }
}
