package com.liang.gateway.ai.internal.mcp.application;

import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.infrastructure.jpa.AiJpaExecutor;
import com.liang.gateway.ai.internal.mcp.infrastructure.McpIdentityCodes;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpServerEntity;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpServerRepository;
import com.liang.gateway.ai.internal.mcp.infrastructure.jpa.McpToolRepository;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class McpServerAdminService {

    private static final Pattern SERVER_PATH = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final AiJpaExecutor jpaExecutor;
    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final AiClock aiClock;

    public McpServerAdminService(
            AiJpaExecutor jpaExecutor,
            McpServerRepository serverRepository,
            McpToolRepository toolRepository,
            AiClock aiClock) {
        this.jpaExecutor = jpaExecutor;
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.aiClock = aiClock;
    }

    public Mono<McpServerSnapshot> create(String name, String path, String description, String version, boolean enabled) {
        return jpaExecutor.call(() -> {
            String nextName = requireText(name, "name", 128);
            String nextPath = requirePath(path);
            String nextVersion = requireText(version, "version", 32);
            String nextDescription = optionalText(description, "description", 512);
            serverRepository.findByPath(nextPath).ifPresent(existing -> {
                throw new McpBadRequestException("path already exists");
            });
            McpServerEntity saved = serverRepository.save(McpServerEntity.create(
                    McpIdentityCodes.serverCode(),
                    nextName,
                    nextPath,
                    nextDescription,
                    nextVersion,
                    enabled,
                    aiClock.nowShanghai()));
            return toSnapshot(saved);
        });
    }

    public Mono<List<McpServerSnapshot>> list() {
        return jpaExecutor.call(() -> serverRepository.findAllByOrderByCreateTimeDesc().stream()
                .map(McpServerAdminService::toSnapshot)
                .toList());
    }

    public Mono<McpServerSnapshot> get(String code) {
        return jpaExecutor.call(() -> toSnapshot(requireServer(code)));
    }

    public Mono<McpServerSnapshot> update(
            String code, String name, String path, String description, String version, Boolean enabled) {
        return jpaExecutor.call(() -> {
            McpServerEntity entity = requireServer(code);
            String nextName = name == null ? entity.getName() : requireText(name, "name", 128);
            String nextPath = path == null ? entity.getPath() : requirePath(path);
            String nextVersion = version == null ? entity.getVersion() : requireText(version, "version", 32);
            String nextDescription =
                    description == null ? entity.getDescription() : optionalText(description, "description", 512);
            boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
            serverRepository.findByPath(nextPath).ifPresent(existing -> {
                if (!existing.getCode().equals(code)) {
                    throw new McpBadRequestException("path already exists");
                }
            });
            entity.update(nextName, nextPath, nextDescription, nextVersion, nextEnabled, aiClock.nowShanghai());
            return toSnapshot(serverRepository.save(entity));
        });
    }

    public Mono<Void> delete(String code) {
        return jpaExecutor.run(() -> {
            McpServerEntity entity = requireServer(code);
            toolRepository.deleteByServerCode(entity.getCode());
            serverRepository.delete(entity);
        });
    }

    McpServerEntity requireServer(String code) {
        if (code == null || code.isBlank()) {
            throw new McpNotFoundException("MCP server not found");
        }
        return serverRepository
                .findByCode(code)
                .orElseThrow(() -> new McpNotFoundException("MCP server not found"));
    }

    static McpServerSnapshot toSnapshot(McpServerEntity entity) {
        return new McpServerSnapshot(
                entity.getCode(),
                entity.getName(),
                entity.getPath(),
                entity.getDescription(),
                entity.getVersion(),
                entity.isEnabled(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    static String requirePath(String path) {
        String value = requireText(path, "path", 64);
        if (!SERVER_PATH.matcher(value).matches()) {
            throw new McpBadRequestException("path must be letters, digits, '-' or '_'");
        }
        return value;
    }

    static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new McpBadRequestException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new McpBadRequestException(field + " is too long");
        }
        return trimmed;
    }

    static String optionalText(String value, String field, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new McpBadRequestException(field + " is too long");
        }
        return trimmed;
    }
}
