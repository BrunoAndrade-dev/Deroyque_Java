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

/**
 * Gera chaves e JWTs efêmeros exclusivamente para testes, sem persistir tokens ou material privado em
 * arquivos ou artefatos de produção.
 */
public final class TestTokens {

    /** Identificador de emissor fictício, utilizado somente na validação dos testes. */
    public static final String ISSUER = "https://issuer.example.test";
    /** Audiência da API nos cenários de teste. */
    public static final String AUDIENCE = "activation-api-test";
    /** Chave de assinatura efêmera do processo de teste; nunca exportar o material privado. */
    public static final RSAKey KEY = newKey();

    /**
     * Impede instanciação do utilitário de tokens efêmeros.
     */
    private TestTokens() {
    }

    /**
     * Gera RSA 2048 efêmero com kid novo, sem persistência em disco.
     *
     * @return chave de teste com material privado apenas em memória.
     */
    public static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot generate ephemeral test key");
        }
    }

    /**
     * Exporta exclusivamente os componentes públicos das chaves.
     *
     * @param keys chaves que terão somente os componentes públicos exportados.
     * @return JWK Set JSON sem chave privada.
     */
    public static String jwks(RSAKey... keys) {
        return new JWKSet(java.util.Arrays.stream(keys).map(RSAKey::toPublicJWK)
                .map(key -> (com.nimbusds.jose.jwk.JWK) key).toList()).toString();
    }

    /**
     * Deriva o dono padrão usado nas fixtures de integração.
     *
     * @return identidade do consumidor test-system.
     */
    public static String ownerId() {
        return br.com.deroyque.activation.security.CurrentConsumer.identity(ISSUER, "test-system");
    }

    /**
     * Assina um JWT efêmero para cenários de teste. A sobrecarga completa permite alterar claims, chave
     * e algoritmo para verificar rejeições.
     *
     * @param scope escopos do token, ou null para testar ausência.
     * @return JWT assinado, a ser mantido fora de logs e arquivos.
     */
    public static String token(String scope) {
        return token(scope, builder -> { });
    }

    /**
     * Assina um JWT efêmero para cenários de teste. A sobrecarga completa permite alterar claims, chave
     * e algoritmo para verificar rejeições.
     *
     * @param scope escopos do token, ou null para testar ausência.
     * @param customization ajuste de claims para o cenário.
     * @return JWT assinado, a ser mantido fora de logs e arquivos.
     */
    public static String token(String scope, Consumer<JWTClaimsSet.Builder> customization) {
        return token(scope, customization, KEY, JWSAlgorithm.RS256);
    }

    /**
     * Assina um JWT efêmero para cenários de teste. A sobrecarga completa permite alterar claims, chave
     * e algoritmo para verificar rejeições.
     *
     * @param scope escopos do token, ou null para testar ausência.
     * @param customization ajuste de claims para o cenário.
     * @param key chave efêmera de assinatura.
     * @param algorithm algoritmo a exercitar no cenário.
     * @return JWT assinado, a ser mantido fora de logs e arquivos.
     */
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
