package com.MoleLaw_backend.law.service.tool;
import com.MoleLaw_backend.law.service.source.LegalSourceIndexService;
import com.MoleLaw_backend.law.service.source.LegalSourceService;

import com.MoleLaw_backend.law.entity.LegalSourceIndex;
import com.MoleLaw_backend.law.repository.LegalSourceIndexRepository;
import com.MoleLaw_backend.law.repository.LegalSourceRepository;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Replaces index persistence only; live model/Qdrant tests still exercise commit visibility. */
final class InMemoryIndexes {
    static LegalSourceIndexService create(LegalSourceService sources) {
        var records = new LinkedHashMap<String, LegalSourceIndex>();
        var repository = mock(LegalSourceIndexRepository.class);
        var sourceRepository = mock(LegalSourceRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            LegalSourceIndex record = call.getArgument(0); records.put(record.getGeneration(), record); return record;
        });
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        when(repository.findByConfigFingerprintAndStatusOrderByCompletedAtDescGenerationDesc(anyString(), anyString()))
                .thenAnswer(call -> records.values().stream()
                        .filter(r -> r.getConfigFingerprint().equals(call.getArgument(0)) && r.getStatus().equals(call.getArgument(1)))
                        .sorted(Comparator.comparing(LegalSourceIndex::getCompletedAt).reversed()).toList());
        when(sourceRepository.findById(anyString())).thenAnswer(call -> Optional.of(sources.restore(call.getArgument(0))));
        return new LegalSourceIndexService(repository, sourceRepository);
    }
}
