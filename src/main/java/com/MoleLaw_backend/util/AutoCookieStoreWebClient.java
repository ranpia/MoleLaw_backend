package com.MoleLaw_backend.util;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AutoCookieStoreWebClient {

    private static final Map<String, String> cookieStore = new ConcurrentHashMap<>();
    private static final String TARGET_COOKIE_NAME = "elevisor_for_j2ee_uid";

    public static WebClient create() {
        return WebClient.builder()
                .baseUrl("http://www.law.go.kr")
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; MoleLawBot/1.0)")
                .filter(cookieExchangeFilter())
                .build();
    }

    private static ExchangeFilterFunction cookieExchangeFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            ClientRequest.Builder builder = ClientRequest.from(request);

            // ✅ 요청에 쿠키 붙이기 (있을 때만)
            String cookieValue = cookieStore.get(TARGET_COOKIE_NAME);
            if (cookieValue != null) {
                builder.header(HttpHeaders.COOKIE, TARGET_COOKIE_NAME + "=" + cookieValue);
            }

            return Mono.just(builder.build());
        }).andThen((request, next) -> {
            return next.exchange(request).doOnNext(response -> {
                // ✅ 응답에서 Set-Cookie 파싱
                String setCookie = response.headers().asHttpHeaders().getFirst(HttpHeaders.SET_COOKIE);
                if (setCookie != null && setCookie.contains(TARGET_COOKIE_NAME)) {
                    String value = setCookie.split("=")[1].split(";")[0];
                    cookieStore.put(TARGET_COOKIE_NAME, value);
                }
            });
        });
    }
}
