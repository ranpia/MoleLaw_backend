package com.MoleLaw_backend.config;

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
                                + "POST/PUT/PATCH/DELETE는 로그인·가입을 포함하여 GET /api/auth/csrf에서 받은 토큰을 X-XSRF-TOKEN 헤더로 보내야 합니다. "
                                + "Swagger UI에서 먼저 CSRF 조회를 실행하고 반환된 token을 Authorize의 CsrfToken에 한 번 등록하세요. 이후 상태 변경 요청에 자동 적용됩니다. 쿠키가 유지되는 같은 서버에서 실행하세요.")
                        .version("v1.0"))
                .components(new Components().addSecuritySchemes("AccessCookie", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("accessToken")
                        .description("HttpOnly Access JWT 쿠키. 로그인 응답의 Set-Cookie로 설정되며 브라우저가 자동 전송합니다."))
                        .addSecuritySchemes("CsrfToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-XSRF-TOKEN")
                                .description("GET /api/auth/csrf 응답의 token을 입력하세요. 같은 브라우저의 XSRF-TOKEN 쿠키와 함께 전송됩니다. 토큰을 새로 받으면 이 값도 갱신하세요."))
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
                if (publicEndpoint) {
                    operation.setSecurity(List.of(new SecurityRequirement().addList("CsrfToken")));
                } else {
                    operation.getSecurity().forEach(requirement -> requirement.addList("CsrfToken"));
                }
                if (operation.getParameters() != null) {
                    operation.getParameters().removeIf(parameter -> "header".equals(parameter.getIn())
                            && "X-XSRF-TOKEN".equalsIgnoreCase(parameter.getName()));
                }
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
                operation.getResponses().addApiResponse("401", new ApiResponse().description("Access 인증 누락·만료·잘못된 용도")
                        .content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new ObjectSchema().addProperty("error", new StringSchema()))
                                .example(java.util.Map.of("error", "Unauthorized")))));
            }
            return operation;
        };
    }

}
