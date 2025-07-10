package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.domain.entity.Law;
import com.MoleLaw_backend.domain.entity.LawChunk;
import com.MoleLaw_backend.domain.repository.LawChunkRepository;
import com.MoleLaw_backend.domain.repository.LawRepository;
import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.OpenLawApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class LawSearchService {

    private final WebClient.Builder webClientBuilder;

    @Value("${openlaw.api-key}")
    private String oc;

    private static final int MAX_DISPLAY = 20;
    private final LawRepository lawRepository;
    private final LawChunkRepository lawChunkRepository;
    private final ObjectMapper objectMapper;

    public String searchLawByKeyword(String keyword, String orgCode) {
        try {
            // ① 제목 검색 + 소관부처
            String result = trySearch(keyword, 1, orgCode);
            if (isNotEmpty(result)) return result;

            // ② 본문 검색 + 소관부처
            result = trySearch(keyword, 2, orgCode);
            if (isNotEmpty(result)) return result;

            return searchLawByKeyword(keyword);

        } catch (WebClientResponseException | WebClientRequestException e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, e);
        } catch (Exception e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "알 수 없는 예외 발생", e);
        }
    }

    public String searchLawByKeyword(String keyword) {
        try {
            // ③ 제목 검색 (부처 없이)
            String result = trySearch(keyword, 1, null);
            if (isNotEmpty(result)) return result;

            // ④ 본문 검색 (부처 없이)
            result = trySearch(keyword, 2, null);
            return result;
        } catch (WebClientResponseException | WebClientRequestException e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, e);
        } catch (Exception e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "알 수 없는 예외 발생", e);
        }
    }

    private String trySearch(String keyword, int searchType, String orgCode) {
        return webClientBuilder.build()
                .get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .scheme("http")
                            .host("www.law.go.kr")
                            .path("/DRF/lawSearch.do")
                            .queryParam("OC", oc)
                            .queryParam("target", "law")
                            .queryParam("type", "JSON")
                            .queryParam("search", searchType)
                            .queryParam("query", keyword)
                            .queryParam("display", MAX_DISPLAY)
                            .queryParam("sort", "lasc");

                    if (orgCode != null && !orgCode.isBlank()) {
                        builder.queryParam("org", orgCode);
                    }

                    return builder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }


    private boolean isNotEmpty(String json) {
        return json != null && json.contains("\"law\"");
    }

    @Transactional
    public List<Law> saveLawsWithArticles(String lawName) {
        List<String> mstList = getLawMstByName(lawName);
        System.out.println("📄 조회된 Mst 목록: " + mstList);

        if (mstList.isEmpty()) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "법령 MST 없음");
        }

        List<Law> savedLaws = new ArrayList<>();
        for (String mst : mstList) {
            try {
                Law law = saveSingleLawWithArticles(mst);
                savedLaws.add(law);
                System.out.println("✅ 저장 완료: " + law.getName() + " (ID: " + law.getId() + ")");
            } catch (Exception e) {
                System.err.println("❌ Mst 저장 실패 (" + mst + "): " + e.getMessage());
            }
        }

        return savedLaws;
    }


    public Law saveSingleLawWithArticles(String lawId) {
        JsonNode lawNode = getLawDetailByMst(lawId);

        String lawName = lawNode.path("기본정보").path("법령명_한글").asText();
        String lawCode = lawNode.path("기본정보").path("법령ID").asText(); // ✅ 법령ID
        String department = lawNode.path("기본정보").path("소관부처").path("content").asText(); // ✅ 부처명
        System.out.println("🧾 법령명: " + lawName);
        System.out.println("🧾 법령 코드: " + lawCode);
        System.out.println("🏢 소관 부처: " + department);

        // 중복 저장 방지
        Law law = lawRepository.findByNameAndLawCode(lawName, lawCode)
                .orElseGet(() -> lawRepository.save(Law.builder()
                        .name(lawName)
                        .lawCode(lawCode)
                        .department(department)
                        .build()));

        JsonNode articleRoot = lawNode.path("조문").path("조문단위");
        System.out.println("📜 조문단위 총 개수: " + (articleRoot.isArray() ? articleRoot.size() : "null 또는 배열 아님"));

        if (!articleRoot.isArray()) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE,
                    "조문단위 없음 - 응답 노드: " + lawNode.toPrettyString());
        }

        for (JsonNode article : articleRoot) {
            String articleNo = article.path("조문번호").asText();
            String articleContent = article.path("조문내용").asText("");

            if (!articleContent.isBlank()) {
                saveOrUpdateChunk(law, articleNo, null, 0, articleContent);
            }

            JsonNode clauses = article.path("항");
            if (clauses != null && clauses.isObject()) {
                JsonNode hoArray = clauses.path("호");
                if (hoArray != null && hoArray.isArray()) {
                    for (JsonNode ho : hoArray) {
                        String hoNo = ho.path("호번호").asText();
                        String hoContent = ho.path("호내용").asText();

                        if (!hoContent.isBlank()) {
                            saveOrUpdateChunk(law, articleNo, hoNo, 1, hoContent);
                        }

                        JsonNode mokArray = ho.path("목");
                        if (mokArray != null && mokArray.isArray()) {
                            for (JsonNode mok : mokArray) {
                                String mokNo = mok.path("목번호").asText();
                                String mokContent = mok.path("목내용").asText();

                                if (!mokContent.isBlank()) {
                                    saveOrUpdateChunk(law, articleNo, hoNo + "-" + mokNo, 2, mokContent);
                                }
                            }
                        }
                    }
                }
            }
        }

        return law;
    }



    private void saveOrUpdateChunk(Law law, String articleNo, String clauseNo, int index, String content) {
        Optional<LawChunk> existing = lawChunkRepository
                .findByLawAndArticleNumberAndClauseNumber(law, articleNo, clauseNo);

        if (existing.isPresent()) {
            LawChunk chunk = existing.get();
            if (!chunk.getContentText().equals(content.trim())) {
                chunk.setContentText(content.trim());
                chunk.setChunkIndex(index);
                chunk.setCreatedAt(LocalDateTime.now());
                lawChunkRepository.save(chunk); // ✅ 내용 바뀐 경우에만 업데이트
            }
        } else {
            LawChunk chunk = LawChunk.builder()
                    .law(law)
                    .articleNumber(articleNo)
                    .clauseNumber(clauseNo)
                    .chunkIndex(index)
                    .contentText(content.trim())
                    .build();
            lawChunkRepository.save(chunk);
        }
    }


    public List<String> getLawMstByName(String lawName) {
        try {
            String json = searchLawByKeyword(lawName);
            JsonNode node = objectMapper.readTree(json);
            JsonNode lawArr = node.path("LawSearch").path("law");

            List<String> MstList = new ArrayList<>();

            if (lawArr.isArray()) {
                for (JsonNode law : lawArr) {
                    String name = law.path("법령명한글").asText();
                    if (name != null && name.startsWith(lawName)) {
                        String Mst = law.path("법령일련번호").asText();
                        if (!Mst.isBlank()) {
                            MstList.add(Mst);
                        }
                    }
                }
            }

            return MstList;

        } catch (JsonProcessingException e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "JSON 파싱 실패", e);
        }
    }


    public JsonNode getLawDetailByMst(String mst) {
        try {
            URI uri = UriComponentsBuilder.newInstance()
                    .scheme("http")
                    .host("www.law.go.kr")
                    .path("/DRF/lawService.do")
                    .queryParam("OC", oc)
                    .queryParam("target", "law")
                    .queryParam("type", "JSON")
                    .queryParam("MST", mst)
                    .build(true)
                    .toUri();

            System.out.println("✅ 최종 URI: " + uri); // Postman URL과 정확히 비교

            JsonNode response = webClientBuilder
                    .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build()
                    .get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            JsonNode lawNode = response.path("법령");

            if (lawNode.isMissingNode() || lawNode.isEmpty()) {
                throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "법령 노드 없음 (MST=" + mst + ")");
            }

            return lawNode;

        } catch (WebClientResponseException e) {
            System.err.println("❌ WebClient 응답 예외 발생:");
            System.err.println(" - Status Code: " + e.getRawStatusCode());
            System.err.println(" - Status Text: " + e.getStatusText());
            System.err.println(" - Response Body: " + e.getResponseBodyAsString());
            System.err.println(" - Headers: " + e.getHeaders());
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "본문 API 응답 오류", e);
        } catch (Exception e) {
            e.printStackTrace();
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "본문 API 예외", e);
        }
    }






}
