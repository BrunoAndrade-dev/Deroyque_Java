# Deroyque Test

Serviço de solicitações de ativação de produtos, desenvolvido em etapas para o
desafio Java Pleno/Sênior da Deroyque.

## Estado atual

Etapa 1: estrutura Spring Boot, Maven Wrapper e verificações HTTP de saúde.
Os endpoints de negócio, persistência, integração e segurança ainda não foram
implementados. O projeto ainda não é a entrega completa do desafio.

## Pré-requisitos

- JDK 21 (`java` e `javac` disponíveis).
- Acesso ao Maven Central na primeira execução, para obter Maven e dependências.
- Não é necessário instalar Maven: use o Wrapper incluído no repositório.

## Executar e testar

A partir da raiz do checkout:

```bash
./mvnw verify
./mvnw spring-boot:run
```

No Windows, utilize `mvnw.cmd` no lugar de `./mvnw`.

O build produz `target/deroyque-test-0.0.1-SNAPSHOT.jar`, que também pode ser
executado com:

```bash
java -jar target/deroyque-test-0.0.1-SNAPSHOT.jar
```

Os testes desta etapa iniciam um servidor HTTP em porta aleatória e verificam
as respostas de liveness e readiness. Com a aplicação em execução na porta
padrão 8080, é possível verificar localmente:

```bash
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
```

Ambos devem retornar HTTP 200 com `{"status":"UP"}`. Nesta etapa, esses
indicadores verificam a aplicação, sem banco ou provedor externo configurados.

## Decisões iniciais

- Java 21, Spring Boot 3.5.16 e Maven 3.9.11 fixados no projeto.
- Spring MVC para HTTP, Bean Validation para a futura validação dos DTOs e
  Actuator para saúde da aplicação.
- JUnit 5 e AssertJ disponibilizados pelo starter de testes do Spring Boot.
- Dependências de persistência, integração e segurança serão adicionadas junto
  das respectivas funcionalidades.

## Próximas etapas

- Domínio, PostgreSQL, Flyway e controle de concorrência.
- Criação, consulta e listagem paginada; identificadores de negócio de 1 a 100
  caracteres; erros padronizados com ProblemDetail.
- Processamento assíncrono, WireMock, idempotência, retentativas e reconciliação.
- OAuth 2.0 Resource Server, JWT e autorização por escopos.
- Dockerfile, Docker Compose, testes adicionais e documentação completa exigida
  pelo enunciado, incluindo análise de produção e limitações conhecidas.
- GitHub Actions, se o tempo permitir.

## Uso de inteligência artificial

O assistente de IA foi utilizado na preparação do ambiente e na geração da
estrutura inicial, do teste de saúde e desta documentação. A revisão conjunta
com o candidato será realizada por etapa. Esta declaração será atualizada para
refletir somente atividades efetivamente realizadas.
