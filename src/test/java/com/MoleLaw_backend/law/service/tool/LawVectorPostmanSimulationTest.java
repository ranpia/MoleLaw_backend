package com.MoleLaw_backend.law.service.tool;
import com.MoleLaw_backend.law.client.vector.LawVectorClient;

import com.MoleLaw_backend.law.config.LawVectorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in: actual HTTP against the local Postman embedding simulation, no live model. */
@EnabledIfEnvironmentVariable(named = "LAW_VECTOR_SIMULATION_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class LawVectorPostmanSimulationTest {
    @Test
    void realClientUsesSimulatedEmbeddingContract() {
        LawVectorProperties properties = new LawVectorProperties();
        properties.setEmbeddingBaseUrl(System.getenv("LAW_VECTOR_SIMULATION_URL"));
        properties.setDimensions(2);
        LawVectorClient client = new LawVectorClient(properties, new ObjectMapper());
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        assertEquals(List.of(List.of(0.6, 0.8)), client.embed(List.of("민법\n제1조 법원"),
                calls::incrementAndGet, completed::incrementAndGet));
        assertEquals(2, calls.get());
        assertEquals(2, completed.get());
    }
}
