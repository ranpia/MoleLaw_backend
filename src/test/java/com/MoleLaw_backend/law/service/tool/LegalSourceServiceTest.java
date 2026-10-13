package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.entity.LegalSource;
import com.MoleLaw_backend.law.repository.LegalSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalSourceServiceTest {
    private final LegalSourceRepository repository = mock(LegalSourceRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LegalSourceService service = new LegalSourceService(repository, mapper);

    @Test
    void keepsSeparateSnapshotsForChangedContentAndVersions() throws Exception {
        when(repository.save(any(LegalSource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LegalSource first = service.save("LAW", "123", "law-1", "20250101", "https://law.go.kr/법령/123",
                mapper.readTree("{\"text\":\"original\"}"));
        LegalSource revised = service.save("LAW", "123", "law-1", "20260101", "https://law.go.kr/법령/123",
                mapper.readTree("{\"text\":\"revised\"}"));

        assertThat(first.getId()).isNotEqualTo(revised.getId());
        assertThat(first.getContentHash()).hasSize(64).isNotEqualTo(revised.getContentHash());
        assertThat(first.getRawJson()).isEqualTo("{\"text\":\"original\"}");
        assertThat(first.getEffectiveDate()).isEqualTo("20250101");
        assertThat(revised.getEffectiveDate()).isEqualTo("20260101");
        assertThat(first.getCollectedAt()).isNotNull();
    }

    @Test
    void preservesNewVersionEvenWhenRawContentIsIdentical() throws Exception {
        when(repository.save(any(LegalSource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LegalSource first = service.save("LAW", "123", "law-1", "20250101", "https://law.go.kr/법령/123",
                mapper.readTree("{\"text\":\"same\"}"));
        LegalSource next = service.save("LAW", "124", "law-1", "20260101", "https://law.go.kr/법령/124",
                mapper.readTree("{\"text\":\"same\"}"));

        assertThat(first.getId()).isNotEqualTo(next.getId());
        assertThat(first.getContentHash()).isEqualTo(next.getContentHash());
        assertThat(first.getExternalId()).isEqualTo("123");
        assertThat(next.getExternalId()).isEqualTo("124");
    }

    @Test
    void restoresExactSnapshotByReference() {
        LegalSource source = new LegalSource("CASE", "456", null, null,
                "https://law.go.kr/판례/456", "hash", "{}");
        when(repository.findById(source.getId())).thenReturn(Optional.of(source));

        assertThat(service.restore(source.getId())).isSameAs(source);
        verify(repository).findById(source.getId());
    }

    @Test
    void rejectsMissingReference() {
        when(repository.findById("absent")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.restore("absent")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.restore(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
