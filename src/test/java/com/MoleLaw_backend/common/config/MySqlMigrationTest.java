package com.MoleLaw_backend.common.config;

import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.entity.Message;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import com.MoleLaw_backend.law.entity.Law;
import com.MoleLaw_backend.law.entity.LawChunk;
import com.MoleLaw_backend.law.entity.LawEmbedding;
import com.MoleLaw_backend.law.entity.LegalSource;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mysql-integration")
class MySqlMigrationTest {
    private MySQLContainer<?> database() {
        return new MySQLContainer<>("mysql:8.4.11")
                .withDatabaseName("molelaw_migration_test")
                .withUsername("migration_test").withPassword("isolated-test-password")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
    }

    private ApplicationContextRunner runner(MySQLContainer<?> db) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(MySqlConfig.class, Entities.class)
                .withPropertyValues("spring.profiles.active=mysql",
                        "spring.datasource.url=" + db.getJdbcUrl(),
                        "spring.datasource.username=" + db.getUsername(),
                        "spring.datasource.password=" + db.getPassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC");
    }

    @Test
    void freshDatabaseMigratesBeforeJpaAndPreservesDataAcrossRestart() {
        try (var db = database()) {
            db.start();
            runner(db).run(context -> {
                assertThat(context).hasNotFailed();
                var flyway = context.getBean(Flyway.class);
                assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
                assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
                var factory = context.getBean(EntityManagerFactory.class);
                try (var em = factory.createEntityManager()) {
                    em.getTransaction().begin();
                    User user = User.builder().email("mysql-test@example.com").provider("local")
                            .password("test-password-hash").nickname("한글😀").build();
                    em.persist(user);
                    ChatRoom room = ChatRoom.builder().title("통합 검사😀").user(user).build();
                    em.persist(room);
                    String encrypted = EncryptUtil.encrypt("암호화 저장 검사😀".repeat(1000));
                    em.persist(Message.builder().chatRoom(room).sender(Message.Sender.USER).content(encrypted).build());
                    Law law = Law.builder().name("검사용 가상 법령").lawCode("test-law").build();
                    em.persist(law);
                    LawChunk chunk = LawChunk.builder().law(law).articleNumber("1").chunkIndex(0)
                            .contentText("가상 조문 내용😀").build();
                    em.persist(chunk);
                    em.persist(LawEmbedding.builder().lawChunk(chunk).embeddingVector(new byte[]{1, 2, 3})
                            .modelName("synthetic-test").build());
                    var source = new LegalSource("LAW", "123", "test-law", "20260101",
                            "https://www.law.go.kr/", "a".repeat(64), "원문😀".repeat(25000));
                    em.persist(source);
                    em.getTransaction().commit();
                    em.clear();
                    assertThat(user.getId()).isPositive();
                    assertThat(em.find(LegalSource.class, source.getId()).getRawJson()).isEqualTo(source.getRawJson());
                    // DATETIME(6) rounds sub-microsecond precision; it must retain the same instant.
                    assertThat(java.time.Duration.between(source.getCollectedAt(),
                            em.find(LegalSource.class, source.getId()).getCollectedAt()).abs())
                            .isLessThanOrEqualTo(java.time.Duration.ofNanos(1000));
                    var saved = em.createQuery("select m from Message m", Message.class).getSingleResult();
                    assertThat(EncryptUtil.decrypt(saved.getContent())).isEqualTo("암호화 저장 검사😀".repeat(1000));
                }
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                assertThatThrownBy(() -> jdbc.update("INSERT INTO `user` (email, provider) VALUES (?, ?)",
                        "mysql-test@example.com", "local")).isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update("INSERT INTO message (chat_room_id) VALUES (?)", Long.MAX_VALUE))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update("INSERT INTO law_embedding (law_chunk_id, embedding_vector) "
                        + "SELECT law_chunk_id, embedding_vector FROM law_embedding"))
                        .isInstanceOf(DataIntegrityViolationException.class);
            });
            runner(db).run(context -> {
                assertThat(context).hasNotFailed();
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                        Integer.class)).isEqualTo(2);
                assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
            });
        }
    }

    @Test
    void unmanagedNonemptyDatabaseIsRejectedWithoutAutomaticBaseline() throws Exception {
        try (var db = database()) {
            db.start();
            try (var connection = java.sql.DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE unmanaged_data (id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO unmanaged_data VALUES (1)");
            }
            var flyway = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).load();
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("non-empty");
            try (var connection = java.sql.DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM unmanaged_data")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {User.class, ChatRoom.class, Law.class})
    static class Entities { }
}
