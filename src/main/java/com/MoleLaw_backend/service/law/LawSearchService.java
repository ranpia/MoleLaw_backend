package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.OpenLawApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;


@Service
@RequiredArgsConstructor
public class LawSearchService {

    private final WebClient.Builder webClientBuilder;

    @Value("${openlaw.api-key}")
    private String oc;

    private static final int MAX_DISPLAY = 20;

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
}
