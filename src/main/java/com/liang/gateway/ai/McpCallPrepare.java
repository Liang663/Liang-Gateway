package com.liang.gateway.ai;

import java.util.Objects;

public sealed interface McpCallPrepare permits McpCallPrepare.Ready, McpCallPrepare.Completed {

    default boolean needsOutbound() {
        return this instanceof Ready;
    }

    record Ready(McpUpstream upstream) implements McpCallPrepare {
        public Ready {
            Objects.requireNonNull(upstream, "upstream");
        }
    }

    record Completed(McpCallResult result) implements McpCallPrepare {
        public Completed {
            Objects.requireNonNull(result, "result");
        }
    }
}
