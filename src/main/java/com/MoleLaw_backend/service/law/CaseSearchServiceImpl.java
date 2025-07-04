package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.dto.PrecedentInfo;
import com.MoleLaw_backend.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.dto.response.PrecedentSearchResponse;
import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.OpenLawApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaseSearchServiceImpl implements CaseSearchService {

    private final WebClient.Builder webClientBuilder;

    @Value("${openlaw.api-key}")
    private String oc;

    @Override
    public List<PrecedentInfo> searchCases(PrecedentSearchRequest request) {
        try {
            PrecedentSearchResponse response = webClientBuilder.build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("http")
                            .host("www.law.go.kr")
                            .path("/DRF/lawSearch.do")
                            .queryParam("OC", oc)
                            .queryParam("target", "prec")
                            .queryParam("type", "JSON")
                            .queryParam("display", 5)
                            .queryParam("JO", request.getQuery())
                            .build())
                    .retrieve()
                    .bodyToMono(PrecedentSearchResponse.class)
                    .block();

            if (response == null || response.getPrecSearch() == null) {
                throw new OpenLawApiException(
                        ErrorCode.OPENLAW_INVALID_RESPONSE,
                        "응답이 null이거나 precSearch가 없습니다"
                );
            }

            return Optional.ofNullable(response.getPrecSearch().getPrec())
                    .orElse(List.of());

        } catch (WebClientResponseException e) {
            log.error("[OpenLaw] 응답 오류: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, e);
        } catch (WebClientRequestException e) {
            log.error("[OpenLaw] 요청 실패: message={}", e.getMessage());
            throw new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE, e);
        }
    }
}
