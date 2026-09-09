package com.liang.gateway.ai.internal.mcp.application;

import com.liang.gateway.ai.internal.infrastructure.AiClock;
import com.liang.gateway.ai.internal.mcp.infrastructure.McpIdentityCodes;
import com.liang.gateway.ai.internal.mcp.infrastructure.persistence.McpServerEntity;
import com.liang.gateway.ai.internal.mcp.infrastructure.persistence.McpServerRepository;
import com.liang.gateway.ai.internal.mcp.infrastructure.persistence.McpToolRepository;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Service
public class McpServerAdminService {

    private static final Pattern SERVER_PATH = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final McpServerRepository serverRepository;
    private final McpToolRepository toolRepository;
    private final AiClock aiClock;
    private final TransactionalOperator transactionalOperator;

    public McpServerAdminService(
            McpServerRepository serverRepository,
            McpToolRepository toolRepository,
            AiClock aiClock,
            TransactionalOperator transactionalOperator) {
        this.serverRepository = serverRepository;
        this.toolRepository = toolRepository;
        this.aiClock = aiClock;
        this.transactionalOperator = transactionalOperator;
    }

    public Mono<McpServerSnapshot> create(String name, String path, String description, String version, boolean enabled) {
        return Mono.defer(() -> {
            String nextName = requireText(name, "name", 128);
            String nextPath = requirePath(path);
            String nextVersion = requireText(version, "version", 32);
            String nextDescription = optionalText(description, "description", 512);
            return serverRepository
                    .findByPath(nextPath)
                    .flatMap(existing -> Mono.error(new McpBadRequestException("path already exists")))
                    .switchIfEmpty(Mono.defer(() -> serverRepository.save(McpServerEntity.create(
                            McpIdentityCodes.serverCode(),
                            nextName,
                            nextPath,
                            nextDescription,
                            nextVersion,
                            enabled,
                            aiClock.nowShanghai()))))
                    .cast(McpServerEntity.class)
                    .map(McpServerAdminService::toSnapshot);
        });
    }

    public Mono<List<McpServerSnapshot>> list() {
        return serverRepository.findAllByOrderByCreateTimeDesc().map(McpServerAdminService::toSnapshot).collectList();
    }

    public Mono<McpServerSnapshot> get(String code) {
        return requireServer(code).map(McpServerAdminService::toSnapshot);
    }

    public Mono<McpServerSnapshot> update(
            String code, String name, String path, String description, String version, Boolean enabled) {
        return requireServer(code).flatMap(entity -> {
            String nextName = name == null ? entity.getName() : requireText(name, "name", 128);
            String nextPath = path == null ? entity.getPath() : requirePath(path);
            String nextVersion = version == null ? entity.getVersion() : requireText(version, "version", 32);
            String nextDescription =
                    description == null ? entity.getDescription() : optionalText(description, "description", 512);
            boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
            return serverRepository
                    .findByPath(nextPath)
                    .flatMap(existing -> {
                        if (!existing.getCode().equals(code)) {
                            return Mono.error(new McpBadRequestException("path already exists"));
                        }
                        return Mono.just(entity);
                    })
                    .switchIfEmpty(Mono.just(entity))
                    .flatMap(current -> {
                        current.update(nextName, nextPath, nextDescription, nextVersion, nextEnabled, aiClock.nowShanghai());
                        return serverRepository.save(current).map(McpServerAdminService::toSnapshot);
                    });
        });
    }

    public Mono<Void> delete(String code) {
        return requireServer(code)
                .flatMap(entity -> transactionalOperator.transactional(toolRepository
                        .deleteByServerCode(entity.getCode())
                        .then(serverRepository.delete(entity))))
                .then();
    }

    Mono<McpServerEntity> requireServer(String code) {
        if (code == null || code.isBlank()) {
            return Mono.error(new McpNotFoundException("MCP server not found"));
        }
        return serverRepository
                .findByCode(code)
                .switchIfEmpty(Mono.error(new McpNotFoundException("MCP server not found")));
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
