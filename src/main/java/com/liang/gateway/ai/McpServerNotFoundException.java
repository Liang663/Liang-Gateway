package com.liang.gateway.ai;

public class McpServerNotFoundException extends RuntimeException {

    private final String serverPath;

    public McpServerNotFoundException(String serverPath) {
        super("MCP server not found: " + (serverPath == null ? "" : serverPath));
        this.serverPath = serverPath;
    }

    public String serverPath() {
        return serverPath;
    }
}
