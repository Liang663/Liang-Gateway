package com.liang.gateway.access.internal.infrastructure.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;

abstract class PersistableRow implements Persistable<Long> {

    @Id
    private Long id;

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return id == null;
    }
}
