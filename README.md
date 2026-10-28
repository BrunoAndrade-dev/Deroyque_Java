# Deroyque Test

Serviço de solicitações de ativação de produtos, desenvolvido em etapas para o
desafio Java Pleno/Sênior da Deroyque.

## Estado atual

Etapas 1 e 2: estrutura Spring Boot, domínio, PostgreSQL, migração Flyway e
verificações HTTP de saúde. Os endpoints de negócio, processamento assíncrono,
integração e segurança ainda não foram implementados. O projeto ainda não é a
entrega completa do desafio.

## Pré-requisitos

- JDK 21 (`java` e `javac` disponíveis).
- Acesso ao Maven Central na primeira execução, para obter Maven e dependências.
- Não é necessário instalar Maven: use o Wrapper incluído no repositório.
- Docker em execução e acessível para os testes com Testcontainers. Os testes
  criam um PostgreSQL 17.6 temporário, sem exigir um banco instalado ou
  credenciais configuradas manualmente. A primeira execução baixa as imagens.
- Para executar a aplicação fora dos testes: PostgreSQL disponível e as
  variáveis de conexão configuradas conforme a seção abaixo.

## Executar e testar

A partir da raiz do checkout:

```bash
./mvnw verify
```

No Windows, utilize `mvnw.cmd` no lugar de `./mvnw`.

Para iniciar a aplicação, disponibilize um banco PostgreSQL vazio e configure
as variáveis no ambiente do processo:

| Variável | Conteúdo |
| --- | --- |
| `DB_URL` | URL JDBC; padrão `jdbc:postgresql://localhost:5432/deroyque` |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |

Forneça as credenciais pelo ambiente ou gerenciador de segredos, sem gravá-las
em arquivos versionados. O usuário precisa de permissão para aplicar as
migrações neste ambiente de desenvolvimento. Com o banco disponível:

```bash
./mvnw spring-boot:run
```

Na inicialização, Flyway aplica as migrações e Hibernate valida o schema. A
aplicação não inicia normalmente se a conexão ou a validação falhar.

O build produz `target/deroyque-test-0.0.1-SNAPSHOT.jar`, que também pode ser
executado com:

```bash
java -jar target/deroyque-test-0.0.1-SNAPSHOT.jar
```

Os testes HTTP iniciam um servidor em porta aleatória. Com a aplicação em
execução na porta padrão 8080, é possível verificar localmente:

```bash
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
```

Ambos devem retornar HTTP 200 com `{"status":"UP"}` em condições normais.
Liveness verifica a aplicação; readiness inclui também a conexão com o banco.
Nenhum dos dois comprova ativação de produtos, que ainda não foi implementada.

## Decisões iniciais

- Java 21, Spring Boot 3.5.16 e Maven 3.9.11 fixados no projeto.
- Spring MVC para HTTP, Bean Validation para a futura validação dos DTOs e
  Actuator para saúde da aplicação.
- JUnit 5 e AssertJ disponibilizados pelo starter de testes do Spring Boot.
- Spring Data JPA/Hibernate para persistência, PostgreSQL e Flyway. O schema é
  definido pelas migrações em `src/main/resources/db/migration`; Hibernate usa
  `ddl-auto=validate`, sem criar ou alterar tabelas automaticamente.
- Entidade e repositório organizados por funcionalidade em `request/domain` e
  `request/persistence`. A entidade não será usada diretamente como DTO da API.

## Domínio e persistência

- UUID interno gerado na criação e estado inicial `PENDING`.
- `externalId`, `customerId` e `productCode`: obrigatórios, de 1 a 100 caracteres
  após remoção de espaços nas extremidades, sem converter maiúsculas/minúsculas.
  Neste estágio, o comprimento é medido por `String.length()` do Java.
- Fluxo atual: `PENDING -> PROCESSING -> COMPLETED` ou `FAILED`. Métodos de
  domínio controlam as transições; não existe setter livre para status.
- `createdAt` permanece imutável. Cada transição atualiza `updatedAt`; ambos
  usam `Instant`, armazenado como `TIMESTAMP WITH TIME ZONE` no PostgreSQL.
  O instante é recebido pelo domínio, permitindo testes com datas fixas.
- Restrição `UNIQUE` global em `external_id`: o banco rejeita inserções
  duplicadas mesmo quando vários processos tentam inserir simultaneamente.
- `@Version` adiciona uma versão ao registro. Uma atualização baseada em uma
  leitura desatualizada falha, em vez de sobrescrever a atualização anterior.
- Índices iniciais `(status, created_at, id)` e `(customer_id, created_at, id)`
  preparam consultas por estado/cliente com ordenação estável por data e UUID.
  A consulta combinada e as consultas do processador serão avaliadas nas
  respectivas etapas; índices adicionais dependerão dos padrões de acesso.
- A proteção do banco já existe. A semântica HTTP de retry idêntico (`200`),
  criação (`201`) e conflito de conteúdo (`409`) será implementada na API.

## Estratégia de testes atual

- **Unitários:** criação, normalização e limites dos identificadores, estados
  inválidos e preservação das datas, sem iniciar Spring ou banco.
- **Integração:** PostgreSQL real com Testcontainers; Flyway cria o schema e
  Hibernate valida o mapeamento. Os testes verificam gravação/leitura,
  unicidade, quatro inserções concorrentes com exatamente um sucesso e
  rejeição de atualização com versão antiga.
- **HTTP:** liveness e readiness consultados por HTTP em uma aplicação iniciada
  com PostgreSQL de teste.
- Nenhum teste é desabilitado automaticamente quando Docker está indisponível;
  essa condição é uma falha de infraestrutura a corrigir.

## Próximas etapas

- Criação, consulta e listagem paginada; identificadores de negócio de 1 a 100
  caracteres; erros padronizados com ProblemDetail.
- Processamento assíncrono, WireMock, idempotência, retentativas e reconciliação.
- OAuth 2.0 Resource Server, JWT e autorização por escopos.
- Dockerfile, Docker Compose, testes adicionais e documentação completa exigida
  pelo enunciado, incluindo análise de produção e limitações conhecidas.
- GitHub Actions, se o tempo permitir.

## Uso de inteligência artificial

O assistente de IA foi utilizado na preparação do ambiente e na geração da
estrutura inicial, do domínio, da persistência, das migrações, dos testes e
desta documentação. A revisão conceitual da etapa 1 foi realizada com o
candidato. A revisão da etapa 2 está pendente. Esta declaração será atualizada
para refletir somente atividades efetivamente realizadas.
