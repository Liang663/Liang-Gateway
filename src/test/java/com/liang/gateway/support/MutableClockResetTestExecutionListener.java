package com.liang.gateway.support;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

public class MutableClockResetTestExecutionListener extends AbstractTestExecutionListener {

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        testContext.getApplicationContext().getBeanProvider(MutableClock.class).ifAvailable(MutableClock::reset);
    }
}
