package com.MoleLaw_backend.service.law;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
public class LawSearchService {

    private final WebClient.Builder webClientBuilder;

    @Value("${openlaw.api-key}")
    private String oc;

    public String searchLawByKeyword(String keyword) {
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
                .block(); // 동기적으로 처리 (비동기로 할 경우 .subscribe())
    }
}
