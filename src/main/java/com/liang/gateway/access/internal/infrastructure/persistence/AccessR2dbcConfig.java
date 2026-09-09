package com.liang.gateway.access.internal.infrastructure.persistence;

import io.r2dbc.spi.ConnectionFactory;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class AccessR2dbcConfig {

    @Bean
    @ConditionalOnMissingBean
    TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }

    @Bean
    R2dbcCustomConversions r2dbcCustomConversions(ConnectionFactory connectionFactory) {
        return R2dbcCustomConversions.of(
                DialectResolver.getDialect(connectionFactory),
                List.of(new ByteToBooleanConverter(), new IntegerToBooleanConverter(), new BooleanToByteConverter()));
    }
}
