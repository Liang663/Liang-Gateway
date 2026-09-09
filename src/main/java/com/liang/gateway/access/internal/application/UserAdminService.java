package com.liang.gateway.access.internal.application;

import com.liang.gateway.access.internal.infrastructure.AccessClock;
import com.liang.gateway.access.internal.infrastructure.IdentityCodes;
import com.liang.gateway.access.internal.infrastructure.persistence.UserEntity;
import com.liang.gateway.access.internal.infrastructure.persistence.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class UserAdminService {

    private final UserRepository userRepository;
    private final AccessClock accessClock;

    public UserAdminService(UserRepository userRepository, AccessClock accessClock) {
        this.userRepository = userRepository;
        this.accessClock = accessClock;
    }

    public Mono<UserSnapshot> create(String name, String authority, boolean enabled) {
        return userRepository
                .save(UserEntity.create(IdentityCodes.userCode(), name, authority, enabled, accessClock.nowShanghai()))
                .map(UserAdminService::toSnapshot);
    }

    public Mono<List<UserSnapshot>> list() {
        return userRepository.findAllByOrderByCreateTimeDesc().map(UserAdminService::toSnapshot).collectList();
    }

    public Mono<UserSnapshot> get(String userCode) {
        return userRepository.findByCode(userCode).map(UserAdminService::toSnapshot);
    }

    public Mono<UserSnapshot> update(String userCode, String name, String authority, Boolean enabled) {
        return requireUser(userCode).flatMap(entity -> {
            boolean nextEnabled = enabled == null ? entity.isEnabled() : enabled;
            entity.update(name, authority, nextEnabled, accessClock.nowShanghai());
            return userRepository.save(entity).map(UserAdminService::toSnapshot);
        });
    }

    public Mono<UserEntity> requireUser(String userCode) {
        return userRepository
                .findByCode(userCode)
                .switchIfEmpty(Mono.error(new AccessNotFoundException("User not found")));
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
