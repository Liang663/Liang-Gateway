package com.liang.gateway.access.internal.infrastructure.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByCode(String code);

    List<UserEntity> findAllByOrderByCreateTimeDesc();
}
