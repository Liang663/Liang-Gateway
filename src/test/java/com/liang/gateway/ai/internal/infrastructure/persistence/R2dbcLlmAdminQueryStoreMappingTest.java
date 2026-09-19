package com.liang.gateway.ai.internal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class R2dbcLlmAdminQueryStoreMappingTest {

    @Test
    void supportsNumericTinyintAndBooleanSuccessColumns() {
        assertThat(R2dbcLlmAdminQueryStore.isSuccessful((byte) 1)).isTrue();
        assertThat(R2dbcLlmAdminQueryStore.isSuccessful((byte) 0)).isFalse();
        assertThat(R2dbcLlmAdminQueryStore.isSuccessful(1)).isTrue();
        assertThat(R2dbcLlmAdminQueryStore.isSuccessful(0)).isFalse();
        assertThat(R2dbcLlmAdminQueryStore.isSuccessful(true)).isTrue();
        assertThat(R2dbcLlmAdminQueryStore.isSuccessful(false)).isFalse();
    }
}
