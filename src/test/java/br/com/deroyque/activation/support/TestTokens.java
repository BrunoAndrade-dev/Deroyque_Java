package br.com.deroyque.activation.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

/** Ephemeral test-only keys. No private key or token is written to a file or application artifact. */
public final class TestTokens {

    public static final String ISSUER = "https://issuer.example.test";
    public static final String AUDIENCE = "activation-api-test";
    public static final RSAKey KEY = newKey();

    private TestTokens() {
    }

    public static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot generate ephemeral test key");
        }
    }

    public static String jwks(RSAKey... keys) {
        return new JWKSet(java.util.Arrays.stream(keys).map(RSAKey::toPublicJWK)
                .map(key -> (com.nimbusds.jose.jwk.JWK) key).toList()).toString();
    }

    public static String ownerId() {
        return br.com.deroyque.activation.security.CurrentConsumer.identity(ISSUER, "test-system");
    }

    public static String token(String scope) {
        return token(scope, builder -> { });
    }

    public static String token(String scope, Consumer<JWTClaimsSet.Builder> customization) {
        return token(scope, customization, KEY, JWSAlgorithm.RS256);
    }

    public static String token(String scope, Consumer<JWTClaimsSet.Builder> customization,
                               RSAKey key, JWSAlgorithm algorithm) {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject("test-system")
                .issueTime(Date.from(now.minusSeconds(1))).notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(300))).jwtID(UUID.randomUUID().toString());
        if (scope != null) claims.claim("scope", scope);
        customization.accept(claims);
        var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims.build());
        try {
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot sign ephemeral test token");
        }
    }
}
