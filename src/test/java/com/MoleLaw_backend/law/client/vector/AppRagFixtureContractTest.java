package com.MoleLaw_backend.law.client.vector;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.MoleLaw_backend.law.util.LawArticleChunker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("rag-preparation")
class AppRagFixtureContractTest {
    @Test void preparedSourceBodiesAndFingerprintMatchApplicationContract() throws Exception {
        var mapper=new ObjectMapper();
        var manifest=mapper.readTree(Path.of("build/rag-dataset/legal-perf-v1/app-rag-v2/manifest.json").toFile());
        var contract=mapper.readTree(Path.of("build/rag-dataset/legal-perf-v1/app-rag-v2/contract.json").toFile());
        var properties=new LawVectorProperties();
        properties.setCollectionName("molelaw_service_loadtest_rag_app_v1");
        properties.setChunkSize(512); properties.setChunkOverlap(64);
        var client=new LawVectorClient(properties,mapper);
        assertThat(client.fingerprint()).isEqualTo(manifest.path("fingerprint").asText());
        com.fasterxml.jackson.databind.JsonNode identity=mapper.valueToTree(client.identity());
        assertThat(identity).isEqualTo(manifest.path("identity"));
        int count=0;
        for(var source:manifest.path("sources")) {
            String raw=source.path("rawJson").asText();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(source.path("contentHash").asText());
            // Use the same exact field name and chunker as EvidenceReadToolService.
            String title=mapper.readTree(raw).path("기본정보").path("법령명_한글").asText();
            var chunks=new LawArticleChunker(mapper).chunk(raw,title,512,64);
            assertThat(chunks).hasSize(source.path("chunkCount").asInt());
            for(var chunk:chunks) {
                var expectedHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((chunk.title()+"\n"+chunk.text()).getBytes(StandardCharsets.UTF_8)));
                boolean matches=false;
                for(var point:contract) {
                    var payload=point.path("payload");
                    if(payload.path("reference_id").asText().equals(source.path("id").asText())
                            && payload.path("unit_id").asText().equals(source.path("externalId").asText()+":"+chunk.unitId())
                            && payload.path("title").asText().equals(chunk.title())
                            && payload.path("text").asText().equals(chunk.text())
                            && payload.path("article_text").asText().equals(chunk.articleText())
                            && point.path("embeddingInputSha256").asText().equals(expectedHash)) matches=true;
                }
                assertThat(matches).as("actual chunker payload and embedding input").isTrue();
            }
            assertThat(source.path("lawId").asText()).matches("[0-9]{6}");
            count+=chunks.size();
        }
        assertThat(count).isEqualTo(29).isEqualTo(manifest.path("pointCount").asInt());
        assertThat(contract.size()).isEqualTo(count);
    }
}
