package com.liang.gateway.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpDiscoverResult(
        List<String> supportedVersions,
        Map<String, Object> capabilities,
        @JsonProperty("_meta") Meta meta,
        String instructions,
        String resultType,
        long ttlMs,
        String cacheScope) {

    public McpDiscoverResult {
        Objects.requireNonNull(supportedVersions, "supportedVersions");
        supportedVersions = List.copyOf(supportedVersions);
        capabilities = capabilities == null ? Map.of("tools", Map.of()) : Map.copyOf(capabilities);
        Objects.requireNonNull(meta, "meta");
        resultType = resultType == null ? "complete" : resultType;
        cacheScope = cacheScope == null ? "private" : cacheScope;
    }

    public record Meta(ServerInfo serverInfo) {
        public Meta {
            Objects.requireNonNull(serverInfo, "serverInfo");
        }
    }

    public record ServerInfo(String name, String version) {
        public ServerInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
        }
    }
}
