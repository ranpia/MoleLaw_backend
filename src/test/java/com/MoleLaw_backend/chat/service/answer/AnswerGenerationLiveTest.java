package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.config.AnswerChatClientConfig;
import com.MoleLaw_backend.chat.service.evidence.CitationValidator;
import com.MoleLaw_backend.common.ai.AiPrompts;
import com.MoleLaw_backend.common.ai.config.ReasoningOptions;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit paid writer-only test; no DB, collection, tools, retries or app network changes. */
@Tag("answer-generation-live")
class AnswerGenerationLiveTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final Path root=Path.of("build/answer-generation", "live-"+Instant.now().toString().replace(":","-"));
    private final List<Map<String,Object>> results=new ArrayList<>();
    private int attempts;
    private double reserved;
    private double estimated;
    private volatile boolean stopped;
    private static final double RESERVATION=.05;
    private final boolean diagnostic="true".equals(System.getenv("ANSWER_LIVE_DIAGNOSTIC"));

    private synchronized void reserve() {
        if(stopped || attempts>=(diagnostic?1:30) || reserved+estimated+RESERVATION>5) throw new IllegalStateException("Live run stopped or request/cost cap reached");
        attempts++; reserved+=RESERVATION;
    }

    private synchronized void settle(int input,int output) {
        double cost=(input*.75+output*4.5)/1_000_000;
        if(cost>RESERVATION || input<1 || output<1) throw new IllegalStateException("Usage outside reservation");
        reserved-=RESERVATION; estimated+=cost;
    }

    @Test void fixedEvidenceWriterSmokeThenSingleAndTwoConcurrentRequests() throws Exception {
        assertEquals("true",System.getenv("RUN_ANSWER_LIVE"),"Explicit paid-run flag required");
        Files.createDirectories(root);
        String key=setting("OPENAI_API_KEY","");
        assertFalse(key.isBlank(),"OPENAI_API_KEY required");
        String modelName="gpt-5.4-mini";
        var clientBuilder=WebClient.builder().filter((request,next) -> Mono.defer(() -> {
            assertEquals("https://api.openai.com/v1/chat/completions",request.url().toString());
            reserve();
            return next.exchange(request);
        }));
        var api=OpenAiApi.builder().apiKey(key).webClientBuilder(clientBuilder).build();
        var options=ReasoningOptions.create(modelName,"medium",4096);
        options.setStreamUsage(true);
        var model=OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build()).build();
        org.springframework.ai.chat.model.ChatModel boundedModel=new org.springframework.ai.chat.model.ChatModel() {
            @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                throw new UnsupportedOperationException("This probe permits streaming only");
            }
            @Override public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt) {
                // A UTF-8 byte bound overestimates text token count; include message framing headroom.
                int bytes=prompt.getInstructions().stream().mapToInt(m -> m.getText().getBytes(StandardCharsets.UTF_8).length+1024).sum();
                if(bytes>40000) throw new IllegalStateException("Full prompt exceeds cost reservation bound");
                return model.stream(prompt);
            }
        };
        var prompts=new AiPrompts(new ClassPathResource("prompts/legal-system.txt"),new ClassPathResource("prompts/keyword-extraction.txt"));
        var writer=new AnswerChatClientConfig().legalAnswerChatClient(ChatClient.builder(boundedModel),modelName,"medium",4096,prompts);
        var fixture=mapper.readTree(Path.of("build/rag-dataset/legal-perf-v1/rerank-fixtures-v1.json").toFile());
        var cases=new ArrayList<Case>();
        for(JsonNode row:fixture.path("cases")) {
            if(row.path("candidate_count").asInt()!=20 || !List.of("q01","q11","q17").contains(row.path("query_id").asText())) continue;
            for(int count:List.of(3,8)) {
                var sources=new ArrayList<EvidenceItem>();
                for(int i=0;i<count;i++) {
                    var doc=row.path("payload").path("documents").get(i);
                    String id=UUID.nameUUIDFromBytes(doc.path("id").asText().getBytes(StandardCharsets.UTF_8)).toString();
                    sources.add(new EvidenceItem(id,"LAW_CHUNK",doc.path("id").asText(),"고정 법령 후보", "https://www.law.go.kr",doc.path("text").asText()));
                }
                String question="고정 스냅샷의 시행일 범위에서만 설명하고 최신 법령은 확인하지 않았다고 명시해 주세요. "+row.path("payload").path("query").asText();
                String prompt=new EvidencePromptBuilder().build(question,sources);
                assertTrue((prompt+prompts.getLegalSystem()).getBytes(StandardCharsets.UTF_8).length<28000,"Input byte reservation bound");
                cases.add(new Case(row.path("query_id").asText()+"-sources"+count,prompt,List.copyOf(sources)));
            }
        }
        assertEquals(6,cases.size());
        try {
            if(diagnostic) {
                request(writer,cases.stream().filter(c -> c.id().equals("q01-sources8")).findFirst().orElseThrow(),"diagnostic",1);
            } else {
                for(int i=0;i<3;i++) request(writer,cases.get(i),"smoke",1);
                for(var item:cases) request(writer,item,"single",1);
                var pool=Executors.newFixedThreadPool(2);
                try {
                    var jobs=new ArrayList<java.util.concurrent.Future<?>>();
                    for(var item:cases) jobs.add(pool.submit(() -> { request(writer,item,"concurrent2",2); return null; }));
                    for(var job:jobs) job.get();
                } finally {
                    pool.shutdownNow();
                    if(!pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Concurrent probe workers did not finish; retain outstanding reservations");
                }
            }
        } finally {
            synchronized(this) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("result.json").toFile(),Map.of(
                        "model",modelName,"effort","medium","maxCompletionTokens",4096,"attempts",attempts,
                        "estimatedUsdUncached",estimated,"unsettledReservationUsd",reserved,"results",results,"completed",!stopped,
                        "note","Writer only. Small fixed-count sample. Source metadata is not expert gold. No backend/RAG load."));
            }
        }
        assertEquals(diagnostic?1:15,attempts);
        System.out.println("ANSWER_LIVE_RESULT "+root+" calls="+attempts+" estimatedUsd="+estimated);
    }

    private void request(ChatClient writer,Case input,String stage,int concurrency) throws Exception {
        long start=System.nanoTime(); var first=new AtomicLong();
        var text=new StringBuilder();
        var usage=new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.metadata.Usage>();
        var finish=new java.util.concurrent.atomic.AtomicReference<String>();
        String outcome="failed";
        String failureReason=null;
        try {
            writer.prompt().user(input.prompt()).options(options()).stream().chatResponse().doOnNext(response -> {
                if(response.getMetadata().getUsage().getPromptTokens()!=null && response.getMetadata().getUsage().getPromptTokens()>0) usage.set(response.getMetadata().getUsage());
                if(response.getResult()!=null) {
                    String delta=response.getResult().getOutput().getText();
                    if(delta!=null && !delta.isEmpty()) { first.compareAndSet(0,System.nanoTime()); text.append(delta); }
                    String reason=response.getResult().getMetadata().getFinishReason();
                    if(reason!=null && !reason.isBlank()) finish.set(reason);
                }
            }).then().block(Duration.ofSeconds(180));
            assertNotNull(usage.get(),"Final usage missing; stop before further paid calls");
            settle(usage.get().getPromptTokens(),usage.get().getCompletionTokens());
            assertEquals("STOP",finish.get()==null?null:finish.get().toUpperCase(),"Truncated/refused generation");
            assertFalse(text.toString().isBlank());
            new CitationValidator().validate(text.toString(),input.sources());
            outcome="success";
        } catch(Exception | AssertionError failure) {
            stopped=true;
            failureReason=failure instanceof com.MoleLaw_backend.chat.exception.CitationValidationException citation
                    ? citation.reason().name() : failure.getClass().getSimpleName();
            throw failure;
        } finally {
            var record=new LinkedHashMap<String,Object>();
            record.put("fixture",input.id()); record.put("stage",stage); record.put("concurrency",concurrency); record.put("outcome",outcome);
            record.put("firstTextSeconds",first.get()==0?null:(first.get()-start)/1e9); record.put("completedSeconds",(System.nanoTime()-start)/1e9);
            record.put("finishReason",finish.get()); record.put("visibleCharacters",text.length());
            record.put("failureReason",failureReason);
            record.put("promptSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.prompt().getBytes(StandardCharsets.UTF_8))));
            if(usage.get()!=null) record.put("usage",mapper.valueToTree(usage.get().getNativeUsage()));
            synchronized(this) {
                results.add(record);
                Files.writeString(root.resolve(input.id()+"-"+stage+"-answer.txt"),text.toString(),StandardCharsets.UTF_8);
                mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("progress.json").toFile(),Map.of(
                        "attempts",attempts,"estimatedUsdUncached",estimated,"unsettledReservationUsd",reserved,"results",results));
            }
        }
    }

    private org.springframework.ai.openai.OpenAiChatOptions options() {
        var options=ReasoningOptions.create("gpt-5.4-mini","medium",4096); options.setStreamUsage(true); return options;
    }

    private String setting(String name,String fallback) throws Exception {
        String value=System.getenv(name); if(value!=null && !value.isBlank()) return value;
        if(!Files.exists(Path.of(".env"))) return fallback;
        for(String line:Files.readAllLines(Path.of(".env"))) {
            String entry=line.strip(); if(entry.startsWith("export ")) entry=entry.substring(7).strip();
            if(!entry.matches(java.util.regex.Pattern.quote(name)+"\\s*=.*")) continue;
            value=entry.substring(entry.indexOf('=')+1).strip();
            if(value.length()>1 && ((value.startsWith("\"")&&value.endsWith("\"")) || (value.startsWith("'")&&value.endsWith("'")))) value=value.substring(1,value.length()-1);
            return value;
        }
        return fallback;
    }
    private record Case(String id,String prompt,List<EvidenceItem> sources) { }
}
