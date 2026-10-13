package com.MoleLaw_backend.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import java.util.List;

@Configuration
@io.swagger.v3.oas.annotations.security.SecurityScheme(
        name = "BearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
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
                                + "Swagger UI에서 먼저 CSRF 조회를 실행하고 반환된 token을 각 요청 헤더에 입력하세요. 쿠키가 유지되는 같은 서버에서 실행하세요.")
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
                    new SecurityRequirement().addList("BearerAuth"),
                    new SecurityRequirement().addList("AccessCookie")));
            boolean unsafe = handler.hasMethodAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                    || handler.hasMethodAnnotation(org.springframework.web.bind.annotation.PutMapping.class)
                    || handler.hasMethodAnnotation(org.springframework.web.bind.annotation.PatchMapping.class)
                    || handler.hasMethodAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class);
            if (unsafe) {
                if (operation.getParameters() == null || operation.getParameters().stream()
                        .noneMatch(parameter -> "X-XSRF-TOKEN".equals(parameter.getName()))) {
                    operation.addParametersItem(new Parameter().name("X-XSRF-TOKEN").in("header").required(true)
                            .description("GET /api/auth/csrf 응답의 token. 해당 응답의 XSRF-TOKEN 쿠키도 함께 전송해야 합니다.")
                            .schema(new StringSchema()).example("csrf-endpoint-response-token"));
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
