package com.MoleLaw_backend.law.service.etl;
import com.MoleLaw_backend.law.util.LawArticleChunker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Preserves article text and emits bounded Unicode chunks without an LLM. */
public final class LawArticleDocumentTransformer implements DocumentTransformer {
    private final LawArticleChunker chunker;
    private final int size;
    private final int overlap;
    public LawArticleDocumentTransformer(ObjectMapper mapper, int size, int overlap) {
        this.chunker = new LawArticleChunker(mapper);
        this.size = size;
        this.overlap = overlap;
    }
    @Override
    public List<Document> apply(List<Document> sources) {
        List<Document> result = new ArrayList<>();
        for (Document source : sources) {
            for (var chunk : chunker.chunk(source.getText(), (String) source.getMetadata().get("title"), size, overlap)) {
                result.add(new Document(source.getId() + ":" + chunk.unitId() + ":" + chunk.chunkIndex(), chunk.text(),
                        Map.of("title", chunk.title(), "article_id", chunk.unitId(),
                                "article_text", chunk.articleText(), "chunk_index", chunk.chunkIndex())));
            }
        }
        return List.copyOf(result);
    }
}
