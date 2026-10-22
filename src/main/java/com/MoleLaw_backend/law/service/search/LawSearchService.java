package com.MoleLaw_backend.law.service.search;

import com.MoleLaw_backend.law.dto.request.LawSearchFilters;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.exception.OpenLawApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.net.URI;

@Service
@Slf4j
@RequiredArgsConstructor
public class LawSearchService {

    private final WebClient.Builder webClientBuilder;

    @Value("${openlaw.api-key}")
    private String oc;

    private static final int MAX_DISPLAY = 20;
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
        return searchPage(keyword, searchType, orgCode, 1, MAX_DISPLAY);
    }

    public String searchPage(String keyword, int searchType, String orgCode, int page, int display) {
        return searchPage(keyword, searchType, orgCode, page, display, LawSearchFilters.NONE);
    }

    public String searchPage(String keyword, int searchType, String orgCode, int page, int display,
                             LawSearchFilters filters) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 200
                || (searchType != 1 && searchType != 2) || page < 1 || page > 10000
                || display < 1 || display > 100
                || (orgCode != null && !orgCode.matches("[0-9]{1,10}"))
                || filters == null || (filters.exactName() && searchType != 1)) {
            throw new IllegalArgumentException("Invalid law search conditions");
        }
        return webClientBuilder.build()
                .get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .scheme("https")
                            .host("www.law.go.kr")
                            .path("/DRF/lawSearch.do")
                            .queryParam("OC", oc)
                            .queryParam("target", "eflaw")
                            .queryParam("type", "JSON")
                            .queryParam("search", searchType)
                            .queryParam("query", "{query}")
                            .queryParam("display", display)
                            .queryParam("page", page)
                            .queryParam("sort", filters.sort());

                    if (orgCode != null && !orgCode.isBlank()) {
                        builder.queryParam("org", orgCode);
                    }
                    if (filters.lawId() != null) builder.queryParam("LID", filters.lawId());
                    if (filters.kind() != null) builder.queryParam("knd", filters.kind());
                    if (filters.status() != null) builder.queryParam("nw", filters.status());
                    if (filters.effectiveDateRange() != null) builder.queryParam("efYd", filters.effectiveDateRange());
                    if (filters.promulgationDateRange() != null) builder.queryParam("ancYd", filters.promulgationDateRange());

                    return builder.build(java.util.Map.of("query", filters.exactName() ? "\"" + keyword.strip() + "\"" : keyword));
                })
                .retrieve()
                .bodyToMono(String.class)
                .block(java.time.Duration.ofSeconds(10));
    }

    private boolean isNotEmpty(String json) {
        return json != null && json.contains("\"law\"");
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
                    if (name != null) {
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
                    .scheme("https")
                    .host("www.law.go.kr")
                    .path("/DRF/lawService.do")
                    .queryParam("OC", oc)
                    .queryParam("target", "law")
                    .queryParam("type", "JSON")
                    .queryParam("MST", mst)
                    .build(true)
                    .toUri();

            JsonNode response = webClientBuilder
                    .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build()
                    .get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(java.time.Duration.ofSeconds(10));

            if (response == null) {
                throw new OpenLawApiException(ErrorCode.OPENLAW_INVALID_RESPONSE);
            }
            JsonNode lawNode = response.path("법령");

            if (lawNode.isMissingNode() || lawNode.isEmpty()) {
                throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "법령 노드 없음 (MST=" + mst + ")");
            }

            return lawNode;

        } catch (WebClientResponseException e) {
            log.warn("법령 상세 조회 실패: status={}", e.getStatusCode().value());
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "본문 API 응답 오류", e);
        } catch (Exception e) {
            log.warn("법령 상세 조회 실패: errorType={}", e.getClass().getSimpleName());
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "본문 API 예외", e);
        }
    }

}
