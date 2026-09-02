package com.liang.gateway.core.support;

import com.liang.gateway.core.PipelineApi;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestPipelineConfiguration {

    @Bean
    TestPipelineController testPipelineController(PipelineApi pipelineApi) {
        return new TestPipelineController(pipelineApi);
    }
}
