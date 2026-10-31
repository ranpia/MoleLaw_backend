package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.client.vector.LawRerankerClient;
import com.MoleLaw_backend.law.config.*;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.*;
import com.MoleLaw_backend.law.dto.vector.LawSearchEvidence;
import com.MoleLaw_backend.law.service.tool.*;
import com.MoleLaw_backend.law.service.retrieval.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Opt-in live experiment. Production orchestration/planners; read-only frozen-corpus adapter. */
public class AdaptiveRetrievalComparison {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String COLLECTION = "molelaw_legal_chunks_kure_v1_c500_431ce0e1_8b418a58";
    static final String REVISION = "8b418a58414668e75532ed045c22d9ca018ae2b2";
    static Path output;
    static Map<String, Article> articles = new LinkedHashMap<>();
    static Map<String, String> canonicalByEvaluation = new HashMap<>();
    static Map<String, String> evaluationByCanonical = new HashMap<>();
    static Map<String, String> sourceNames = new HashMap<>();
    static Map<String, String> sourceMsts = new HashMap<>();
    static List<String> titles;
    static List<ObjectNode> searches;
    static long started;
    static LawRerankerClient reranker;
    record Article(String title, String body, String lawId, String url) {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); output = Path.of(args[1]);
        int limit = Integer.parseInt(args[2]);
        Files.createDirectories(output);
        loadArticles(root.resolve("experiments/query-strategies/chunk-sizes-v1/data/char500/corpus.jsonl"));
        var names = new HashMap<String, String>();
        for (var source : JSON.readTree(root.resolve("experiments/model-comparison/data/corpus-manifest.json").toFile()).path("sources"))
            names.put(source.path("id").asText(), source.path("name").asText());
        for (var source : JSON.readTree(root.resolve("experiments/retrieval-validation/expanded-v1/review/sources.json").toFile()).path("laws"))
            names.put(source.path("id").asText(), source.path("name").asText());
        var notice = JSON.readTree(root.resolve("experiments/model-comparison/review/special-sources.json").toFile()).path("notice");
        names.put(notice.path("id").asText(), notice.path("name").asText());
        sourceNames = names;
        titles = articles.values().stream().map(article -> {
            String name = names.get(article.lawId());
            if (name == null || name.isBlank()) throw new IllegalStateException("Missing frozen source title");
            return name;
        }).distinct().sorted().toList();
        if (articles.size() != canonicalByEvaluation.size() || articles.size() != evaluationByCanonical.size())
            throw new IllegalStateException("Article mapping must be bijective");
        String civilMst = sourceMsts.get("civil");
        if (!evaluationByCanonical.containsKey(civilMst + ":580")
                || !com.MoleLaw_backend.law.util.LawArticleQuery.paths("민법 제580조", sourceNames.get("civil")).equals(List.of("580")))
            throw new IllegalStateException("Named article adapter contract failed");
        System.out.printf("ADAPTER_CONTRACT_PASS units=%d sources=%d canonicalIds=bijective namedArticle=verified%n", articles.size(), titles.size());
        var version = request("http://localhost:11437/api/version", null, false);
        if (!REVISION.equals(version.path("revision").asText())) throw new IllegalStateException("Embedding revision mismatch");
        var props = new LawRerankerProperties(); props.setEnabled(true); props.setReadTimeoutMillis(120000);
        reranker = new LawRerankerClient(props, JSON);
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(5000); factory.setReadTimeout(120000);
        var api = OpenAiApi.builder().apiKey(required("OPENAI_API_KEY"))
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("gpt-5.4-mini").build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build()).build();
        var config = new EvidenceChatClientConfig();
        var questionClient = config.questionPlanningChatClient(ChatClient.builder(model), "gpt-5.4-mini", "low", 4096,
                new ClassPathResource("prompts/question-retrieval-plan.txt"));
        var decisionClient = config.evidenceCollectionChatClient(ChatClient.builder(model), "gpt-5.4", "medium", 12288,
                new ClassPathResource("prompts/evidence-decision.txt"));
        var readOptions = config.evidenceReadOptions("gpt-5.4-mini", "low", 4096);
        var readClient = config.evidenceReadChatClient(ChatClient.builder(model), readOptions,
                new ClassPathResource("prompts/evidence-reading.txt"));
        var rawQuestions = Files.readAllLines(root.resolve("experiments/reranking/dense50-v1/data/c500-legal_transformed/candidates.jsonl"), StandardCharsets.UTF_8);
        int count = 0;
        for (String line : rawQuestions) {
            if (count++ >= limit) break;
            var q = JSON.readTree(line); String id = q.path("queryId").asText();
            Path target = output.resolve(id + ".json");
            if (Files.exists(target)) throw new IllegalStateException("Refusing to overwrite question result");
            started = System.nanoTime(); searches = new ArrayList<>();
            var trace = JSON.createObjectNode().put("queryId", id).put("question", q.path("originalText").asText());
            trace.set("goldUnitIds", q.path("goldUnitIds"));
            var rounds = trace.putArray("rounds");
            var rag = mock(LawRagSearchToolService.class);
            when(rag.indexedLawTitles()).thenReturn(titles);
            when(rag.searchTop15(any(), anyString())).thenAnswer(inv -> search(inv.getArgument(0), inv.getArgument(1)));
            var reader = mock(EvidenceReadToolService.class);
            // Replace SQL source restoration only: replay verified full article from immutable experiment corpus.
            when(reader.readEvidence(anyList(), anyList())).thenAnswer(inv -> {
                List<EvidenceItem> pool = inv.getArgument(0); List<String> ids = inv.getArgument(1);
                if (ids.size() > 8 || new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException();
                return ids.stream().map(key -> {
                    var item = pool.stream().filter(e -> e.id().equals(key)).findFirst().orElseThrow();
                    var article = articles.get(evaluationByCanonical.get(item.sourceId()));
                    if (article == null || !article.body().equals(item.content()) || !article.title().equals(item.title()))
                        throw new IllegalArgumentException("Frozen article identity mismatch");
                    return item;
                }).toList();
            });
            var readPlanner = new EvidenceReadPlanner(readClient, JSON, reader, 8000, 12000, readOptions);
            var decision = spy(new EvidenceDecisionPlanner(decisionClient, JSON, 8000, readPlanner, 32000, provider(rag)));
            doAnswer(inv -> {
                List<EvidenceItem> pool = inv.getArgument(1);
                var round = rounds.addObject().put("remainingActions", (int)inv.getArgument(4)).put("elapsedMs", elapsed());
                round.set("pool", JSON.valueToTree(pool));
                if (!trace.has("initialPool")) {
                    trace.set("initialPool", JSON.valueToTree(pool)); trace.put("initialElapsedMs", elapsed());
                }
                trace.set("lastPool", JSON.valueToTree(pool));
                var result = (EvidenceDecision)inv.callRealMethod();
                round.set("decision", JSON.valueToTree(result));
                return result;
            }).when(decision).decide(anyString(), anyList(), any(), anyList(), anyInt());
            doAnswer(inv -> {
                var result = (EvidenceDecision)inv.callRealMethod();
                ((ObjectNode)rounds.get(rounds.size() - 1)).set("reconsideredDecision", JSON.valueToTree(result));
                return result;
            }).when(decision).reconsiderDiscovery(anyString(), anyList(), any(), anyList(), anyInt());
            var questions = spy(new QuestionRetrievalPlanner(questionClient, JSON));
            doAnswer(inv -> { var result = (QuestionRetrievalPlan)inv.callRealMethod(); trace.set("plan", JSON.valueToTree(result));
                trace.set("initialQueries", JSON.valueToTree(result.initialSearchQueries())); return result; })
                    .when(questions).plan(anyString());
            var collector = new LegalEvidenceCollector(provider(rag), provider(mock(LawVectorizationToolService.class)),
                    mock(LegalApiToolService.class), decision, questions, mock(LawCandidateSelector.class),
                    mock(AiSearchEvidenceCollector.class), RetrievalBackend.QDRANT, new LawCandidateSelectionProperties(), Duration.ofMinutes(15));
            ReflectionTestUtils.setField(collector, "fixedIndexOnly", true);
            try {
                var result = collector.collectForAnswer(q.path("originalText").asText());
                trace.put("status", "SUCCESS"); trace.set("selectedEvidence", JSON.valueToTree(result.evidence()));
            } catch (RuntimeException failure) {
                trace.put("status", "FAILED"); trace.put("errorType", failure.getClass().getSimpleName());
                if (failure instanceof MolelawException known) trace.put("errorCode", known.getErrorCode().name());
                // Never persist exception messages/causes, request headers, credentials or provider response errors.
            }
            trace.set("searches", JSON.valueToTree(searches)); trace.put("elapsedMs", elapsed());
            trace.put("completedAtUtc", Instant.now().toString());
            normalizeEvaluationIds(trace);
            JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), trace);
            System.out.printf("ADAPTIVE_RESULT id=%s status=%s rounds=%d searches=%d elapsedMs=%d%n", id, trace.path("status").asText(), rounds.size(), searches.size(), elapsed());
        }
    }

    static List<LawSearchEvidence> search(LegalToolContext context, String query) throws Exception {
        long begin = elapsed(); context.checkDeadline(); context.reserve();
        var exactUnits = new ArrayList<String>();
        for (var source : sourceMsts.entrySet()) {
            for (String path : com.MoleLaw_backend.law.util.LawArticleQuery.paths(query, sourceNames.get(source.getKey())))
                if (exactUnits.size() < 15) exactUnits.add(source.getValue() + ":" + path);
        }
        var embedding = request("http://localhost:11437/api/embed", Map.of("model", "kure-v1:official", "input", List.of(query), "truncate", false,
                "options", Map.of("num_thread", 6, "num_gpu", 0)), false);
        var vector = embedding.path("embeddings").path(0);
        if (!vector.isArray() || vector.size() != 1024) throw new IllegalStateException("Embedding schema mismatch");
        context.checkDeadline(); context.reserve();
        var queryBody = new LinkedHashMap<String, Object>(Map.of("query", vector, "group_by", "unit_id", "group_size", 1, "limit", 50,
                "with_payload", true, "with_vector", false, "params", Map.of("exact", true)));
        // Production LawVectorClient restricts to explicitly named article units, without a broad fallback.
        var explicitEvaluationIds = exactUnits.stream().map(evaluationByCanonical::get).filter(Objects::nonNull).toList();
        if (!exactUnits.isEmpty()) queryBody.put("filter", Map.of("must", List.of(Map.of("key", "unit_id", "match", Map.of("any", explicitEvaluationIds)))));
        var response = exactUnits.isEmpty() || !explicitEvaluationIds.isEmpty()
                ? request("http://localhost:6333/collections/" + COLLECTION + "/points/query/groups", queryBody, true)
                : JSON.createObjectNode();
        var hits = new ArrayList<LawSearchEvidence>();
        for (var group : response.path("result").path("groups")) {
            var hit = group.path("hits").path(0); var payload = hit.path("payload"); String unit = group.path("id").asText();
            var article = articles.get(unit);
            if (article == null || !article.title().equals(payload.path("title").asText())
                    || !article.body().contains(payload.path("text").asText())) throw new IllegalStateException("Frozen corpus payload mismatch");
            String canonical = canonicalByEvaluation.get(unit);
            String external = canonical.substring(0, canonical.indexOf(':'));
            hits.add(new LawSearchEvidence(canonical, UUID.nameUUIDFromBytes(external.getBytes(StandardCharsets.UTF_8)).toString(),
                    external, article.lawId(), null, article.title(), payload.path("text").asText(), article.body(),
                    article.url(), hit.path("score").asDouble(), REVISION, "frozen-c500-overlap64", "frozen-corpus"));
        }
        if ((exactUnits.isEmpty() && hits.size() != 50) || hits.size() > 50) throw new IllegalStateException("Candidate count mismatch");
        var ranked = reranker.rerank(query, hits, context::reserve, context::checkDeadline);
        var record = JSON.createObjectNode().put("query", query).put("elapsedMs", elapsed() - begin);
        record.put("exactArticleLookup", !exactUnits.isEmpty()); record.set("canonicalExactUnits", JSON.valueToTree(exactUnits));
        record.set("denseCandidates", JSON.valueToTree(hits)); record.set("rerankedTop15", JSON.valueToTree(ranked)); searches.add(record);
        System.out.printf("ADAPTIVE_SEARCH completed=%d elapsedMs=%d%n", searches.size(), elapsed() - begin);
        return ranked;
    }

    static void loadArticles(Path path) throws Exception {
        Map<String, List<JsonNode>> chunks = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            var node = JSON.readTree(line); chunks.computeIfAbsent(node.path("unitId").asText(), ignored -> new ArrayList<>()).add(node);
        }
        var lawTitles = new TreeSet<String>();
        for (var entry : chunks.entrySet()) {
            entry.getValue().sort(Comparator.comparingInt(n -> n.path("startChar").asInt()));
            StringBuilder body = new StringBuilder();
            for (var chunk : entry.getValue()) {
                int start = chunk.path("startChar").asInt(); String text = chunk.path("text").asText();
                if (start > body.length() || start + text.length() != chunk.path("endChar").asInt()) throw new IllegalStateException("Chunk boundaries mismatch");
                int overlap = Math.min(body.length() - start, text.length());
                if (!body.substring(start, start + overlap).equals(text.substring(0, overlap))) throw new IllegalStateException("Chunk overlap mismatch");
                body.append(text.substring(overlap));
            }
            var first = entry.getValue().get(0); String title = first.path("title").asText();
            articles.put(entry.getKey(), new Article(title, body.toString(), first.path("lawId").asText(), first.path("sourceUrl").asText()));
            String[] parts = entry.getKey().split(":", 3);
            String sourceMst = parts.length == 3 ? parts[1] : "fixture-" + first.path("lawId").asText();
            String articlePath = parts.length == 3 ? parts[2] : entry.getKey();
            var articleNumber = java.util.regex.Pattern.compile("(?:^|\\s)제([0-9]+)조(?:의([0-9]+))?").matcher(title);
            if (articleNumber.find()) {
                articlePath = new java.math.BigInteger(articleNumber.group(1)).toString();
                if (articleNumber.group(2) != null) articlePath += "-" + new java.math.BigInteger(articleNumber.group(2));
            }
            String canonical = sourceMst + ":" + articlePath;
            if (evaluationByCanonical.putIfAbsent(canonical, entry.getKey()) != null) throw new IllegalStateException("Canonical article collision");
            canonicalByEvaluation.put(entry.getKey(), canonical);
            if (parts.length == 3) sourceMsts.put(first.path("lawId").asText(), sourceMst);
            else sourceMsts.putIfAbsent(first.path("lawId").asText(), sourceMst);
            lawTitles.add(title.split(" 제", 2)[0]);
        }
        titles = List.copyOf(lawTitles);
    }
    /** Keep immutable qrel IDs in reports while retaining actual service-format IDs alongside them. */
    static void normalizeEvaluationIds(JsonNode node) {
        if (node.isObject()) {
            var object = (ObjectNode)node;
            for (String field : List.of("sourceId", "unitId")) {
                String canonical = object.path(field).asText();
                if (evaluationByCanonical.containsKey(canonical)) {
                    object.put("canonical" + Character.toUpperCase(field.charAt(0)) + field.substring(1), canonical);
                    object.put(field, evaluationByCanonical.get(canonical));
                }
            }
        }
        node.elements().forEachRemaining(AdaptiveRetrievalComparison::normalizeEvaluationIds);
    }
    static JsonNode request(String url, Object body, boolean qdrant) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120));
        if (qdrant) builder.header("api-key", required("QDRANT_API_KEY"));
        if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        var result = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (result.statusCode() != 200) throw new IllegalStateException("Experiment HTTP status " + result.statusCode());
        return JSON.readTree(result.body());
    }
    static long elapsed() { return (System.nanoTime() - started) / 1_000_000; }
    static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name); return value; }
    @SuppressWarnings("unchecked") static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> result = mock(ObjectProvider.class); when(result.getIfAvailable()).thenReturn(value); return result;
    }
}
