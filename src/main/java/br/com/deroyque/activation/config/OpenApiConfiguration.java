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
                        .description("API de ativação assíncrona. Uma criação retorna PENDING; consulte o pedido "
                                + "para acompanhar o resultado. Requer JWT de um emissor externo: requests:write "
                                + "para criar e requests:read para consultar. Cada consumidor acessa somente seus pedidos. "
                                + "O Swagger não emite tokens; sem emissor configurado, chamadas protegidas retornam 401."))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Informe somente o access token, sem o prefixo Bearer. "
                                + "A autorização não é persistida entre recarregamentos.")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
