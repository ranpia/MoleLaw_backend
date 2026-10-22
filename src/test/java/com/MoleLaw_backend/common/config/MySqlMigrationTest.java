package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.chat.util.TestEncryption;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.entity.ChatEvidenceSnapshot;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.law.entity.LegalSource;
import com.MoleLaw_backend.law.entity.LegalSourceIndex;
import com.MoleLaw_backend.user.entity.User;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.MySQLContainer;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;

@Tag("mysql-integration")
class MySqlMigrationTest {
    private MySQLContainer<?> database() {
        return new MySQLContainer<>("mysql:8.4.11").withDatabaseName("molelaw_migration_test")
                .withUsername("migration_test").withPassword("isolated-test-password")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
    }
    private ApplicationContextRunner runner(MySQLContainer<?> db) {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(MySqlConfig.class, Entities.class)
                .withPropertyValues("spring.profiles.active=mysql", "spring.datasource.url=" + db.getJdbcUrl(),
                        "spring.datasource.username=" + db.getUsername(), "spring.datasource.password=" + db.getPassword(),
                        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC");
    }
    @Test void freshDatabaseMigratesBeforeJpaAndPreservesDataAcrossRestart() {
        try (var db = database()) {
            db.start();
            runner(db).run(context -> {
                assertThat(context).hasNotFailed();
                var flyway = context.getBean(Flyway.class);
                assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
                assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
                var factory = context.getBean(EntityManagerFactory.class);
                try (var em = factory.createEntityManager()) {
                    em.getTransaction().begin();
                    var user = User.builder().email("mysql-test@example.com").provider("local")
                            .password("test-password-hash").nickname("한글😀").build();
                    em.persist(user);
                    var room = ChatRoom.builder().title("통합 검증😀").user(user).build();
                    em.persist(room);
                    String plaintext = "한글 긴 암호문😀".repeat(6000);
                    String encrypted = TestEncryption.create().encrypt(plaintext);
                    assertThat(encrypted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(65535);
                    var message = Message.builder().chatRoom(room).sender(Message.Sender.USER).content(encrypted).build();
                    em.persist(message);
                    em.persist(new ChatEvidenceSnapshot(room, "[]", "a".repeat(64)));
                    var source = new LegalSource("LAW", "123", "001823", "20260101",
                            "https://www.law.go.kr/", "a".repeat(64), "원문😀".repeat(25000));
                    em.persist(source);
                    var index = new LegalSourceIndex(source.getId(), source.getContentHash(), "b".repeat(64),
                            "molelaw_service_test", "test-model", "test-revision", 2);
                    index.complete(1);
                    em.persist(index);
                    em.getTransaction().commit();
                    em.clear();
                    assertThat(TestEncryption.create().decrypt(em.find(Message.class, message.getId()).getContent())).isEqualTo(plaintext);
                    assertThat(em.find(LegalSource.class, source.getId()).getRawJson()).isEqualTo(source.getRawJson());
                    assertThat(em.find(ChatEvidenceSnapshot.class, room.getId()).getSchemaVersion()).isEqualTo(2);
                }
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                assertAccountConstraints(jdbc);
                assertThatThrownBy(() -> jdbc.update("INSERT INTO chat_room (title,user_id,created_at) VALUES ('',1,NOW())"))
                        .isInstanceOf(DataAccessException.class);
                assertThatThrownBy(() -> jdbc.update("INSERT INTO message (chat_room_id,sender,content,timestamp) VALUES (1,'INFO','x',NOW())"))
                        .isInstanceOf(DataAccessException.class);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('law','law_chunk','law_embedding')", Integer.class)).isZero();
            });
            runner(db).run(context -> {
                assertThat(context).hasNotFailed();
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class)).isEqualTo(4);
                assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
                jdbc.update("DELETE FROM `user` WHERE email='mysql-test@example.com'");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_room", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_evidence_snapshot", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legal_source", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legal_source_index", Integer.class)).isEqualTo(1);
            });
        }
    }
    private void assertAccountConstraints(JdbcTemplate jdbc) {
        String insert = "INSERT INTO `user` (email,provider,nickname,password_hash,provider_subject) VALUES (?,?,?,?,?)";
        assertThatThrownBy(() -> jdbc.update(insert, "mysql-test@example.com", "local", "name", "hash", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_user_email_provider");
        assertThatThrownBy(() -> jdbc.update(insert, "missing@example.test", "local", "name", null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_user_credentials");
        assertThatThrownBy(() -> jdbc.update(insert, "subject@example.test", "local", "name", "hash", "subject"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_user_credentials");
        assertThatThrownBy(() -> jdbc.update(insert, "oauth@example.test", "google", "name", "hash", "subject"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_user_credentials");
        assertThatThrownBy(() -> jdbc.update(insert, "missing-subject@example.test", "google", "name", null, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_user_credentials");
        assertThatThrownBy(() -> jdbc.update(insert, "blank-subject@example.test", "google", "name", null, " "))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_user_credentials");
        assertThatThrownBy(() -> jdbc.update(insert, "unsupported@example.test", "facebook", "name", null, "subject"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Check constraint");
        assertThatThrownBy(() -> jdbc.update(insert, "uppercase@example.test", "LOCAL", "name", "hash", null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("Check constraint");
        assertThatThrownBy(() -> jdbc.update(insert, "blank-name@example.test", "local", " ", "hash", null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_user_nickname_nonblank");
        assertThatThrownBy(() -> jdbc.update(insert, " ", "local", "name", "hash", null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_user_email_nonblank");
        jdbc.update(insert, "upper@example.test", "google", "name", null, "Subject");
        jdbc.update(insert, "lower@example.test", "google", "name", null, "subject");
        jdbc.update(insert, "other@example.test", "kakao", "name", null, "Subject");
        assertThatThrownBy(() -> jdbc.update(insert, "duplicate@example.test", "google", "name", null, "Subject"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_user_provider_subject");
    }
    @Test void unmanagedNonemptyDatabaseIsRejectedWithoutAutomaticBaseline() throws Exception {
        try (var db = database()) {
            db.start();
            try (var c = java.sql.DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword()); var s = c.createStatement()) {
                s.execute("CREATE TABLE unmanaged_data (id BIGINT PRIMARY KEY)"); s.execute("INSERT INTO unmanaged_data VALUES (1)");
            }
            var flyway = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class).hasMessageContaining("non-empty");
        }
    }
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {User.class, ChatRoom.class, LegalSource.class})
    static class Entities { }
}