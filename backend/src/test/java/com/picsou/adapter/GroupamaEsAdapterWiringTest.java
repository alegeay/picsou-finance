package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GroupamaEsAdapterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues("app.groupama-es-auth.url=http://groupama-es-auth:8001")
        .withBean(ObjectMapper.class)
        .withBean(GroupamaEsAdapter.class);

    @Test
    void springSelectsTheProductionConstructor() {
        contextRunner.run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(GroupamaEsAdapter.class));
    }
}
