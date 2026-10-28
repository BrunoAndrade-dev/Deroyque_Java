package br.com.deroyque.activation.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.util.Collection;

public class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;
    private final Clock clock;

    public AccessTokenClaimsValidator(String audience, Clock clock) {
        this.audience = audience;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        boolean valid = jwt.getSubject() != null && !jwt.getSubject().isBlank()
                && jwt.getAudience() != null && jwt.getAudience().contains(audience)
                && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                && !jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
                && validScopes(jwt.getClaims().get("scope")) && validScopes(jwt.getClaims().get("scp"));
        return valid ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token.", null));
    }

    private boolean validScopes(Object value) {
        return value == null || value instanceof String
                || (value instanceof Collection<?> scopes && scopes.stream().allMatch(String.class::isInstance));
    }
}
