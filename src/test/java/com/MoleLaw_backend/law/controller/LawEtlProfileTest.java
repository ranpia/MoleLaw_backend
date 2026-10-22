package com.MoleLaw_backend.law.controller;

import com.MoleLaw_backend.law.service.etl.LawEtlTestService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LawEtlProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LawEtlTestController.class)
            .withBean(LawEtlTestService.class, () -> mock(LawEtlTestService.class));

    @Test
    void onlyEnabledLocalWithoutTestProfileRegistersController() {
        runner.withPropertyValues("app.law-vector.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LawEtlTestController.class));
        runner.withPropertyValues("spring.profiles.active=local", "app.law-vector.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(LawEtlTestController.class));
        runner.withPropertyValues("spring.profiles.active=local,test", "app.law-vector.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(LawEtlTestController.class));
        runner.withPropertyValues("spring.profiles.active=local", "app.law-vector.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LawEtlTestController.class));
    }
}
