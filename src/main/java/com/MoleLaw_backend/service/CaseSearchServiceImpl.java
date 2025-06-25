package com.MoleLaw_backend.service;

import com.MoleLaw_backend.dto.PrecedentInfo;
import com.MoleLaw_backend.dto.PrecedentSearchRequest;
import com.MoleLaw_backend.dto.PrecedentSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CaseSearchServiceImpl implements CaseSearchService {

    private final WebClient.Builder webClientBuilder;

    @Value("${openlaw.api-key}")
    private String oc;

    private static final String BASE_URL = "http://www.law.go.kr";

    @Override
    public List<PrecedentInfo> searchCases(PrecedentSearchRequest request) {
        PrecedentSearchResponse response = webClientBuilder.build()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("http")
                        .host("www.law.go.kr")
                        .path("/DRF/lawSearch.do")
                        .queryParam("OC", oc)
                        .queryParam("target", "prec")
                        .queryParam("type", "JSON")
                        .queryParam("JO", request.getQuery())
                        .build())
                .retrieve()
                .bodyToMono(PrecedentSearchResponse.class)
                .block();

        if (response == null || response.getPrecSearch() == null) {
            throw new RuntimeException("❌ PrecSearch 응답이 없습니다");
        }

        List<PrecedentInfo> precList = Optional.ofNullable(response.getPrecSearch().getPrec())
                .orElse(List.of()); // 빈 리스트 반환 (예외 아님)

        return precList;
    }
}
