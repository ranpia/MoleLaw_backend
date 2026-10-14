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
                        .description("MoleLaw 백엔드 API. 보호된 API는 Bearer Access JWT 또는 accessToken 쿠키로 인증하며 Bearer가 우선합니다. "
                                + "Access는 JWT 검증과 Redis jti 등록 확인을 모두 통과해야 합니다. 미등록·폐기 Access는 401, Redis 장애는 503입니다. "
                                + "Access/Refresh의 subject는 User.id 문자열이며 tokenVersion=2를 사용합니다. 이메일·provider는 claim에 포함하지 않습니다. 이전 토큰은 거절하므로 재로그인해야 합니다. "
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
                    || (handler.getBeanType().getSimpleName().equals("UserController")
                    && java.util.Set.of("signup", "login", "logout", "reissue").contains(method));
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
                operation.getResponses().addApiResponse("401", new ApiResponse().description("Access 인증 누락·만료·잘못된 용도 또는 Redis 등록 누락·폐기")
                        .content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new ObjectSchema().addProperty("error", new StringSchema()))
                                .example(java.util.Map.of("error", "Unauthorized")))));
            }
            if (!handler.getBeanType().getSimpleName().equals("CsrfController")) {
                operation.getResponses().addApiResponse("503", new ApiResponse()
                        .description("Redis 인증 저장소 조회·등록·폐기 실패. JWT만으로 우회하지 않습니다. 자동 재발급을 반복하지 말고 제한된 횟수로 재시도합니다.")
                        .content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/BusinessErrorResponse"))
                                .example(java.util.Map.of("success", false, "error", java.util.Map.of(
                                        "status", 503, "code", "AUTH_STORE_UNAVAILABLE",
                                        "message", "인증 저장소를 사용할 수 없습니다. 잠시 후 다시 시도하세요."))))));
            }
            return operation;
        };
    }

}
