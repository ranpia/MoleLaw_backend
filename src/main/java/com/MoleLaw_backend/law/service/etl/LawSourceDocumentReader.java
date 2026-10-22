package com.MoleLaw_backend.law.service.etl;
import com.MoleLaw_backend.law.entity.LegalSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import java.util.List;
import java.util.Map;

/** Reads an immutable MySQL source restored by the authorized tool; no external request. */
public record LawSourceDocumentReader(LegalSource source, String title) implements DocumentReader {
    @Override
    public List<Document> get() {
        if (!"LAW".equals(source.getSourceType())) throw new IllegalArgumentException("Expected law source");
        return List.of(new Document(source.getId(), source.getRawJson(), Map.of("title", title)));
    }
}
