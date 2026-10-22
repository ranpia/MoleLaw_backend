package com.MoleLaw_backend.chat.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptionConfigurationTest {
    @TempDir Path directory;

    @Test
    void injectsDotenvValuesThroughTheRealApplicationYamlWithoutProcessEnvironment() throws Exception {
        Path dotenv = directory.resolve(".env");
        Files.writeString(dotenv, "CHAT_ENCRYPTION_KEY=" + TestEncryption.KEY
                + "\nCHAT_ENCRYPTION_KEY_VERSION=dotenv-v1\n");
        runner(dotenv).run(context -> {
            assertThat(context).hasNotFailed();
            var encryptor = context.getBean(EncryptUtil.class);
            String encrypted = encryptor.encrypt("설정 파일로 관리");
            assertThat(encrypted).startsWith("gcm1.dotenv-v1.");
            assertThat(new EncryptUtil(TestEncryption.KEY, "dotenv-v1").decrypt(encrypted))
                    .isEqualTo("설정 파일로 관리");
        });
    }

    @Test
    void anEmptyDotenvKeyFailsStartupInsteadOfFallingBackToAnotherKey() throws Exception {
        Path dotenv = directory.resolve(".env");
        Files.writeString(dotenv, "CHAT_ENCRYPTION_KEY=\nCHAT_ENCRYPTION_KEY_VERSION=v1\n");
        runner(dotenv).run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner runner(Path dotenv) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().remove("systemEnvironment");
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withUserConfiguration(EncryptUtil.class)
                .withPropertyValues("spring.config.location=classpath:application.yml",
                        "spring.config.import=" + dotenv.toUri() + "[.properties]",
                        "spring.profiles.active=encryption-configuration");
    }
}
