package com.MoleLaw_backend.auth.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenLifetimeTest {
    private static final String SECRET = "test-secret-key-with-at-least-thirty-two-bytes";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(new ApplicationConversionService()))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(JwtUtil.class, CookieUtil.class)
            .withPropertyValues("jwt.secret=" + SECRET, "cookie.secure=true");

    @Test
    void productionDefaultsIssueFiveMinuteAccessAndMatchingCookie() {
        assertLifetime("mysql", 300);
    }

    @Test
    void localDevelopmentKeepsFifteenHourAccessAndMatchingCookie() {
        assertLifetime("local", 54000);
    }

    @Test
    void testProfileUsesProductionLifetime() {
        assertLifetime("test", 300);
    }

    @Test
    void testProfileDoesNotInheritDevelopmentLifetimeWhenLocalIsAlsoSelected() {
        assertLifetime("local,test", 300);
    }

    @Test
    void rejectsInvalidLifetimesAtStartup() {
        for (String lifetime : new String[]{"0s", "-1s", "500ms"}) {
            runner.withPropertyValues("spring.profiles.active=test", "jwt.access-ttl=" + lifetime)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseMessage("Access token TTL must be positive whole seconds");
                    });
        }
    }

    private void assertLifetime(String profile, long expectedSeconds) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed();
            JwtUtil jwt = context.getBean(JwtUtil.class);
            var parser = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).build();
            String access = jwt.generateAccessToken("user@example.com", "local");
            var accessClaims = parser.parseClaimsJws(access).getBody();
            assertThat(Duration.between(accessClaims.getIssuedAt().toInstant(),
                    accessClaims.getExpiration().toInstant()).getSeconds()).isEqualTo(expectedSeconds);

            var refreshClaims = parser.parseClaimsJws(jwt.generateRefreshToken("user@example.com", "local"))
                    .getBody();
            assertThat(Duration.between(refreshClaims.getIssuedAt().toInstant(),
                    refreshClaims.getExpiration().toInstant())).isEqualTo(Duration.ofDays(7));

            MockHttpServletResponse response = new MockHttpServletResponse();
            context.getBean(CookieUtil.class).addJwtCookie(response, "accessToken", access);
            assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=" + expectedSeconds + ";");
        });
    }
}
