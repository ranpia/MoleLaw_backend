package com.MoleLaw_backend.service.law.tool;

import com.MoleLaw_backend.domain.entity.LegalSource;
import com.MoleLaw_backend.dto.request.LawSearchFilters;
import com.MoleLaw_backend.domain.repository.LawChunkRepository;
import com.MoleLaw_backend.domain.repository.LawRepository;
import com.MoleLaw_backend.domain.repository.LegalSourceRepository;
import com.MoleLaw_backend.service.law.LawSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.MoleLaw_backend.service.law.tool.LegalToolContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in network smoke test. Only sanitized public metadata is printed. */
@Tag("openlaw-live")
class OpenLawLiveTest {
    @Test
    void realLawAndCaseListsAndDetailsPassToolContracts() throws Exception {
        String key = apiKey();
        assertFalse(key.isBlank(), "Set OC_KEY in the environment or repository .env");
        var mapper = new ObjectMapper();
        var builder = WebClient.builder();
        var laws = new LawSearchService(builder, mock(LawRepository.class),
                mock(LawChunkRepository.class), mapper);
        ReflectionTestUtils.setField(laws, "oc", key);
        var cases = new OpenLawCaseClient(builder, mapper, key);
        // Real transport and Tool validation; snapshots retained only in test memory.
        var repository = mock(LegalSourceRepository.class);
        Map<String, LegalSource> saved = new HashMap<>();
        when(repository.save(any(LegalSource.class))).thenAnswer(call -> {
            LegalSource source = call.getArgument(0);
            saved.put(source.getId(), source);
            return source;
        });
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        var sources = new LegalSourceService(repository, mapper);
        var tools = new LegalApiToolService(laws, cases, sources, mapper);
        var clock = Clock.systemUTC();
        var context = new LegalToolContext(LegalToolContext.Purpose.PRECOLLECTION,
                "openlaw-live", clock.instant().plusSeconds(90), 6, clock);
        try {
            var lawPage = tools.searchLaws(context, new SearchRequest("민법", 1, "1270000", 1, 100,
                    new LawSearchFilters(null, "A0002", 3, true)));
            assertEquals(Status.AVAILABLE, lawPage.status(), "Law search must return candidates");
            assertTrue(lawPage.candidates().stream().allMatch(candidate -> "민법".equals(candidate.title())),
                    "Only exact Civil Act titles may be collected");
            String civilActId = lawPage.candidates().get(0).lawId();
            var identified = tools.searchLaws(context, new SearchRequest("민법", 1, "1270000", 1, 5,
                    new LawSearchFilters(civilActId, "A0002", 3, true)));
            assertEquals(Status.AVAILABLE, identified.status(), "LID lookup must find Civil Act");
            var law = tools.getLawDetail(context, identified.candidates().get(0).id());
            assertTrue(law.contentAvailable(), "Law articles must be present");
            assertFalse(sources.restore(law.referenceId()).getRawJson().isBlank());
            System.out.printf("LAW: total=%d, returned=%d, title=%s, MST=%s, lawId=%s, effectiveDate=%s, articles=%d%n",
                    lawPage.totalCount(), lawPage.candidates().size(), law.title(), law.externalId(),
                    law.lawId(), law.effectiveDate(), law.articleCount());

            var casePage = tools.searchCases(context, new SearchRequest("손해배상", 1, null, 1, 5));
            assertEquals(Status.AVAILABLE, casePage.status(), "Case search must return candidates");
            var precedent = tools.getCaseDetail(context, casePage.candidates().get(0).id());
            assertTrue(precedent.contentAvailable(), "Case text must be present");
            var original = mapper.readTree(sources.restore(precedent.referenceId()).getRawJson());
            System.out.printf("CASE: total=%d, returned=%d, title=%s, ID=%s, caseNumber=%s, contentChars=%d%n",
                    casePage.totalCount(), casePage.candidates().size(), precedent.title(), precedent.externalId(),
                    casePage.candidates().get(0).caseNumber(), original.path("판례내용").asText().length());
            assertEquals(5, context.callsUsed());
            assertEquals(2, saved.size());
        } catch (RuntimeException failure) {
            // Do not propagate WebClient causes: they may contain OC in request URLs.
            String reason = failure instanceof LegalToolException tool ? tool.getReason().name()
                    : failure.getClass().getSimpleName();
            fail("OpenLaw live verification failed: " + reason + ", calls=" + context.callsUsed());
        }
    }

    private String apiKey() throws Exception {
        String value = System.getenv("OC_KEY");
        if (value != null && !value.isBlank()) return value.strip();
        Path dotenv = Path.of(".env");
        if (!Files.exists(dotenv)) return "";
        for (String line : Files.readAllLines(dotenv, StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (entry.startsWith("export ")) entry = entry.substring(7).strip();
            if (!entry.matches("OC_KEY\\s*=.*")) continue;
            value = entry.substring(entry.indexOf('=') + 1).strip();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return "";
    }
}
