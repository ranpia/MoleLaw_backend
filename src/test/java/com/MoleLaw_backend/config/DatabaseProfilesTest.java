package com.MoleLaw_backend.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DatabaseProfilesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(MySqlConfig.class, QdrantConfig.class)
            .withPropertyValues("MYSQL_PASSWORD=test-only", "QDRANT_API_KEY=test-only")
            .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class));

    @Test
    void mysqlProfileProvidesPrimaryDataSourceWithoutQdrant() {
        runner.withPropertyValues("spring.profiles.active=mysql").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataSource.class);
            assertThat(context).doesNotHaveBean(QdrantVectorStore.class);
            assertThat(context.getBean(DataSource.class)).isSameAs(context.getBean("mysqlDataSource"));
            assertThat(context.getBeanFactory().getBeanDefinition("dataSource").isPrimary()).isTrue();
            assertThat(context.getBean(HikariDataSource.class).getJdbcUrl()).contains("127.0.0.1:3307/molelawdb");
        });
    }

    @Test
    void localProfileGroupsBothStoresWithoutCallingEmbeddingOrCreatingCollection() {
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataSource.class).hasSingleBean(QdrantVectorStore.class);
            assertThat(context.getEnvironment().getActiveProfiles()).contains("mysql", "qdrant", "local");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
            verifyNoInteractions(context.getBean(EmbeddingModel.class));
        });
    }

    @Test
    void testProfileDisablesBothExternalStoreConfigurationsEvenWhenSelected() {
        runner.withPropertyValues("spring.profiles.active=test,mysql,qdrant").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class).doesNotHaveBean(QdrantVectorStore.class);
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:");
        });
    }
}
