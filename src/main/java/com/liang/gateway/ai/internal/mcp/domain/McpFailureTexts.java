package com.liang.gateway.ai.internal.mcp.domain;

public final class McpFailureTexts {

    public static final String TOOL_UNAVAILABLE = "tool_unavailable";
    public static final String MISSING_ARGUMENT = "missing_argument";
    public static final String ARGUMENT_TYPE = "argument_type";
    public static final String UNKNOWN_ARGUMENT = "unknown_argument";
    public static final String PATH_MISMATCH = "path_mismatch";
    public static final String UPSTREAM_ERROR = "upstream_error";
    public static final String UPSTREAM_TIMEOUT = "upstream_timeout";

    static final int BODY_LIMIT = 512;

    private McpFailureTexts() {}

    public static String toolUnavailable() {
        return TOOL_UNAVAILABLE + ": 工具不存在或未启用";
    }

    public static String missingArgument(String name) {
        return MISSING_ARGUMENT + ": 缺少参数 " + name;
    }

    public static String argumentType(String name) {
        return ARGUMENT_TYPE + ": 参数 " + name + " 类型不正确";
    }

    public static String unknownArgument(String name) {
        return UNKNOWN_ARGUMENT + ": 未声明的参数 " + name;
    }

    public static String pathMismatch() {
        return PATH_MISMATCH + ": 路径参数与 URL 占位不符";
    }

    public static String upstreamTimeout() {
        return UPSTREAM_TIMEOUT + ": 上游超时";
    }

    public static String upstreamError(int status, String reason, String body) {
        String truncated = truncate(body);
        String reasonPart = reason == null || reason.isBlank() ? "" : " " + reason;
        return UPSTREAM_ERROR + ": HTTP " + status + reasonPart + "; body=" + truncated;
    }

    static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= BODY_LIMIT ? body : body.substring(0, BODY_LIMIT);
    }
}
