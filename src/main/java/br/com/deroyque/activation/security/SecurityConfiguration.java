package br.com.deroyque.activation.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * Configura validação RS256, origem confiável das chaves e autorização stateless por escopos.
 * Documentação habilitada e probes possuem leitura pública; pedidos continuam protegidos.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    /**
     * Configura acesso às chaves com timeout e sem seguir redirecionamentos.
     *
     * @return cliente HTTP exclusivo da validação JWT.
     */
    @Bean
    HttpClient jwtHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Valida RS256, emissor, audiência e datas, convertendo falhas de obtenção de chaves em rejeição
     * de autenticação com resposta 401.
     *
     * @param properties configuração validada do componente.
     * @param client cliente HTTP utilizado pelo componente.
     * @return decoder baseado exclusivamente no JWK Set configurado.
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties, @Qualifier("jwtHttpClient") HttpClient client) {
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(2));
        var decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(factory)).build();
        var timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.issuerUri().toString()), timestamps,
                new AccessTokenClaimsValidator(properties.audience(), Clock.systemUTC())));
        return token -> {
            try {
                return decoder.decode(token);
            } catch (JwtException exception) {
                // Falhas na obtenção de chaves também devem retornar 401 com mensagem sanitizada.
                // No JwtAuthenticationProvider, uma JwtException genérica é convertida em
                // AuthenticationServiceException e poderia resultar em 500.
                throw new BadJwtException("Unable to validate access token.");
            }
        };
    }

    /**
     * Autoriza operações por escopo, limita Bearer ao header e desabilita sessão, Basic e formulário.
     *
     * @param http configurador de segurança HTTP.
     * @param decoder validador de tokens JWT.
     * @param errors handler sanitizado de autenticação e autorização.
     * @return cadeia stateless com handlers sanitizados de 401 e 403.
     * @throws Exception se a construção da cadeia de segurança falhar.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
                                           SecurityProblemHandler errors) throws Exception {
        var resolver = new DefaultBearerTokenResolver();
        resolver.setAllowFormEncodedBodyParameter(false);
        resolver.setAllowUriQueryParameter(false);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(access -> access
                        .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority("SCOPE_requests:write")
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*")
                        .hasAuthority("SCOPE_requests:read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.bearerTokenResolver(resolver)
                        .jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }
}
