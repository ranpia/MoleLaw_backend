package com.MoleLaw_backend.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SwaggerAccessPolicy {
    private final Environment environment;

    public AuthorizationDecision authorize() {
        return new AuthorizationDecision(environment.acceptsProfiles(Profiles.of("local", "test"))
                && (environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false)
                || environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, false)));
    }
}
