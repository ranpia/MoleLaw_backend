package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.domain.entity.Law;
import com.MoleLaw_backend.domain.entity.LawChunk;
import com.MoleLaw_backend.domain.repository.LawChunkRepository;
import com.MoleLaw_backend.domain.repository.LawRepository;
import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.OpenLawApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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
        }
        catch (WebClientResponseException | WebClientRequestException e) {
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
    public Law saveLawWithArticles(String lawName) {
        try {
            // 1. 법령 MST 조회
            String lawMst = getLawMstByName(lawName)
                    .orElseThrow(() -> new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "법령 ID 조회 실패"));

            // 2. 법령 본문 조회
            JsonNode lawNode = getLawDetailByMst(lawMst);

            // 3. Law 엔티티 저장
            Law law = lawRepository.findByName(lawName)
                    .orElseGet(() -> lawRepository.save(Law.builder()
                            .name(lawName)
                            .lawCode(lawNode.get("법령일련번호").asText())
                            .department(lawNode.get("소관부처명").asText())
                            .build()));

            // 4. 조문 파싱
            JsonNode articles = lawNode.get("조문");
            if (articles == null || !articles.isArray()) {
                throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "조문 없음");
            }

            for (JsonNode article : articles) {
                String articleNo = article.path("조문번호").asText();
                String articleTitle = article.path("조문제목").asText(null);
                String articleContent = article.path("조문내용").asText("");

                // ✅ 조문 제목 저장 (예: 제140조(교통안전교육기관의 수강료 등))
                if (!articleContent.isBlank()) {
                    saveChunk(law, articleNo, null, 0, articleContent);
                }

                // ✅ 항 저장
                JsonNode clauses = article.path("항");
                if (clauses != null && clauses.isArray()) {
                    for (JsonNode clause : clauses) {
                        String clauseNo = clause.path("항번호").asText();
                        String clauseText = clause.path("항내용").asText();
                        if (!clauseText.isBlank()) {
                            saveChunk(law, articleNo, clauseNo, 1, clauseText);
                        }

                        // ✅ 호 저장 (선택)
                        JsonNode hos = clause.path("호");
                        if (hos != null && hos.isArray()) {
                            for (JsonNode ho : hos) {
                                String hoNo = ho.path("호번호").asText();
                                String hoText = ho.path("호내용").asText();
                                if (!hoText.isBlank()) {
                                    saveChunk(law, articleNo, clauseNo + "-" + hoNo, 2, hoText);
                                }
                            }
                        }
                    }
                }
            }

            return law;

        } catch (Exception e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "법령 본문 수집 실패", e);
        }
    }

    private void saveChunk(Law law, String articleNo, String clauseNo, int index, String content) {
        LawChunk chunk = LawChunk.builder()
                .law(law)
                .articleNumber(articleNo)
                .clauseNumber(clauseNo)
                .chunkIndex(index)
                .contentText(content.trim())
                .build();
        lawChunkRepository.save(chunk);
    }



    public Optional<String> getLawMstByName(String lawName) {
        try {
            String json = searchLawByKeyword(lawName);
            JsonNode node = objectMapper.readTree(json);
            JsonNode lawArr = node.path("LawSearch").path("law");

            if (lawArr.isArray()) {
                for (JsonNode law : lawArr) {
                    String name = law.get("법령명한글").asText();
                    if (name.equals(lawName)) {
                        return Optional.of(law.get("법령일련번호").asText()); // ✅ 여기
                    }
                }
            }
            return Optional.empty();
        } catch (JsonProcessingException e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "JSON 파싱 실패", e);
        }
    }



    public JsonNode getLawDetailByMst(String mst) {
        try {
            JsonNode response = webClientBuilder.build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("http")
                            .host("www.law.go.kr")
                            .path("/DRF/lawService.do")
                            .queryParam("OC", oc)
                            .queryParam("target", "law")
                            .queryParam("type", "JSON")
                            .queryParam("MST", mst)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            System.out.println("✅ 법령 본문 API 응답: " + response);

            return response.get("법령");  // ✅ 반드시 여기를 "법령"으로!
        } catch (WebClientResponseException e) {
            System.out.println("❌ WebClientResponseException: " + e.getRawStatusCode());
            System.out.println("❌ 응답 본문: " + e.getResponseBodyAsString());
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "본문 API 응답 오류", e);
        } catch (Exception e) {
            System.out.println("❌ 일반 예외: " + e.getMessage());
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "본문 API 예외", e);
        }
    }



}
