package com.MoleLaw_backend.law.repository;

import com.MoleLaw_backend.law.entity.LegalSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:legal-source-test;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LegalSourceRepositoryTest {
    @Autowired
    private LegalSourceRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void restoresLargeRawContentAndRetainsVersionsForSameExternalId() {
        String raw = "{\"text\":\"" + "법령 원문 ".repeat(12000) + "\"}";
        LegalSource first = repository.save(new LegalSource("LAW", "123", "law-1", "20250101",
                "https://law.go.kr/법령/123", "a".repeat(64), raw));
        LegalSource second = repository.save(new LegalSource("LAW", "123", "law-1", "20260101",
                "https://law.go.kr/법령/123", "b".repeat(64), "{}"));
        repository.flush();
        entityManager.clear();

        assertThat(repository.findById(first.getId()).orElseThrow().getRawJson()).isEqualTo(raw);
        assertThat(repository.findById(second.getId()).orElseThrow().getEffectiveDate()).isEqualTo("20260101");
        assertThat(repository.count()).isEqualTo(2);
    }
}
