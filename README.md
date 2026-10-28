# Deroyque Test

Serviço de solicitações de ativação de produtos, desenvolvido em etapas para o
desafio Java Pleno/Sênior da Deroyque.

## Estado atual

Etapas 1 a 4: estrutura Spring Boot, domínio, PostgreSQL, Flyway, API,
processamento assíncrono durável, provedor simulado com WireMock, retentativas,
reconciliação e verificações de saúde. Segurança e empacotamento Docker da
aplicação ainda serão implementados. O projeto ainda não é a entrega completa
do desafio.

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
| `PROVIDER_BASE_URL` | Endereço do WireMock/provedor; padrão `http://localhost:8081` |

Forneça as credenciais pelo ambiente ou gerenciador de segredos, sem gravá-las
em arquivos versionados. O usuário precisa de permissão para aplicar as
migrações neste ambiente de desenvolvimento. Com o banco e o mock disponíveis:

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
- Fluxo: `PENDING -> PROCESSING -> COMPLETED` ou `FAILED`. Falhas temporárias
  podem retornar a `PENDING`, respeitando a próxima tentativa e o limite.
  Métodos de domínio controlam as transições; não existe setter livre para status.
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
no contexto dos logs da requisição. Ele é persistido na criação e propagado nas
tentativas assíncronas até o provedor, inclusive após reiniciar a aplicação.

## Processamento assíncrono e integração

O POST persiste e confirma a criação sem aguardar o provedor. Uma rotina do
Spring procura pedidos disponíveis no próprio PostgreSQL. Não há fila apenas
em memória nem dependência de um evento disparado depois do commit: um pedido
confirmado continua disponível no banco se a aplicação cair antes de enviá-lo.

1. Em uma transação curta, seleciona um pedido devido com
   `FOR UPDATE SKIP LOCKED`, marca `PROCESSING`, incrementa `attemptCount` e
   atribui um identificador à tentativa. A transação termina antes da chamada HTTP.
2. Chama `POST /provider/v1/activate` com os dados de negócio e os headers
   `Idempotency-Key` e `X-Correlation-ID`.
3. Em outra transação curta, confere o identificador da tentativa e grava o
   resultado. Um resultado de uma tentativa antiga é descartado.

Cada instância tem um processador sequencial e uma rotina de reconciliação em
threads separadas. O ciclo processa até dez itens, reservando cada um apenas
antes de enviá-lo. O lock é liberado antes da chamada remota; o estado durável e
o identificador da tentativa protegem a atualização posterior. Outras instâncias
podem selecionar pedidos diferentes sem aguardar um registro já bloqueado.
`@Version` continua protegendo alterações concorrentes na entidade.

| Retorno do provedor | Resultado | Nova tentativa automática |
| --- | --- | --- |
| Qualquer `2xx` | `COMPLETED` | Não |
| `400` e demais `4xx` permanentes | `FAILED` | Não |
| `429` | `PENDING` enquanto houver tentativas | Sim, respeitando `Retry-After` válido |
| `500`, `502`, `503`, `504` | `PENDING` enquanto houver tentativas | Sim; `503` também respeita `Retry-After` |
| Timeout ou falha de conexão/I/O | `PENDING` enquanto houver tentativas | Sim, com a mesma chave de idempotência |
| Limite de tentativas alcançado | `FAILED` | Não |

Outros status, incluindo redirecionamentos e `501`, são tratados como falha
permanente; o cliente não segue redirecionamentos. Apenas status e headers são
usados: o corpo da resposta do provedor é fechado sem ser armazenado ou exposto.
`lastError` contém apenas códigos sanitizados, como `HTTP_400` ou `TIMEOUT`.
Como a integração é assíncrona, o consumidor acompanha essas falhas consultando
o estado; o POST original não muda retroativamente para uma resposta de erro.

### Tentativas, timeouts e idempotência

São três tentativas **no total**, com esperas mínimas de um segundo após a
primeira falha e dois segundos após a segunda. O banco guarda `nextAttemptAt`;
nenhuma transação fica aberta esperando o backoff. `Retry-After` aceita segundos
ou data HTTP: vale o maior prazo entre esse header e o backoff. Headers inválidos
são ignorados, preservando o backoff. O agendamento pode executar depois do prazo,
conforme a consulta periódica e a carga, mas não deve executar antes dele.

O cliente HTTP Java tem timeout de conexão e um limite para obter a resposta.
Não lê o corpo do provedor, pois a decisão depende apenas do status. Os padrões
são 2 segundos para conexão e 5 segundos para a resposta.

A chave é o SHA-256 do `externalId` normalizado, estável em todas as tentativas.
Hash não é criptografia nem substitui autorização. O contrato esperado do
provedor real deve garantir o mesmo efeito para repetições da chave e rejeitar
conteúdo divergente. Sem essa garantia externa, a aplicação não pode prometer
uma única ativação após um timeout ou queda entre a ativação e o registro local.

`FAILED` após timeout significa que não foi possível confirmar o sucesso dentro
do limite. O produto pode ter sido ativado externamente e exigir consulta ou
reconciliação operacional com o provedor; não há endpoint de reprocessamento
manual nesta entrega.

### Recuperação após interrupção

A rotina procura pedidos em `PROCESSING` há pelo menos 60 segundos, usando
`processingStartedAt`. Se houver orçamento de tentativas, retorna a `PENDING`
com backoff; caso contrário, marca `FAILED`. A tentativa reservada conta no
limite mesmo que a aplicação caia antes de efetuar a chamada. O prazo de
recuperação deve ser maior que os timeouts do cliente; a configuração verifica
que ele excede a soma dos limites de conexão e resposta.

Cada nova tentativa tem um token próprio. Uma resposta atrasada, cujo token já
foi substituído ou invalidado, não altera o pedido. A chave de idempotência do
provedor continua igual. Essa distinção protege o estado local e o efeito remoto.

Em maior escala, consideraríamos fila com entrega pelo menos uma vez, outbox
transacional quando necessário, leases renováveis e reconciliação consultando
o resultado no provedor. Ainda seriam necessárias idempotência externa e
proteção contra resultados antigos; uma fila sozinha não resolve essas questões.

### Configurações do processador

| Variável | Padrão | Função |
| --- | --- | --- |
| `PROCESSING_ENABLED` | `true` | Ativa as rotinas automáticas |
| `PROCESSING_MAX_ATTEMPTS` | `3` | Total de tentativas; configuração aceita de 1 a 10 |
| `PROCESSING_RETRY_DELAY` | `1s` | Base do backoff exponencial |
| `PROCESSING_TIMEOUT` | `60s` | Prazo para considerar uma tentativa abandonada |
| `PROCESSING_POLL_INTERVAL` | `1s` | Intervalo após um ciclo de processamento |
| `PROCESSING_RECOVERY_INTERVAL` | `5s` | Intervalo após um ciclo de recuperação |
| `PROVIDER_CONNECT_TIMEOUT` | `2s` | Limite de conexão HTTP |
| `PROVIDER_READ_TIMEOUT` | `5s` | Limite para obter a resposta HTTP |

## Mock WireMock

O build também gera `target/deroyque-test-0.0.1-SNAPSHOT-provider-mock.jar`, uma
extensão exclusiva do mock, separada do JAR da aplicação. A dependência WireMock
tem escopo de teste e não é incorporada à aplicação de produção.

Depois de `./mvnw verify`, em Linux/macOS, execute o mock em outro terminal:

```bash
java -cp "$HOME/.m2/repository/org/wiremock/wiremock-standalone/3.13.1/wiremock-standalone-3.13.1.jar:target/deroyque-test-0.0.1-SNAPSHOT-provider-mock.jar" \
  wiremock.Run --port 8081 --root-dir mock-provider \
  --extensions br.com.deroyque.activation.support.IdempotentProviderMock
```

Se o repositório Maven local estiver em outro diretório, ajuste o primeiro
caminho do classpath. No Windows, use `;` como separador do classpath.

O mock compara o conteúdo associado à chave, confirma uma ativação apenas uma
vez e devolve sucesso para repetições já ativadas. A mesma chave com conteúdo
diferente recebe `409`. Os seguintes `productCode` controlam cenários locais:

| Produto | Comportamento |
| --- | --- |
| `INTERNET-500` ou outro valor comum | Sucesso imediato |
| `PERMANENT_FAILURE` | HTTP 400 |
| `TEMPORARY_FAILURE` | Duas respostas 500, depois sucesso |
| `RATE_LIMITED` | Primeiro 429 com `Retry-After: 2`, depois sucesso |
| `TIMEOUT_AFTER_ACTIVATION` | Ativa, atrasa a primeira resposta por 6 segundos; repetição recebe sucesso sem reativar |

O estado do mock fica em memória e é perdido ao reiniciá-lo. Em um provedor real,
o registro de idempotência precisa ser durável e ter retenção compatível com o
prazo de retries e reconciliação. As regras especiais de produto pertencem apenas
ao mock; o serviço de ativação não interpreta esses códigos especiais.

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
- **Provedor:** HTTP real contra WireMock, classificação de status, parsing de
  `Retry-After`, falha de conexão, headers e payload.
- **Processamento:** PostgreSQL real e relógio controlado para verificar backoff,
  limite, recuperação, token de tentativa, disputa entre quatro processadores e
  timeout após ativação, sem duplicar o efeito no mock. Um spy verifica que não
  existe transação de banco aberta ao chamar o cliente HTTP real.
- **Agendamento:** teste com rotinas habilitadas e provedor bloqueado confirma
  que o POST responde antes da conclusão e que o trabalho termina em background.
- Os testes anteriores controlam o processador explicitamente, desabilitando
  somente o agendamento automático para manter as verificações determinísticas.
  A rotina automática é executada no teste específico de agendamento.
- Nenhum teste é desabilitado automaticamente quando Docker está indisponível;
  essa condição é uma falha de infraestrutura a corrigir.

## Próximas etapas

- OAuth 2.0 Resource Server, JWT e autorização por escopos.
- Dockerfile, Docker Compose, testes adicionais e documentação completa exigida
  pelo enunciado, incluindo análise de produção e limitações conhecidas.
- GitHub Actions, se o tempo permitir.

## Uso de inteligência artificial

Utilizei inteligência artificial como agente auxiliar na preparação do ambiente,
na implementação de código, na elaboração e execução de testes e na documentação.
Eu defini e aprovei o escopo, as regras de negócio e as decisões técnicas. Usei a
ferramenta para esclarecer dúvidas, discutir alternativas e implementar conforme
minhas orientações.
