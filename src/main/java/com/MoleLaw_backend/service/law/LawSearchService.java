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

    public String searchLawByKeyword(String keyword) {
        try {
            return webClientBuilder.build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("http")
                            .host("www.law.go.kr")
                            .path("/DRF/lawSearch.do")
                            .queryParam("OC", oc)
                            .queryParam("target", "law")
                            .queryParam("type", "JSON")
                            .queryParam("query", keyword)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

        } catch (WebClientResponseException | WebClientRequestException e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, e);
        } catch (Exception e) {
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, "알 수 없는 예외 발생", e);
        }
    }
}
