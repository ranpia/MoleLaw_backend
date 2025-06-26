package com.MoleLaw_backend.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.security.SecurityRequirement;

import java.util.List;

@Configuration
@SecurityScheme(
        name = "BearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
@OpenAPIDefinition(
        servers = {
                @Server(url = "https://team-moleback.store", description = "molelaw 배포 백엔드 서버"),
                @Server(url = "http://localhost:8080", description = "개발 로컬 환경")
        }
)
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .servers(List.of(
                        new io.swagger.v3.oas.models.servers.Server().url("https://team-moleback.store")  // 💡 여기만 풀네임!
                ))
                .info(new Info()
                        .title("MoleLaw API")
                        .description("MoleLaw 백엔드 API 문서입니다.")
                        .version("v1.0"))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"));
    }
}
