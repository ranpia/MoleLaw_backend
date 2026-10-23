package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.dto.vector.LawIndexResult;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.service.source.LegalSourceIndexService;
import com.MoleLaw_backend.law.service.source.LegalSourceService;
import com.MoleLaw_backend.law.service.etl.LawSourceDocumentReader;
import com.MoleLaw_backend.law.service.etl.LawArticleDocumentTransformer;
import com.MoleLaw_backend.law.service.etl.LawQdrantDocumentWriter;
import com.MoleLaw_backend.law.client.vector.LawVectorClient;
import com.MoleLaw_backend.law.exception.LegalToolException;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.MoleLaw_backend.law.entity.LegalSource;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.MoleLaw_backend.law.exception.LegalToolException.Reason.*;

/** Explicit server tool. Its context and source references must come from an authorized execution. */
public class LawVectorizationToolService {
    private final LawVectorClient client;
    private final LawVectorProperties properties;
    private final ObjectMapper mapper;
    private final LegalSourceService sources;
    private final LegalSourceIndexService indexes;

    public LawVectorizationToolService(LawVectorClient client, LawVectorProperties properties,
                                      ObjectMapper mapper, LegalSourceService sources, LegalSourceIndexService indexes) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
        this.sources = sources;
        this.indexes = indexes;
    }

    public LawIndexResult vectorizeLaw(LegalToolContext context, String sourceReferenceId) {
        synchronized (context) {
            context.authorize();
            context.checkDeadline();
            var detail = context.details.values().stream()
                    .filter(d -> d.referenceId().equals(sourceReferenceId)).findFirst()
                    .orElseThrow(() -> new LegalToolException(UNKNOWN_ID, false));
            LegalSource source = sources.restore(sourceReferenceId);
            if (!"LAW".equals(source.getSourceType()) || !detail.externalId().equals(source.getExternalId())) {
                throw new LegalToolException(UNKNOWN_ID, false);
            }
            return index(context, source, detail.title());
        }
    }

    /** Reuse only a published snapshot of the same version and embedding configuration. */
    public java.util.Optional<String> publishedSource(LegalToolContracts.Candidate candidate) {
        return indexes.searchable(client.fingerprint()).values().stream()
                .map(record -> sources.restore(record.getSourceId()))
                .filter(source -> candidate.id().equals(source.getExternalId())
                        && java.util.Objects.equals(candidate.lawId(), source.getLawId())
                        && java.util.Objects.equals(candidate.effectiveDate(), source.getEffectiveDate()))
                .map(LegalSource::getId).findFirst();
    }

    /** Explicit server precollection only: rebuild saved sources without another OpenLaw call. */
    public LawIndexResult reindexStoredLaw(LegalToolContext context, String referenceId) {
        synchronized (context) {
            context.authorize();
            context.checkDeadline();
            LegalSource source = sources.restore(referenceId);
            if (!"LAW".equals(source.getSourceType())) throw new LegalToolException(UNKNOWN_ID, false);
            try {
                String title = mapper.readTree(source.getRawJson()).path("기본정보").path("법령명_한글").asText();
                if (title.isBlank()) throw new LegalToolException(INVALID_RESPONSE, false);
                return index(context, source, title);
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                throw new LegalToolException(INVALID_RESPONSE, false);
            }
        }
    }

    private LawIndexResult index(LegalToolContext context, LegalSource source, String title) {
        context.configureVectorCallLimits(properties.getEmbeddingMaxCalls(), properties.getIndexingMaxCalls());
        var documents = new LawArticleDocumentTransformer(mapper, properties.getChunkSize(), properties.getChunkOverlap())
                .transform(new LawSourceDocumentReader(source, title).read());
        var cached = indexes.reusable(source, client.fingerprint());
        if (cached.isPresent()) {
            var record = cached.get();
            if (client.collectionAvailable(context::reserveIndex, context::checkDeadline)
                    && record.getChunkCount() == documents.size()
                    && writer(context, sources.restore(record.getSourceId()), record).verify(documents)) {
                return new LawIndexResult(record.getSourceId(), properties.getCollectionName(),
                        (int) documents.stream().map(d -> d.getMetadata().get("article_id")).distinct().count(), record.getChunkCount());
            }
            // A confirmed missing/mismatched index is rebuilt once as a fresh generation.
            // Transport/permission failures throw above; they never trigger expensive reembedding.
            context.checkDeadline();
            indexes.fail(record.getGeneration());
        }
        var attempt = indexes.begin(source, client);
        try {
            writer(context, source, attempt).write(documents);
            context.checkDeadline();
            indexes.complete(attempt.getGeneration(), documents.size());
            return new LawIndexResult(source.getId(), properties.getCollectionName(),
                    (int) documents.stream().map(d -> d.getMetadata().get("article_id")).distinct().count(), documents.size());
        } catch (RuntimeException failure) {
            try { indexes.fail(attempt.getGeneration()); }
            catch (RuntimeException bookkeeping) { failure.addSuppressed(bookkeeping); }
            throw failure;
        }
    }

    private LawQdrantDocumentWriter writer(LegalToolContext context, LegalSource source,
            com.MoleLaw_backend.law.entity.LegalSourceIndex attempt) {
        return new LawQdrantDocumentWriter(client, properties, source, attempt,
                context::reserveIndex, context::checkDeadline,
                () -> context.embeddingTime(java.time.Duration.ofSeconds(properties.getEmbeddingTimeoutSeconds())),
                context::reserveEmbedding);
    }
}
