package com.MoleLaw_backend.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import java.util.List;

@Configuration
@org.springframework.context.annotation.Profile({"local", "test"})
@OpenAPIDefinition(
        servers = {
                @Server(url = "/", description = "현재 접속한 서버"),
                @Server(url = "https://team-moleback.store", description = "molelaw 배포 백엔드 서버"),
                @Server(url = "http://localhost:8080", description = "개발 로컬 환경")
        }
)

public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MoleLaw API")
                        .description("MoleLaw 백엔드 개발용 API 문서.\n\n"
                                + "### 실행 순서\n"
                                + "1. GET /api/auth/csrf를 실행합니다. 이후 변경 요청에는 CSRF 헤더가 자동으로 추가됩니다.\n"
                                + "2. 로그인 API로 인증 쿠키를 발급받습니다.\n"
                                + "3. 선택적으로 POST /api/legal-answers/suggestions에 초안을 보내 추천 질문을 받습니다. 이 단계는 상담을 저장하지 않습니다.\n"
                                + "4. 선택한 question 또는 원래 질문을 POST /api/chat-rooms의 content로 보내 최초 상담을 시작합니다.\n"
                                + "5. 생성된 상담방에서 후속 메시지와 근거 조회 API를 사용합니다. 판례 검색 동의는 해당 요청에서 별도로 전달합니다.\n\n"
                                + "### 인증·보안\n"
                                + "보호된 API는 Bearer Access JWT 또는 accessToken 쿠키로 인증하며 Bearer가 우선합니다. "
                                + "Access는 5분 stateless JWT로 Redis 조회 없이 검증합니다. 로그아웃·비밀번호 변경 후에도 기존 Access는 만료까지 유효합니다. Refresh 저장·회전·폐기의 Redis 장애는 503입니다. "
                                + "Access subject는 User.id 문자열이며 tokenVersion=3, issuer=molelaw-auth, audience=molelaw-api입니다. Refresh는 난수 기반 HttpOnly 쿠키로만 전달하며 매 재발급마다 회전합니다. 이전 JWT Refresh는 거절하므로 재로그인해야 합니다. "
                                + "POST/PUT/PATCH/DELETE는 로그인·가입을 포함하여 GET /api/auth/csrf에서 받은 토큰을 X-XSRF-TOKEN 헤더로 보내야 합니다. "
                                + "같은 서버의 Swagger UI에서 GET /api/auth/csrf를 한 번 실행하면 XSRF-TOKEN 쿠키를 읽어 이후 요청에 X-XSRF-TOKEN 헤더를 자동으로 추가합니다. 직접 토큰을 입력할 필요가 없습니다.")
                        .version("v1.0"))
                .components(new Components().addSecuritySchemes("AccessCookie", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("accessToken")
                        .description("HttpOnly Access JWT 쿠키. 로그인 응답의 Set-Cookie로 설정되며 브라우저가 자동 전송합니다."))
                        .addSchemas("BusinessErrorResponse", new ObjectSchema()
                                .description("GlobalExceptionHandler의 오류 응답. null data는 직렬화에서 생략됩니다.")
                                .addProperty("success", new io.swagger.v3.oas.models.media.BooleanSchema().example(false))
                                .addProperty("error", new ObjectSchema()
                                        .addProperty("status", new io.swagger.v3.oas.models.media.IntegerSchema().example(400))
                                        .addProperty("code", new StringSchema().example("INVALID_REQUEST"))
                                        .addProperty("message", new StringSchema().example("잘못된 요청입니다.")))));
    }

    @Bean
    public OperationCustomizer authenticationDocumentation() {
        return (operation, handler) -> {
            String method = handler.getMethod().getName();
            boolean publicEndpoint = handler.getBeanType().getSimpleName().equals("CsrfController")
                    || (handler.getBeanType().getSimpleName().equals("AuthController")
                    && java.util.Set.of("signup", "login", "logout", "refresh").contains(method));
            operation.setSecurity(publicEndpoint ? List.of() : List.of(
                    new SecurityRequirement().addList("AccessCookie")));
            boolean unsafe = handler.hasMethodAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                    || handler.hasMethodAnnotation(org.springframework.web.bind.annotation.PutMapping.class)
                    || handler.hasMethodAnnotation(org.springframework.web.bind.annotation.PatchMapping.class)
                    || handler.hasMethodAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class);
            if (unsafe) {
                if (operation.getResponses().get("403") == null) {
                    operation.getResponses().addApiResponse("403", new ApiResponse().description(
                            "CSRF 토큰 누락·불일치 또는 허용되지 않은 Origin. 필터에서 거절되며 ApiResponse JSON을 보장하지 않습니다."));
                } else {
                    var forbidden = operation.getResponses().get("403");
                    forbidden.setDescription(forbidden.getDescription()
                            + " / CSRF·Origin 필터 거절 시 본문 형식은 ApiResponse JSON을 보장하지 않습니다.");
                }
            }
            if (!publicEndpoint) {
                operation.getResponses().addApiResponse("401", new ApiResponse().description("Access 인증 누락·만료·서명/용도/버전/issuer/audience 오류 또는 사용자 없음")
                        .content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new ObjectSchema().addProperty("error", new StringSchema()))
                                .example(java.util.Map.of("error", "Unauthorized")))));
            }
            if ((handler.getBeanType().getSimpleName().equals("AuthController") || handler.getBeanType().getSimpleName().equals("UserController"))
                    && java.util.Set.of("signup", "login", "refresh", "logout", "changePassword", "deleteUser").contains(method)) {
                operation.getResponses().addApiResponse("503", new ApiResponse()
                        .description("Redis Refresh 저장·회전·폐기 실패. 기존 Access 검증은 Redis에 의존하지 않습니다. 사용한 Refresh로 자동 재시도하지 않습니다.")
                        .content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/BusinessErrorResponse"))
                                .example(java.util.Map.of("success", false, "error", java.util.Map.of(
                                        "status", 503, "code", "AUTH_STORE_UNAVAILABLE",
                                        "message", "인증 저장소를 사용할 수 없습니다. 잠시 후 다시 시도하세요."))))));
            }
            if (publicEndpoint && java.util.Set.of("signup", "login").contains(method)) {
                operation.getResponses().addApiResponse("401", new ApiResponse()
                        .description("발급 전 Refresh 사용자 세대가 변경·유실되어 등록 거절. 재로그인이 필요합니다.")
                        .content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/BusinessErrorResponse"))
                                .example(java.util.Map.of("success", false, "error", java.util.Map.of(
                                        "status", 401, "code", "INVALID_REFRESH_TOKEN",
                                        "message", "유효하지 않거나 만료·폐기된 Refresh 토큰입니다."))))));
            }
            return operation;
        };
    }

}
