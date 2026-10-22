package com.MoleLaw_backend.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class DotenvProfileConfigurationTest {
    @TempDir Path directory;

    @Test
    void dotenvAloneActivatesLocalAndItsMysqlQdrantGroup() throws Exception {
        var dotenv = directory.resolve(".env");
        Files.writeString(dotenv, "SPRING_PROFILES_ACTIVE=local\n");
        runner(dotenv).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getActiveProfiles()).contains("local", "mysql", "qdrant");
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("true");
        });
    }

    @Test
    void explicitProfileOverridesDotenv() throws Exception {
        var dotenv = directory.resolve(".env");
        Files.writeString(dotenv, "SPRING_PROFILES_ACTIVE=local\n");
        runner(dotenv).withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
        });
    }

    private ApplicationContextRunner runner(Path dotenv) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().remove("systemEnvironment");
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withPropertyValues("spring.config.location=classpath:application.yml",
                        "spring.config.import=" + dotenv.toUri() + "[.properties]");
    }
}
