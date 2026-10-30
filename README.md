# Deroyque Test

Serviço de solicitações de ativação de produtos, desenvolvido em etapas para o
desafio Java Pleno/Sênior da Deroyque.

## Visão geral e decisões técnicas

Defini o fluxo de ativação como assíncrono: a API registra a solicitação e
responde antes da integração externa. O PostgreSQL mantém os pedidos e o trabalho
pendente, permitindo retomar o processamento após interrupções. O `201 Created`
confirma o registro; `COMPLETED` confirma o sucesso informado pelo provedor.

Escolhi Java 21 por ser uma versão LTS e Spring Boot para integrar HTTP,
persistência, segurança e saúde da aplicação. O Maven Wrapper fixa a versão do
build sem exigir uma instalação prévia do Maven. PostgreSQL oferece persistência,
transações e restrições de integridade; Flyway versiona o schema e Hibernate
mapeia as entidades e valida sua compatibilidade com o banco.

Organizei o código por funcionalidade, separando API, aplicação, domínio,
persistência, processamento e cliente externo. Os DTOs definem os contratos de
entrada e saída sem expor campos internos da entidade. Bean Validation verifica
os dados de entrada, o domínio controla as transições, `UNIQUE` garante a
unicidade e `@Version` protege atualizações baseadas em versões desatualizadas.
Cada mecanismo tem uma responsabilidade distinta.

O projeto inclui API paginada, processamento durável, retentativas limitadas,
recuperação de pedidos abandonados, isolamento por consumidor, testes com
PostgreSQL e WireMock, Swagger e execução por Docker. As limitações e evoluções
propostas estão identificadas ao final deste documento.

## Guia de leitura

- [Execução a partir de um clone limpo](#executar-e-testar).
- [Demonstração autenticada pelo Swagger](docs/demo-swagger.md).
- [Tutorial detalhado com Docker Compose](docs/execucao.md).
- [Contrato da API](#api) e [segurança](#segurança).
- [Processamento e integração](#processamento-assíncrono-e-integração).
- [Estratégia de testes](#estratégia-de-testes).
- [Análise de lentidão](#cenário-de-análise-lentidão-em-produção).
- [Execução em produção](#execução-em-produção).
- [Limitações](#pontos-pendentes-e-limitações-conhecidas) e
  [prioridades de evolução](#melhorias-futuras-e-prioridades).

## Tutorial de execução com Docker

Para criar e consultar pedidos manualmente pelo Swagger sem um emissor real,
use a [demonstração autenticada de teste](docs/demo-swagger.md). Ela inicia recursos
temporários independentes, mantém a segurança e não altera o `.env` ou o Compose.
O emissor e o launcher ficam exclusivamente em `src/test`, fora do JAR de produção.

Consulte o [tutorial detalhado de execução](docs/execucao.md). Ele explica o uso
do terminal, pré-requisitos, testes, configuração, inicialização,
consultas, logs, persistência, reinício e solução de problemas.

**Ainda não foi configurado um emissor OAuth 2.0 real.** Os testes funcionam com
um emissor efêmero exclusivo dos testes. A execução manual autenticada pelo Compose
exige configurar um emissor externo; o Compose não cria um servidor de autenticação
nem desabilita a segurança.

Com os metadados públicos do emissor e o arquivo externo de senha configurados
conforme o tutorial:

```bash
docker compose up -d --build --wait --wait-timeout 180
```

O Compose inicia aplicação, PostgreSQL e WireMock. Apenas a API é publicada,
em `127.0.0.1:8080`. Banco e mock são acessados pela rede interna do Compose.
O build usa Maven 3.9.11/JDK 21; a imagem final usa apenas JRE 21, usuário
`10001:10001` e o JAR necessário para cada serviço. O build das imagens compila
os testes para gerar a extensão do mock, mas não os executa: use `./mvnw verify`
separadamente. O banco usa volume persistente; aplicação e mock têm sistema de
arquivos somente leitura e `/tmp` temporário. O desligamento da aplicação é
gracioso, com prazo de 20 segundos por fase do Spring e 45 segundos no Compose.

## Pré-requisitos

- JDK 21 (`java` e `javac` disponíveis).
- Acesso ao Maven Central na primeira execução, para obter Maven e dependências.
- Não é necessário instalar Maven: use o Wrapper incluído no repositório.
- Docker em execução e acessível para os testes com Testcontainers. Os testes
  criam um PostgreSQL 17.6 temporário, sem exigir um banco instalado ou
  credenciais configuradas manualmente. A primeira execução baixa as imagens.
- Para executar a aplicação fora dos testes: PostgreSQL disponível e as
  variáveis de conexão configuradas conforme a seção abaixo.
- Para chamadas autenticadas fora dos testes: um provedor OAuth 2.0 externo,
  com JWTs RS256, issuer, JWK Set público e audiência configurados. Não existe
  servidor de autenticação nem emissor de tokens na aplicação.

## Executar e testar

Com Git, JDK 21 e Docker disponíveis, partindo de um diretório sem o projeto:

```bash
git clone --branch develop https://github.com/BrunoAndrade-dev/Deroyque_Java.git
cd Deroyque_Java
./mvnw verify
```

Se já tiver o checkout, execute apenas `./mvnw verify` na raiz. Esse comando
compila, executa os testes e gera os artefatos. Não exige `.env`, banco instalado
ou emissor OAuth real: os testes preparam seus recursos temporários.

Para experimentar a API manualmente sem emissor real, inicie a demonstração:

```bash
./mvnw test-compile spring-boot:test-run \
  -Dspring-boot.run.main-class=br.com.deroyque.activation.demo.SwaggerDemoApplication
```

Abra o Swagger em `http://127.0.0.1:8090/swagger-ui.html` e a página de tokens na
URL local exibida no console. Gere um token na página e informe-o em **Authorize**,
sem o prefixo `Bearer`. O [guia da demonstração](docs/demo-swagger.md) detalha os
cenários e a execução pelo IntelliJ. `Ctrl+C` encerra os recursos temporários;
os pedidos dessa demonstração não persistem entre execuções.

No Windows, utilize `mvnw.cmd` no lugar de `./mvnw`.

Para iniciar a aplicação, disponibilize um banco PostgreSQL vazio e configure
as variáveis no ambiente do processo:

| Variável | Conteúdo |
| --- | --- |
| `DB_URL` | URL JDBC; padrão `jdbc:postgresql://localhost:5432/deroyque` |
| `DB_USERNAME` | Usuário do banco |
| `DB_PASSWORD` | Senha do banco |
| `PROVIDER_BASE_URL` | Endereço do WireMock/provedor; padrão `http://localhost:8081` |
| `SECURITY_ISSUER_URI` | Emissor confiável dos JWTs; obrigatório |
| `SECURITY_JWK_SET_URI` | Endereço confiável das chaves públicas; obrigatório |
| `SECURITY_AUDIENCE` | Audiência esperada para esta API; obrigatória |
| `PROVIDER_TOKEN_FILE` | Caminho externo do arquivo de credencial do provedor; opcional para o mock |

Forneça as credenciais pelo ambiente ou gerenciador de segredos, sem gravá-las
em arquivos versionados. O usuário precisa de permissão para aplicar as
migrações neste ambiente de desenvolvimento. Configure os três parâmetros de
segurança com os metadados públicos do seu emissor real. Sem esses valores, a
aplicação falha na inicialização; não existe fallback que libere a API.
Os testes configuram automaticamente um JWK Set local e chaves efêmeras, sem
precisar de credenciais reais. Com banco, mock e segurança configurados:

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
Nenhum dos dois comprova que uma ativação específica foi concluída; esse resultado
é acompanhado pelo endpoint da solicitação.

## Tecnologias e documentação da API

- Swagger UI/OpenAPI com springdoc 2.8.17 para documentar os endpoints. No Compose
  local, abra `http://localhost:8080/swagger-ui.html`; o JSON fica em `/v3/api-docs`.
  Use **Try it out → Execute** para enviar uma chamada. **Authorize** aceita um JWT
  válido sem o prefixo `Bearer`; a autorização não é persistida entre recarregamentos.
  A página não emite tokens nem libera operações protegidas. Sem emissor real,
  o diagnóstico do Compose permite explorar a documentação e observar respostas
  401; a demonstração separada em `src/test` permite testar chamadas autenticadas.
  Fora do Compose, a documentação vem desativada: habilite com `SWAGGER_ENABLED=true`.
  O Compose local habilita por padrão; `SWAGGER_ENABLED=false` desativa. Em produção,
  mantenha desativada ou restrinja seu acesso na infraestrutura. Use navegador
  confiável e HTTPS fora de localhost; encerre a autorização ao terminar.

- Java 21, Spring Boot 3.5.16 e Maven 3.9.11 fixados no projeto.
- Spring MVC para HTTP, Bean Validation para validação dos DTOs e
  Actuator para saúde da aplicação.
- JUnit 5 e AssertJ disponibilizados pelo starter de testes do Spring Boot.
- Spring Data JPA/Hibernate para persistência, PostgreSQL e Flyway. O schema é
  definido pelas migrações em `src/main/resources/db/migration`; Hibernate usa
  `ddl-auto=validate`, sem criar ou alterar tabelas automaticamente.
- Entidade e repositório organizados por funcionalidade em `request/domain` e
  `request/persistence`. A entidade não é usada diretamente como DTO da API.

## Domínio e persistência

- UUID interno gerado na criação e estado inicial `PENDING`.
- `externalId`, `customerId` e `productCode`: obrigatórios, de 1 a 100 caracteres
  após remoção de espaços nas extremidades, sem converter maiúsculas/minúsculas.
  O comprimento é medido por `String.length()` do Java.
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
  Índices parciais em `next_attempt_at` para pedidos pendentes e em
  `processing_started_at` para pedidos em processamento apoiam o processador e
  a recuperação. Índices adicionais dependerão dos padrões de acesso medidos.
- A criação tenta inserir em uma transação própria. Em uma colisão na restrição
  de `external_id`, a transação já terminou e sofreu rollback antes de consultar
  o registro vencedor. Isso evita consultar uma transação abortada pelo
  PostgreSQL. Outras falhas de integridade não são tratadas como duplicidade.
- O relógio UTC é injetado e o instante de criação é truncado para microssegundos,
  alinhando a precisão com PostgreSQL. Assim, a primeira resposta e um retry
  imediato apresentam as mesmas datas.

## API

Os endpoints de negócio exigem um access token JWT no header `Authorization`.
Os exemplos abaixo usam Bash e uma função que entrega o header ao curl pela
entrada padrão, evitando colocar o valor do token nos argumentos do processo.
Obtenha o token do seu emissor externo pelo fluxo `client_credentials` e informe
somente o token quando solicitado, sem o prefixo `Bearer`:

```bash
read -r -s -p 'Access token: ' ACCESS_TOKEN
printf '\n'
api_curl() {
  if [[ ! "$ACCESS_TOKEN" =~ ^[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+[.][A-Za-z0-9_-]+$ ]]; then
    printf 'Formato de JWT inválido.\n' >&2
    return 1
  fi
  printf 'header = "Authorization: Bearer %s"\n' "$ACCESS_TOKEN" | curl --config - "$@"
}
```

Use apenas um JWT de três partes, sem quebras de linha ou aspas. Não ative
`set -x`, `curl -v`, `--trace` ou dumps de headers, pois essas ferramentas podem
revelar a credencial. Em produção, use HTTPS; HTTP local destina-se ao
desenvolvimento controlado.

### Criar uma solicitação

```bash
api_curl -i -X POST http://localhost:8080/api/v1/requests \
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
| Mesmo consumidor, mesmo `externalId`, `customerId` e `productCode` | `200 OK`, com o registro e seu estado atual |
| Mesmo `externalId`, `customerId` ou `productCode` diferente | `409 Conflict` |
| `externalId` já vinculado a outro consumidor | `409 Conflict` genérico, sem devolver o registro |
| Campos ausentes, nulos, em branco ou acima de 100 caracteres após `strip()` | `400 Bad Request` |

Essas comparações usam os identificadores normalizados, preservando diferenças
entre maiúsculas e minúsculas. Requisições idênticas simultâneas resultam em uma
criação e respostas de retry com o mesmo UUID, sem registros adicionais.

### Consultar pelo UUID

```bash
api_curl -i http://localhost:8080/api/v1/requests/0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90
```

Retorna `200 OK` com a representação atual, `404 Not Found` para UUID inexistente
ou pertencente a outro consumidor, ou `400 Bad Request` para formato inválido.

### Listar e filtrar

```bash
api_curl -i 'http://localhost:8080/api/v1/requests?status=PENDING&customerId=CUST-10001&page=0&size=20'
```

- `status` e `customerId` são filtros opcionais, aplicados em conjunto quando
  ambos estão presentes. Status aceitos: `PENDING`, `PROCESSING`, `COMPLETED`,
  `FAILED`. O filtro de cliente remove espaços das extremidades e exige de
  1 a 100 caracteres quando informado.
- A consulta e seus totais consideram apenas registros do consumidor autenticado.
- `page`: começa em zero, padrão `0`; `size`: de 1 a 100, padrão `20`.
  Parâmetros inválidos recebem `400`.
- A ordenação é fixa: `createdAt` decrescente, com `id` decrescente para desempate.
- A resposta tem `content`, `page`, `size`, `totalElements` e `totalPages`.
  Uma página além do resultado retorna `200` com `content` vazio e os totais.
- A ordenação é determinística para um mesmo conjunto de dados. A paginação por
  offset não mantém um snapshot entre chamadas se os registros forem alterados.

Ao terminar as chamadas, remova a variável e a função do shell:

```bash
unset ACCESS_TOKEN
unset -f api_curl
```

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

## Segurança

A aplicação é um OAuth 2.0 Resource Server. O sistema consumidor obtém seu
access token no servidor de autorização externo pelo fluxo `client_credentials`,
adequado à comunicação entre sistemas sem usuário humano. As credenciais desse
cliente pertencem ao consumidor e não precisam ser fornecidas à nossa API.
A API verifica o token; não possui endpoint de login, emissão ou renovação.

### Autenticação e autorização

- Assinatura validada com chaves públicas do JWK Set explicitamente configurado.
  Apenas RS256 é aceito; tokens sem assinatura, HMAC ou outros algoritmos são
  rejeitados. Endereços de chaves enviados pelo próprio token não configuram
  a origem de confiança.
- `iss` deve corresponder ao emissor configurado; `aud` deve conter a audiência
  desta API. `sub`, `iat` e `exp` são obrigatórios. A expiração e `nbf`, quando
  presente, são verificados. Uma emissão futura além da tolerância também falha.
  A tolerância de relógio é de 30 segundos; os relógios devem ser sincronizados.
- `POST /api/v1/requests` exige `requests:write`. Os GETs individual e paginado
  exigem `requests:read`. O token pode possuir ambos. Os scopes vêm de `scope`
  ou `scp`; um claim `roles` sozinho não concede essas permissões.
- Token ausente ou inválido recebe `401`. Token válido sem a permissão exigida
  recebe `403`. Ambos usam ProblemDetail, correlação e mensagens genéricas, sem
  detalhes da assinatura, claims ou texto da exceção.
- Os GETs de liveness/readiness são públicos e não expõem detalhes do banco.
  Quando habilitados, os GETs da interface Swagger e de `/v3/api-docs` também
  são públicos; isso não libera os endpoints de negócio. Os demais recursos
  e métodos não autorizados são negados por padrão.
- Além do scope, cada pedido pertence ao consumidor identificado pelo par
  `iss`/`sub` do JWT validado. O vínculo interno é um hash estável desse par;
  não contém nem depende do valor do token. Renovar o JWT preservando essa
  identidade mantém a propriedade. O emissor deve fornecer um `sub` estável e
  diferente para cada sistema consumidor.
- A API define o dono na criação; não aceita atribuição de dono por campos do
  JSON, query parameters ou headers de identidade. Consultas individuais de
  outro consumidor recebem `404`. Listagem, filtros, paginação e totais já são
  restringidos pelo dono na consulta ao banco.
- `externalId` continua globalmente único. Uma repetição legítima só devolve
  `200` ao mesmo consumidor. Outra identidade recebe conflito genérico, inclusive
  se os dados forem iguais ou as inserções acontecerem simultaneamente. Isso não
  revela o conteúdo do pedido, mas a regra global permite inferir que aquele
  identificador está indisponível.
- Registros anteriores à migração V3 não têm dono autenticado conhecido. Recebem
  uma identidade reservada e permanecem inacessíveis pela API, sem serem apagados
  ou atribuídos ao próximo chamador. Havendo dados legados reais, sua vinculação
  precisa de um mapeamento confiável e uma migração administrativa específica.
  Não existe endpoint público para transferir propriedade.

### Proteção de tokens e transporte

O bearer token de entrada é aceito apenas no header `Authorization`: não é
obtido da URL, formulário ou cookie. Não é salvo no banco, nos logs, nos DTOs,
em sessão HTTP, no código ou nos artefatos da aplicação. As respostas possuem
controle de cache e não devolvem o header de autenticação recebido. O handler
de segurança não serializa nem registra mensagens de exceções de autenticação.

A API é stateless, sem formulário de login ou HTTP Basic. CSRF está desabilitado
porque a autenticação não usa cookies/sessão enviados automaticamente pelo
navegador; se esse modelo mudar, essa decisão deve ser revista.

JWT assinado não é criptografado: seus claims podem ser lidos. Não inclua segredos
no conteúdo e proteja o transporte com TLS no ambiente de produção, inclusive
no caminho entre proxy e aplicação conforme o modelo de rede. O emissor e JWK
Set exigem HTTPS; HTTP é permitido apenas em loopback para desenvolvimento.
TLS e validação de certificados não são desabilitados.

Os testes geram chaves privadas e tokens temporários somente na memória, com
JWK Set simulado local. Nenhum emissor de teste ou chave privada é empacotado no
JAR da aplicação ou da extensão do mock. Ainda será necessário configurar e
validar um emissor real para uma implantação externa.

As chaves públicas são armazenadas no cache do decoder e uma nova chave pode
ser buscada quando um `kid` desconhecido aparece. A rotação deve publicar a chave
nova antes de usá-la e manter a anterior durante a validade dos tokens antigos.
A validação local não oferece revogação imediata de JWTs já emitidos; use tokens
de curta duração e considere introspecção/controle de revogação se o contrato
exigir essa capacidade.

### Credencial do provedor e rotação

A credencial de saída é independente do JWT recebido: o token do consumidor
nunca é encaminhado ao provedor. Configure `PROVIDER_TOKEN_FILE` com o caminho
de um arquivo de segredo montado fora do checkout, somente leitura e com acesso
restrito ao usuário da aplicação. Não coloque o conteúdo em variável de build,
Dockerfile, imagem, README ou repositório. O arquivo deve conter apenas a
credencial, sem o prefixo `Bearer`; o cliente constrói o header internamente.

O arquivo é lido a cada chamada. Substitua-o atomicamente ou use um volume de
segredos com atualização atômica para realizar rotação sem reiniciar a aplicação.
Não há cache do valor no serviço de credenciais. O provedor precisa aceitar o
período de transição das credenciais conforme seu contrato.

Arquivo configurado ausente, inválido, vazio ou maior que 8 KiB impede a chamada;
ela não é enviada sem autenticação como fallback. O erro sanitizado
`CREDENTIAL_UNAVAILABLE` segue o orçamento de retentativas. Uma credencial
configurada exige HTTPS fora de loopback; redirecionamentos HTTP não são seguidos.
Sem arquivo configurado, o mock local pode funcionar sem credencial.

Arquivos de segredos, chaves e tokens têm padrões no `.gitignore` como proteção
adicional. Isso não apaga vazamentos anteriores: em caso de exposição, revogue e
rotacione a credencial e trate também o histórico e os registros afetados.
Logs de proxy, infraestrutura e ferramentas externas também devem ocultar
`Authorization`; as verificações da aplicação não substituem essa configuração.

Limitação de taxa e limites adicionais de payload são possíveis evoluções de
segurança; não são garantias implementadas nesta etapa.

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

Considerei `2xx` como confirmação de sucesso e erros permanentes como falhas que
não se resolvem repetindo os mesmos dados. Os status temporários selecionados e
o `429` permitem recuperação após espera; o timeout exige cautela porque não
confirma o resultado remoto. Todas as repetições respeitam o limite de tentativas.

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

## Estratégia de testes

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
- **Segurança:** JWTs assinados de verdade com chaves efêmeras e JWK Set HTTP
  local; assinatura inválida, algoritmos não permitidos, issuer/audience,
  expiração, claims obrigatórios, scopes, 401/403 e rotação de chaves públicas.
  Indisponibilidade HTTP e falha de conexão ao JWK Set também retornam 401
  padronizado quando não é possível validar a chave necessária.
  Testes também verificam ausência de token nas respostas, headers, banco e
  logs capturados. Os testes de erros MVC usam usuário simulado apenas para
  chegar ao controller; os testes de JWT não substituem o decoder real.
- **Isolamento:** dois consumidores com os mesmos scopes, consultas cruzadas,
  filtros e totais, renovação de token, tentativa de atribuir outro dono,
  registros legados sem dono e disputa simultânea por um `externalId` global.
- **Credencial do provedor:** leitura limitada, rejeição de arquivo inválido ou
  ausente, proteção contra injeção de headers, HTTPS e rotação entre chamadas.
- Os testes anteriores controlam o processador explicitamente, desabilitando
  somente o agendamento automático para manter as verificações determinísticas.
  A rotina automática é executada no teste específico de agendamento.
- Nenhum teste é desabilitado automaticamente quando Docker está indisponível;
  essa condição é uma falha de infraestrutura a corrigir.

## Observabilidade

Estão implementados liveness, readiness com verificação do banco, logs com
identificador de correlação e propagação desse identificador até o provedor.
Os resultados da integração são registrados de forma sanitizada. O identificador
ajuda a relacionar os logs; ele não constitui, sozinho, um tracing distribuído.

Swagger permite explorar os endpoints manualmente. WireMock simula o provedor;
os testes automatizados fazem as verificações repetíveis sobre os resultados.
Um teste de `500` não substitui um teste de timeout: no primeiro há uma resposta
de erro, enquanto no segundo o resultado remoto pode ser desconhecido.

Como evolução, priorizaria alertas para aumento persistente de erros/timeouts,
crescimento da fila e idade excessiva dos pedidos pendentes. Definiria limites e
janelas de avaliação com base no volume esperado e nos objetivos de serviço.
Exportação Prometheus, métricas próprias da fila, dashboards, alertas, logs JSON
e tracing OpenTelemetry ainda não estão implementados.

## Cenário de análise: lentidão em produção

O cenário do desafio informa API de **300 ms para 2,5 s**, CPU em **45%**, memória
em **60%**, algumas consultas de **50 ms para 1,2 s** e provedor externo de
**400 ms para 3 s**. Os valores abaixo orientam uma investigação; não são
resultados de um teste de carga executado neste projeto.

### O que investigaria primeiro?

Identificaria os endpoints afetados, o início da degradação, o volume de tráfego,
a taxa de erros e mudanças recentes, como deploys ou alterações de infraestrutura.
Investigaria primeiro as consultas que pioraram, separando execução SQL, bloqueios
e espera por conexão. O aumento medido no banco é uma pista concreta, mas não
explica necessariamente todo o tempo da requisição.

CPU e memória moderadas não descartam gargalos: threads podem estar esperando
conexões, locks ou respostas de rede. O tempo de build e os testes unitários não
medem diretamente esse comportamento de produção.

### Como isolaria aplicação, banco e integração externa?

Mediria separadamente o tempo total por endpoint, o trabalho da aplicação, a
espera por conexão e a execução de cada consulta. No PostgreSQL, examinaria
consultas lentas, transações longas, bloqueios e planos de execução. Usaria
`EXPLAIN` e, quando adequado em ambiente controlado, `EXPLAIN ANALYZE`, que
executa a consulta e exige cuidado com impacto e comandos que alteram dados.

Na integração, mediria latência, erros, timeouts, tentativas e vazão. Como a chamada
é assíncrona e acontece fora da transação, os 3 segundos do provedor não são
somados diretamente ao tempo do POST. Ainda assim, podem aumentar a fila e
pressionar recursos compartilhados, especialmente se houver mais tentativas.
Separaria o tempo de resposta do cadastro do tempo total até concluir a ativação.

### Quais logs e métricas seriam úteis?

- Latência por endpoint e resultado, incluindo p50, p95 e p99, taxa de chamadas
  e de erros. Percentis ajudam a enxergar atrasos ocultos pela média.
- Tempo e frequência das consultas, locks, transações longas, conexões ativas,
  disponíveis e em espera, além de I/O e recursos do banco.
- Latência e taxa de erro/timeout do provedor, tentativas por pedido e resultados.
- Quantidade de pedidos por estado, idade do pendente mais antigo e tempo entre
  criação e conclusão. Cadastro rápido com fila crescente não indica fluxo saudável.
- Threads, pausas de GC, heap, CPU, memória e limites de recursos do contêiner.
- Logs sanitizados correlacionando entrada, reserva, tentativa e resultado, com
  horário, duração e código de falha, sem tokens ou corpos sensíveis.

Os health checks e a correlação já existem. As métricas específicas, sua
exportação, o tracing e os alertas descritos são propostas de evolução.

### Que melhorias consideraria?

A correção dependeria da evidência: criar ou ajustar índices conforme os planos,
reduzir consultas desnecessárias e transações longas, corrigir bloqueios e
avaliar o dimensionamento do pool em conjunto com a capacidade do banco. Aumentar
o pool sem medir pode apenas deslocar ou agravar o gargalo.

Para a integração, manteria timeouts e tentativas limitadas, ajustando a
concorrência à capacidade do provedor. Aumentar chamadas simultâneas contra um
provedor sobrecarregado pode piorar o problema. Consideraria circuit breaker,
jitter e controle de admissão, conforme as medições. Uma fila externa pode ajudar
a organizar a carga, mas não cria capacidade no provedor.

### Os sintomas poderiam ter uma causa comum?

Uma hipótese seria um aumento de tráfego acima da capacidade disponível:
mais chamadas pressionariam o provedor e o banco, e as retentativas ampliariam a
carga. Confirmaria ou descartaria essa hipótese comparando séries temporais de
tráfego, tentativas, conexões, consultas e latências com o início da degradação.
Se o tráfego não explicar, investigaria alterações compartilhadas de rede ou
infraestrutura e compararia instâncias e dependências. Não trataria hipótese como
diagnóstico sem evidências.

### Como validaria a correção após publicar?

Executaria testes de regressão e testes de carga representativos em homologação.
Publicaria de forma gradual, comparando períodos ou grupos com tráfego semelhante.
Verificaria p95/p99 da API, consultas, espera por conexões, erros, timeouts, vazão,
idade da fila e tempo até a ativação. Acompanharia por uma janela suficiente para
observar picos e confirmaria que a melhora não transferiu o problema para outra
dependência. Prepararia reversão caso os indicadores piorassem.

## Execução em produção

O Compose fornecido é um ambiente local com provedor simulado. Não o trataria
como uma infraestrutura de produção pronta. Para uma implantação real:

1. Executaria `./mvnw verify` e construiria a imagem da aplicação com
   `docker build --target application -t deroyque-app:1.0.0 .`. Publicaria
   uma versão identificável em um registro, usando a mesma imagem validada
   nos ambientes seguintes.
2. Disponibilizaria PostgreSQL com armazenamento durável, backups e restauração
   verificada. Planejaria as migrações Flyway antes de receber tráfego, com
   permissões controladas e compatibilidade entre versões durante o deploy.
   Atualmente o Flyway executa na inicialização; separar o migrador exigiria
   configuração própria de implantação.
3. Forneceria `DB_URL`, `DB_USERNAME`, credenciais e os três parâmetros
   `SECURITY_*` com metadados do emissor real. Montaria os segredos fora da imagem
   e do repositório. Para a senha do banco, poderia manter o padrão configtree do
   Compose, montando `spring.datasource.password` e configurando
   `SPRING_CONFIG_IMPORT=configtree:/run/secrets/`.
4. Configuraria `PROVIDER_BASE_URL` para o serviço real HTTPS e
   `PROVIDER_TOKEN_FILE` para o arquivo protegido da credencial. Validaria o
   contrato de idempotência, sua retenção, timeouts, limites e rotação. O WireMock
   e o emissor da demonstração não seriam implantados como serviços reais.
5. Executaria a imagem com usuário não-root, limites de recursos, acesso de rede
   restrito e TLS. Manteria `SWAGGER_ENABLED=false` ou restringiria a documentação
   na infraestrutura. Publicaria somente o acesso necessário à API.
6. Configuraria liveness/readiness, monitoramento, alertas e desligamento gracioso.
   Validaria autenticação e permissões com um token real: health `UP` não confirma
   que o emissor ou o provedor funcionam. Planejaria rollback da aplicação e a
   compatibilidade com migrações, sem presumir que reverter a imagem desfaz o banco.

A URL do provedor é configuração comum e pode variar sem alterar Java; mudanças
nas configurações carregadas no início exigem reinício. A credencial externa tem
tratamento de segredo e é relida a cada chamada. Em cloud, um gerenciador de
segredos pode disponibilizar os arquivos com atualização atômica e acesso mínimo.

## Microsserviços, cloud e CI/CD

### Múltiplas instâncias

As instâncias devem compartilhar o mesmo banco lógico. A restrição global de
`external_id` protege a criação em todas elas. A reserva com
`FOR UPDATE SKIP LOCKED` e a mudança para `PROCESSING` ocorrem na mesma transação;
outras instâncias pulam registros bloqueados. A recuperação e o identificador da
tentativa protegem o estado após interrupções e respostas antigas.

Esses mecanismos não prometem uma única chamada externa em todos os cenários.
Timeouts e recuperação ainda podem provocar repetição; a chave estável e a
idempotência durável do provedor continuam necessárias. Ajustaria a quantidade
de instâncias à capacidade do banco e do provedor, sem escalar apenas pela CPU.

### Eventos de conclusão — proposta futura

Para avisar outros serviços, usaria Outbox transacional: a transição para
`COMPLETED` e um evento pendente seriam gravados na mesma transação. Um publicador
enviaria o evento a uma fila ou tópico e registraria a confirmação, repetindo
em caso de falha. Isso evita perder a intenção de notificar entre o commit e o
publicador, mas admite entregas repetidas.

Cada evento teria identificador único. O consumidor coordenaria sua ação e o
registro desse identificador para reconhecer reentregas sem repetir o efeito.
Comparar apenas o conteúdo não basta, pois eventos legítimos podem ser parecidos.
Mensageria e Outbox não estão implementadas nesta entrega.

### Esteira de CI/CD — proposta futura

Automatizaria checkout, JDK 21 e `./mvnw verify` em um executor com Docker,
incluindo migrações, testes com PostgreSQL e WireMock. Depois construiria e
verificaria a imagem e suas dependências, publicando-a com versão vinculada ao
commit. Não incluiria credenciais em artefatos ou camadas do build.

Implantaria em homologação com configurações e segredos próprios, verificaria
health checks e chamadas autenticadas e promoveria a mesma imagem para produção.
Acompanharia métricas e erros após o deploy, com critérios de aprovação e reversão.
Testes de desempenho seriam uma etapa específica com carga e ambiente comparáveis;
o tempo de resposta do WireMock não mede a velocidade do provedor real.

CI/CD automatiza a entrega da aplicação; mensageria distribui eventos de negócio.
São evoluções distintas e nenhuma delas está implementada atualmente.

## Pontos pendentes e limitações conhecidas

| Item | Situação atual e abordagem proposta |
| --- | --- |
| Emissor OAuth real | A API já valida JWTs, scopes e propriedade; falta integrar e validar um emissor real no ambiente de implantação. Manter a emissão simulada exclusivamente em testes. |
| Provedor real | Integração validada contra WireMock. Confirmar autenticação, idempotência durável, retenção e comportamento no serviço real. O histórico do mock é perdido ao reiniciá-lo. |
| Resultado remoto incerto | `FAILED` após timeout não prova ausência de ativação. Adicionar consulta/reconciliação com o provedor e procedimento operacional; não há reprocessamento manual pela API. |
| Automação de entrega | Não há esteira CI/CD. Criar pipeline com testes, imagem versionada, homologação e promoção monitorada. |
| Observabilidade avançada | Não há exportação Prometheus, métricas próprias da fila, alertas, logs JSON ou OpenTelemetry. Instrumentar conforme os indicadores descritos. |
| Eventos de conclusão | Não há broker, Outbox ou publicador. Implementar persistência transacional do evento e consumo idempotente quando houver consumidores interessados. |
| Proteção contra abuso | Não há rate limiting nem limite global específico de corpo implementado. Definir limites na borda e na aplicação; os limites dos campos não substituem um limite de bytes do corpo. |
| Resiliência adicional | Há backoff, orçamento de tentativas e recuperação; circuit breaker e jitter ainda não existem. Avaliar conforme a carga e o contrato do provedor. |
| Capacidade e arquitetura | Não foi estabelecida capacidade por teste de carga nem implementada verificação com ArchUnit. Medir carga representativa e adicionar regras de dependência se úteis à evolução. |
| Dados e paginação | Listagem por offset não mantém snapshot entre chamadas. Dados legados sem dono exigem migração administrativa confiável. |
| Operação cloud | Infraestrutura, TLS, backups, alertas e implantação gradual são orientações, não recursos cloud provisionados por este repositório. |

## Melhorias futuras e prioridades

Minhas prioridades são integrar e validar um emissor OAuth real no ambiente de
produção e criar uma esteira de CI/CD. A segurança da API já está implementada;
a integração operacional com o emissor ainda precisa ser validada.

Na evolução seguinte, priorizaria métricas e alertas para erros e atrasos de
processamento, reconciliação de resultados incertos e proteção contra abuso.
Para notificar outros serviços, adotaria eventos com Outbox e consumidores
idempotentes. Ajustes de concorrência, índices, circuit breaker, jitter e eventual
fila externa seriam orientados pelas medições, sem adicionar tecnologias apenas
para aumentar a complexidade.

## Uso de inteligência artificial

Utilizei inteligência artificial como agente auxiliar para aumentar a
produtividade na implementação. Defini a arquitetura, as regras de negócio e as
decisões técnicas do projeto, enquanto a ferramenta me auxiliou na implementação
conforme minhas orientações.
