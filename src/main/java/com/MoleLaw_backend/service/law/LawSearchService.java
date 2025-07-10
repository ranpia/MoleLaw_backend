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
    public Law saveLawWithArticles(String lawName) {
        try {
            String lawMst = getLawMstByName(lawName)
                    .orElseThrow(() -> new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "법령 ID 조회 실패"));

            JsonNode lawNode = getLawDetailByMst(lawMst);

            Law law = lawRepository.findByName(lawName)
                    .orElseGet(() -> lawRepository.save(Law.builder()
                            .name(lawName)
                            .lawCode(lawNode.path("법령일련번호").asText())
                            .department(lawNode.path("소관부처").path("content").asText())
                            .build()));

            JsonNode articleRoot = lawNode.path("조문").path("조문단위");
            if (articleRoot == null || !articleRoot.isArray()) {
                throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "조문단위 없음");
            }

            for (JsonNode article : articleRoot) {
                String articleNo = article.path("조문번호").asText();
                String articleTitle = article.path("조문제목").asText(null);
                String articleContent = article.path("조문내용").asText("");

                if (!articleContent.isBlank()) {
                    saveChunk(law, articleNo, null, 0, articleContent);
                }

                // 항 처리
                JsonNode clauses = article.path("항");
                if (clauses.isObject()) {
                    JsonNode hoArray = clauses.path("호");
                    if (hoArray != null && hoArray.isArray()) {
                        for (JsonNode ho : hoArray) {
                            String hoNo = ho.path("호번호").asText();
                            String hoContent = ho.path("호내용").asText();

                            if (!hoContent.isBlank()) {
                                saveChunk(law, articleNo, hoNo, 1, hoContent);
                            }

                            // ✅ 목 처리
                            JsonNode mokArray = ho.path("목");
                            if (mokArray != null && mokArray.isArray()) {
                                for (JsonNode mok : mokArray) {
                                    String mokNo = mok.path("목번호").asText();
                                    String mokContent = mok.path("목내용").asText();

                                    if (!mokContent.isBlank()) {
                                        saveChunk(law, articleNo, hoNo + "-" + mokNo, 2, mokContent);
                                    }
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
                    if (law.path("법령명한글").asText().equals(lawName)) {
                        return Optional.of(law.path("법령ID").asText());
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

            return response.path("법령");
        } catch (WebClientResponseException e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "본문 API 응답 오류", e);
        } catch (Exception e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "본문 API 예외", e);
        }
    }



}
