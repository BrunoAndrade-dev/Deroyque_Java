package br.com.deroyque.activation.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI activationOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Deroyque — Solicitações de ativação").version("v1")
                        .description("""
                                API REST para registrar e acompanhar solicitações de ativação de produtos.
                                O processamento é assíncrono: a criação confirma a persistência da solicitação;
                                o resultado da ativação deve ser acompanhado pelo endpoint de consulta.

                                **Autenticação:** access token JWT no header `Authorization: Bearer <token>`.
                                O escopo `requests:write` autoriza a criação; `requests:read` autoriza consultas.
                                Os registros são isolados por consumidor. Identificadores internos utilizam UUID
                                e datas são representadas em UTC no formato ISO-8601.

                                Use **Authorize** para informar um token válido. A documentação não emite tokens;
                                na demonstração local, utilize a página auxiliar de tokens temporários.
                                """))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Informe somente o access token, sem o prefixo Bearer. "
                                + "A autorização não é persistida entre recarregamentos.")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
