package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.IdentityCodes;
import com.liang.gateway.access.internal.infrastructure.jpa.JpaExecutor;
import com.liang.gateway.access.internal.infrastructure.jpa.UserEntity;
import com.liang.gateway.access.internal.infrastructure.jpa.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class UserAdminService {

    private final JpaExecutor jpaExecutor;
    private final UserRepository userRepository;
    private final AccessClock accessClock;

    public UserAdminService(JpaExecutor jpaExecutor, UserRepository userRepository, AccessClock accessClock) {
        this.jpaExecutor = jpaExecutor;
        this.userRepository = userRepository;
        this.accessClock = accessClock;
    }

    public Mono<UserSnapshot> create(String name, String authority, boolean enabled) {
        return jpaExecutor.call(() -> {
            UserEntity saved = userRepository.save(
                    UserEntity.create(IdentityCodes.userCode(), name, authority, enabled, accessClock.nowShanghai()));
            return toSnapshot(saved);
        });
    }

    public Mono<List<UserSnapshot>> list() {
        return jpaExecutor.call(
                () -> userRepository.findAllByOrderByCreateTimeDesc().stream().map(UserAdminService::toSnapshot).toList());
    }

    public Mono<UserSnapshot> get(String userCode) {
        return jpaExecutor
                .call(() -> userRepository.findByCode(userCode))
                .flatMap(optional -> optional.map(UserAdminService::toSnapshot).map(Mono::just).orElseGet(Mono::empty));
    }

    public Mono<UserSnapshot> update(String userCode, String name, String authority, Boolean enabled) {
        return jpaExecutor.call(() -> {
            UserEntity entity = userRepository
                    .findByCode(userCode)
                    .orElseThrow(() -> new AccessNotFoundException("User not found"));
            boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
            entity.update(name, authority, nextEnabled, accessClock.nowShanghai());
            return toSnapshot(userRepository.save(entity));
        });
    }

    public Mono<UserEntity> requireUser(String userCode) {
        return jpaExecutor
                .call(() -> userRepository.findByCode(userCode))
                .flatMap(optional -> optional
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new AccessNotFoundException("User not found"))));
    }

    static UserSnapshot toSnapshot(UserEntity entity) {
        return new UserSnapshot(
                entity.getCode(),
                entity.getName(),
                entity.getAuthority(),
                entity.isEnabled(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }
}
