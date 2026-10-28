# Deroyque Test

Serviço de solicitações de ativação de produtos, desenvolvido em etapas para o
desafio Java Pleno/Sênior da Deroyque.

## Estado atual

Etapas 1 a 3: estrutura Spring Boot, domínio, PostgreSQL, migração Flyway,
criação, consulta, listagem paginada, erros padronizados e verificações HTTP de
saúde. O processamento assíncrono, a integração e a segurança ainda não foram
implementados. As solicitações criadas permanecem em `PENDING` nesta etapa.
O projeto ainda não é a entrega completa do desafio.

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
- Spring MVC para HTTP, Bean Validation para validação dos DTOs e
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
- A criação tenta inserir em uma transação própria. Em uma colisão na restrição
  de `external_id`, a transação já terminou e sofreu rollback antes de consultar
  o registro vencedor. Isso evita consultar uma transação abortada pelo
  PostgreSQL. Outras falhas de integridade não são tratadas como duplicidade.
- O relógio UTC é injetado e o instante de criação é truncado para microssegundos,
  alinhando a precisão com PostgreSQL. Assim, a primeira resposta e um retry
  imediato apresentam as mesmas datas.

## API

### Criar uma solicitação

```bash
curl -i -X POST http://localhost:8080/api/v1/requests \
  -H 'Content-Type: application/json' \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'
```

Uma criação nova responde `201 Created`, com `Location: /api/v1/requests/{id}` e:

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "PENDING",
  "createdAt": "2026-10-27T12:00:00Z",
  "updatedAt": "2026-10-27T12:00:00Z"
}
```

O UUID e as datas acima são ilustrativos. DTOs de entrada e saída são separados
da entidade; a versão interna do registro não é exposta na resposta.

| Situação | Resposta |
| --- | --- |
| Novo `externalId` com dados válidos | `201 Created` e `Location` |
| Mesmo `externalId`, mesmo `customerId` e `productCode` | `200 OK`, com o registro e seu estado atual |
| Mesmo `externalId`, `customerId` ou `productCode` diferente | `409 Conflict` |
| Campos ausentes, nulos, em branco ou acima de 100 caracteres após `strip()` | `400 Bad Request` |

Essas comparações usam os identificadores normalizados, preservando diferenças
entre maiúsculas e minúsculas. Requisições idênticas simultâneas resultam em uma
criação e respostas de retry com o mesmo UUID, sem registros adicionais.

### Consultar pelo UUID

```bash
curl -i http://localhost:8080/api/v1/requests/0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90
```

Retorna `200 OK` com a representação atual, `404 Not Found` para UUID inexistente
ou `400 Bad Request` para identificador em formato inválido.

### Listar e filtrar

```bash
curl -i 'http://localhost:8080/api/v1/requests?status=PENDING&customerId=CUST-10001&page=0&size=20'
```

- `status` e `customerId` são filtros opcionais, aplicados em conjunto quando
  ambos estão presentes. Status aceitos: `PENDING`, `PROCESSING`, `COMPLETED`,
  `FAILED`. O filtro de cliente remove espaços das extremidades e exige de
  1 a 100 caracteres quando informado.
- `page`: começa em zero, padrão `0`; `size`: de 1 a 100, padrão `20`.
  Parâmetros inválidos recebem `400`.
- A ordenação é fixa: `createdAt` decrescente, com `id` decrescente para desempate.
- A resposta tem `content`, `page`, `size`, `totalElements` e `totalPages`.
  Uma página além do resultado retorna `200` com `content` vazio e os totais.
- A ordenação é determinística para um mesmo conjunto de dados. A paginação por
  offset não mantém um snapshot entre chamadas se os registros forem alterados.

### Erros e correlação

Os erros usam `application/problem+json`, com `type`, `title`, `status`, `detail`,
`instance` e `traceId`. Falhas de validação acrescentam `errors` com campo e
mensagem, sem incluir o valor rejeitado. Erros inesperados recebem `500` com uma
mensagem genérica, sem stack trace, SQL ou detalhes da exceção na resposta.

Cada resposta inclui `X-Correlation-ID`. Um identificador recebido nesse header
é reutilizado se tiver de 1 a 100 caracteres, começar com letra ou número e
contiver apenas letras ASCII, números, ponto, hífen ou sublinhado. Caso contrário,
a aplicação gera um UUID. O mesmo identificador aparece em `traceId` nos erros e
no contexto dos logs da requisição. Sua propagação ao provedor será adicionada
junto da integração.

## Estratégia de testes atual

- **Unitários:** criação, normalização e limites dos identificadores, estados
  inválidos e preservação das datas, sem iniciar Spring ou banco.
- **Integração:** PostgreSQL real com Testcontainers; Flyway cria o schema e
  Hibernate valida o mapeamento. Os testes verificam gravação/leitura,
  unicidade, quatro inserções concorrentes com exatamente um sucesso e
  rejeição de atualização com versão antiga.
- **HTTP:** liveness e readiness consultados por HTTP em uma aplicação iniciada
  com PostgreSQL de teste.
- **API com PostgreSQL real:** criação e consulta, normalização, limites de
  entrada, retries, conflitos, filtros, paginação e requisições simultâneas.
- **Serviço com Mockito:** resolução da disputa de inserção e distinção entre
  violação de unicidade esperada e outras falhas de integridade.
- **MVC:** JSON inválido, tipo de conteúdo não suportado, erro inesperado sem
  vazamento de detalhes e validação do identificador de correlação.
- Nenhum teste é desabilitado automaticamente quando Docker está indisponível;
  essa condição é uma falha de infraestrutura a corrigir.

## Próximas etapas

- Processamento assíncrono, WireMock, idempotência, retentativas e reconciliação.
- OAuth 2.0 Resource Server, JWT e autorização por escopos.
- Dockerfile, Docker Compose, testes adicionais e documentação completa exigida
  pelo enunciado, incluindo análise de produção e limitações conhecidas.
- GitHub Actions, se o tempo permitir.

## Uso de inteligência artificial

O candidato é responsável pelas decisões do projeto e orienta o desenvolvimento,
incluindo o escopo, as regras de negócio e as escolhas técnicas. O assistente de
IA atua como ferramenta de apoio: esclarece alternativas e consequências,
explica conceitos e implementa código, testes e documentação conforme as
orientações do candidato.

A assistência de IA foi utilizada na preparação do ambiente, na implementação
das funcionalidades, na elaboração e execução de testes e na documentação. Os
resultados são apresentados ao candidato para revisão. As etapas 1 e 2 foram
discutidas e revisadas conceitualmente com o candidato; a revisão da etapa 3 está
em andamento. A declaração será atualizada para refletir somente atividades
efetivamente realizadas, sem afirmar que toda a solução já foi compreendida ou
validada pelo candidato antes da conclusão dessa revisão.
