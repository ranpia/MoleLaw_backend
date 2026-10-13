package com.MoleLaw_backend.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class CookieUtilTest {
    @Test
    void insecureCookiesRequireExplicitLocalOrTestProfile() {
        assertThrows(IllegalArgumentException.class,
                () -> new CookieUtil(false, "Lax", new MockEnvironment()));
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod", "mysql");
        assertThrows(IllegalArgumentException.class, () -> new CookieUtil(false, "Lax", production));
        for (String profile : new String[]{"local", "test"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            assertDoesNotThrow(() -> new CookieUtil(false, "Lax", environment));
        }
    }

    @Test
    void sameSiteNoneRequiresSecureAndUnknownPoliciesAreRejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        assertThrows(IllegalArgumentException.class, () -> new CookieUtil(false, "None", environment));
        for (String invalid : new String[]{"none", "", "Invalid"}) {
            assertThrows(IllegalArgumentException.class, () -> new CookieUtil(true, invalid, environment));
        }
        for (String valid : new String[]{"None", "Lax", "Strict"}) {
            assertDoesNotThrow(() -> new CookieUtil(true, valid, environment));
        }
    }

    @Test
    void issuanceScopesRefreshAndKeepsCookieLifetimesAlignedWithTokens() {
        CookieUtil util = new CookieUtil(true, "None", new MockEnvironment());
        MockHttpServletResponse response = new MockHttpServletResponse();
        util.addJwtCookie(response, "accessToken", "access-value");
        util.addJwtCookie(response, "refreshToken", "refresh-value");
        var headers = response.getHeaders("Set-Cookie");
        assertEquals(3, headers.size());
        assertCookie(headers.get(0), "accessToken=access-value", "/", 54000);
        assertCookie(headers.get(1), "refreshToken=refresh-value", "/api/auth", 604800);
        assertCookie(headers.get(2), "refreshToken=", "/", 0);
    }

    @Test
    void deletionClearsBothCurrentAndLegacyRefreshPathsWithMatchingPolicy() {
        CookieUtil util = new CookieUtil(true, "None", new MockEnvironment());
        MockHttpServletResponse response = new MockHttpServletResponse();
        util.clearJwtCookie(response, "accessToken");
        util.clearJwtCookie(response, "refreshToken");
        var headers = response.getHeaders("Set-Cookie");
        assertEquals(3, headers.size());
        assertCookie(headers.get(0), "accessToken=", "/", 0);
        assertCookie(headers.get(1), "refreshToken=", "/api/auth", 0);
        assertCookie(headers.get(2), "refreshToken=", "/", 0);
    }

    @Test
    void localPolicyProducesHttpOnlyLaxCookiesWithoutSecure() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        CookieUtil util = new CookieUtil(false, "Lax", environment);
        MockHttpServletResponse response = new MockHttpServletResponse();
        util.addJwtCookie(response, "accessToken", "value");
        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
        assertFalse(header.contains("Secure"));
    }

    private static void assertCookie(String header, String value, String path, long maxAge) {
        assertTrue(header.startsWith(value + ";"), header);
        assertTrue(header.contains("Path=" + path + ";"), header);
        assertTrue(header.contains("Max-Age=" + maxAge + ";"), header);
        assertTrue(header.contains("Secure"), header);
        assertTrue(header.contains("HttpOnly"), header);
        assertTrue(header.contains("SameSite=None"), header);
    }
}
