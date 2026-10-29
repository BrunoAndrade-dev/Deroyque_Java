package br.com.deroyque.activation.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.util.Collection;

/**
 * Valida claims obrigatórios, audiência e formato dos escopos após a decodificação. Assinatura, emissor
 * e validade temporal são tratados pelos demais componentes do decoder.
 */
public class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;
    private final Clock clock;

    /**
     * Recebe audiência e relógio utilizados na validação de claims.
     *
     * @param audience audiência esperada no JWT.
     * @param clock relógio utilizado na avaliação temporal.
     */
    public AccessTokenClaimsValidator(String audience, Clock clock) {
        this.audience = audience;
        this.clock = clock;
    }

    /**
     * Verifica subject, audiência, emissão, expiração obrigatória e formato de scope/scp.
     *
     * @param jwt token já decodificado a validar.
     * @return sucesso ou erro público genérico de token inválido.
     */
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

    /**
     * Aceita escopo ausente, string ou coleção composta exclusivamente por strings.
     *
     * @param value valor a examinar.
     * @return true quando o formato é compatível com o conversor de autoridades.
     */
    private boolean validScopes(Object value) {
        return value == null || value instanceof String
                || (value instanceof Collection<?> scopes && scopes.stream().allMatch(String.class::isInstance));
    }
}
