# Tutorial de execução — Deroyque Test

## 1. Onde executar os comandos

Execute os comandos no terminal da máquina em que o repositório e o Docker
estão disponíveis, seja no terminal do sistema ou no terminal integrado do IntelliJ.

`localhost` aponta para a máquina em que o comando ou navegador está sendo
executado. Se a aplicação estiver em uma máquina remota, o acesso pelo navegador
local exige encaminhamento privado de portas ou uma configuração de rede adequada.

Para obter o projeto:

```bash
git clone --branch develop https://github.com/BrunoAndrade-dev/Deroyque_Java.git
cd Deroyque_Java
```

Se já tiver o repositório, abra sua pasta existente. Na raiz, confira:

```bash
pwd
git status --short --branch
```

`pwd` mostra a pasta atual. `git status` permite conferir a branch e as alterações
locais. Os exemplos usam a branch `develop`. Não clone novamente dentro de um
checkout existente.

## 2. O que já pode ser executado sem um emissor OAuth real

Ainda não temos um emissor real configurado. A decisão aprovada foi manter o
emissor simulado **somente nos testes**. Por isso:

- Os testes completos podem ser executados agora, sem tokens ou credenciais reais.
- As imagens Docker podem ser construídas sem um emissor.
- Para chamar os endpoints de negócio manualmente pelo Compose com autenticação,
  será necessário configurar um emissor externo e obter um token válido.
- Para experimentar chamadas autenticadas sem emissor real, use a
  [demonstração pelo Swagger](demo-swagger.md). Ela inicia um emissor efêmero
  exclusivo de `src/test` e recursos temporários separados, mantendo a validação
  de JWT, as permissões e o isolamento por consumidor.

O JAR de produção não possui endpoint de login nem emite tokens. O helper de
emissão existe somente na demonstração de teste, fora desse artefato.
Não substitua os metadados por endereços inventados para considerar a segurança
validada. Uma aplicação com health `UP` não comprova que um emissor está acessível
ou que um JWT será aceito: a obtenção das chaves ocorre quando necessária.

### Diagnóstico local sem emissor

A integração manual com um emissor real permanece como melhoria futura. Para
verificar somente a infraestrutura e a rejeição de acesso anônimo,
use no `.env` os valores abaixo, junto do caminho real de `DB_PASSWORD_FILE`:

```dotenv
SECURITY_ISSUER_URI=https://issuer.invalid
SECURITY_JWK_SET_URI=https://issuer.invalid/jwks
SECURITY_AUDIENCE=deroyque-local-diagnostic
```

O domínio reservado `.invalid` não representa um emissor real. Essa configuração
permite iniciar a aplicação porque ela não busca chaves no startup, mas não gera
tokens nem permite operações autenticadas. Não a use em produção nem considere
um health UP como validação de OAuth.

Com as imagens construídas e o segredo do banco preparado, siga os comandos das
seções 8 e 9: `config --quiet`, `up --no-build --wait --wait-timeout 180` e `ps`.
Os health checks devem retornar 200; consultas sem token devem retornar 401.
Os fluxos autenticados continuam sendo verificados por `./mvnw verify`.
As instruções posteriores que pedem emissor e token reais referem-se à execução
manual autenticada pelo Compose, independente da demonstração de teste.

## 3. Conferir as ferramentas

```bash
docker version
docker compose version
```

O primeiro comando deve mostrar cliente e servidor Docker. Se aparecer apenas
o cliente acompanhado de erro, o daemon está indisponível ou o terminal não tem
acesso ao socket. Verifique se o serviço Docker está ativo e se o usuário possui
as permissões necessárias para acessá-lo. Não torne o socket público.

Use Docker com suporte a BuildKit e Compose v2 com `--wait` e `--wait-timeout`.
Para construir e iniciar pelo Compose, Java e Maven não precisam estar instalados
na máquina hospedeira: o Dockerfile fornece ambos no estágio de build.
O primeiro build precisa acessar os registros de imagens e o Maven Central.

Para rodar os testes com o Wrapper no terminal, confira também:

```bash
java -version
javac -version
```

Ambos devem indicar Java 21. Somente o JRE, sem `javac`, não basta para compilar.
O Wrapper baixa o Maven; você não precisa instalar Maven separadamente.

## 4. Executar os testes agora

Na raiz do projeto:

```bash
./mvnw verify
```

No Windows, use `mvnw.cmd verify`. Os demais exemplos usam Bash em Linux.
O resultado esperado é `BUILD SUCCESS`, sem testes ignorados por falta de Docker.
Os testes sobem PostgreSQL temporário por Testcontainers, criam suas próprias
chaves em memória e simulam o JWK Set. Eles não usam o volume do Compose nem
exigem configurar `.env` ou uma senha de banco manualmente.

O comando também gera o JAR da aplicação e a extensão separada do mock em
`target/`. Os relatórios ficam em `target/surefire-reports/`. Esses arquivos
gerados não devem ser commitados.

No IntelliJ, configure o Project SDK e o JDK do Maven como Java 21. No terminal,
use `JAVA_HOME` e `PATH` da instalação de JDK da sua máquina. Proxy e certificados
são configurações do ambiente; veja o apêndice quando necessário.

## 5. Entender os três serviços

| Serviço | Responsabilidade | Acesso |
| --- | --- | --- |
| `app` | Recebe solicitações, persiste e processa em segundo plano | `127.0.0.1:8080` na máquina do Docker |
| `db` | PostgreSQL, com dados persistidos | Rede interna, nome `db`, porta 5432 |
| `provider-mock` | WireMock que simula as ativações | Rede interna, nome `provider-mock`, porta 8081 |

Dentro do container da aplicação, `localhost` aponta para a própria aplicação.
É por isso que ela usa `jdbc:postgresql://db:5432/deroyque` e
`http://provider-mock:8081`. O Compose resolve os nomes dos serviços.

O banco precisa ficar saudável e o mock precisa responder ao seu health check
antes de o Compose iniciar a aplicação. Flyway cria/atualiza as tabelas; Hibernate
confere o schema. Você não precisa executar SQL manualmente para criar as tabelas.

O mock local não exige credencial. O Compose não monta token do provedor e não
encaminha o JWT do consumidor. Para um provedor real autenticado, mantenha HTTPS
e monte o arquivo externo indicado por `PROVIDER_TOKEN_FILE`, conforme a seção
de segurança do README. Este Compose foi preparado para desenvolvimento com mock.

## 6. Preparar a senha do PostgreSQL fora do repositório

Esta configuração será necessária quando você for iniciar o Compose.
No Bash, escolha uma senha exclusiva deste ambiente e digite-a no prompt oculto:

```bash
install -d -m 700 "$HOME/.config/deroyque"
(
  umask 077
  read -r -s -p 'Nova senha do PostgreSQL local: ' local_db_password
  printf '\n'
  if [ -z "$local_db_password" ]; then
    printf 'A senha não pode ser vazia.\n' >&2
    exit 1
  fi
  printf '%s' "$local_db_password" > "$HOME/.config/deroyque/db-password"
  unset local_db_password
  chmod 444 "$HOME/.config/deroyque/db-password"
)
```

O valor digitado não aparece no comando nem no histórico. Não use `set -x`.
Faça isso uma única vez para um banco novo; não sobrescreva uma senha já usada.
O arquivo fica fora do checkout, em uma pasta acessível apenas ao seu usuário.
O modo `444` permite sua leitura pelo usuário não-root dentro dos containers;
a pasta pai `700` impede outros usuários comuns do host de alcançar o arquivo.
Em produção, use o gerenciador de segredos da plataforma e permissões adequadas.

O Compose monta esse arquivo somente nos serviços que precisam dele. O PostgreSQL
usa `POSTGRES_PASSWORD_FILE`; o Spring lê `spring.datasource.password` por
`configtree`. A senha não é interpolada como variável do container. Segredos de
Compose local são arquivos montados, não um cofre criptografado: quem administra
o Docker ou a máquina tem acesso privilegiado a eles.

## 7. Configurar os metadados do emissor externo

Esta etapa depende de um emissor OAuth 2.0 real, ainda não configurado.
Você precisará obter dele:

- O `issuer` exato presente nos tokens.
- A URL HTTPS do JWK Set de chaves públicas.
- A audiência esperada para esta API.
- Um cliente autorizado a obter access tokens JWT RS256 por `client_credentials`,
  com `requests:write` e/ou `requests:read`.

As credenciais privadas do cliente pertencem ao consumidor e ao emissor. Não
coloque client secret ou chave privada na aplicação de solicitações.
O consumidor precisa ter `sub` estável entre tokens, exclusivo por sistema,
pois o isolamento utiliza a combinação confiável de `issuer` e `sub`.

Crie a configuração local, sem sobrescrever um arquivo já configurado:

```bash
cp -n .env.example .env
```

Edite `.env` com os três metadados públicos reais e o caminho absoluto do arquivo
de senha criado na etapa anterior. Não escreva a senha nesse arquivo.
`DB_PASSWORD_FILE` deve apontar, por exemplo, para
`/home/seu-usuario/.config/deroyque/db-password`, ajustado ao resultado de
`printf '%s\n' "$HOME"`. Não use literalmente esse exemplo e não coloque `$HOME`
no valor do `.env`: informe o caminho absoluto.

O `.env` é ignorado pelo Git. O `.env.example`, versionado, contém apenas nomes
e campos vazios. Não coloque tokens, senhas ou chaves em nenhum dos dois.
Variáveis já exportadas no terminal têm precedência sobre `.env`; confira isso
se uma configuração antiga continuar sendo usada.

O container precisa conseguir acessar a URL de chaves e confiar no certificado
TLS. Se o emissor estiver na sua máquina fora do Docker, `localhost` no container
não aponta para ele. Use um endereço HTTPS acessível e válido para o ambiente.

## 8. Validar, construir e iniciar

Depois de completar a configuração real:

```bash
docker compose config --quiet
docker compose build
docker compose up -d --wait --wait-timeout 180
docker compose ps
```

`config --quiet` confere a configuração sem imprimir seu conteúdo. Variáveis
obrigatórias ausentes causam erro antes de criar containers. `build` compila o
projeto e prepara duas imagens: aplicação e mock. O Dockerfile usa múltiplos
estágios; Maven, fontes de teste e chaves dos testes não entram na imagem final.
O build pula a execução dos testes, por isso a etapa `./mvnw verify` é separada.

`up` cria rede, volume e containers; `-d` deixa os serviços em segundo plano;
`--wait` aguarda saúde; `--wait-timeout 180` limita a espera a três minutos.
O `ps` deve mostrar os três serviços em execução e saudáveis. Para construir e
iniciar em um comando nas próximas vezes:

```bash
docker compose up -d --build --wait --wait-timeout 180
```

A primeira compilação pode demorar conforme a rede e o cache. Proxy corporativo
ou da nuvem exige configuração de Maven e confiança no certificado também dentro
do build; veja o apêndice. Não desative a verificação TLS.

## 9. Verificar a aplicação pelo terminal

```bash
curl --fail-with-body http://localhost:8080/actuator/health/liveness
curl --fail-with-body http://localhost:8080/actuator/health/readiness
curl -i http://localhost:8080/api/v1/requests
```

Os dois primeiros devem devolver `200` e `{"status":"UP"}`. O terceiro deve
devolver `401`, porque não recebeu token. Isso é esperado. Readiness inclui o
banco; não comprova acesso ao emissor nem sucesso do provedor externo.
`/actuator/health` agregado não é público; use as duas URLs completas acima.

### Explorar os endpoints pelo Swagger UI

Para testar criação, consulta e retentativas manualmente com JWT temporário,
consulte a [demonstração separada](demo-swagger.md). Use o Swagger da porta 8090
nessa execução. Os passos abaixo referem-se ao Swagger do Compose, na porta 8080.

Com o Compose iniciado, abra no navegador da mesma máquina:

```text
http://localhost:8080/swagger-ui.html
```

Se mudou `APP_PORT`, ajuste a porta na URL. Expanda uma operação para ver os campos,
exemplos, filtros e respostas. Clique em **Try it out**, preencha os dados e clique
em **Execute**. O resultado aparece na própria página.

No diagnóstico sem emissor, as operações de negócio retornam 401. O Swagger não
emite tokens. No futuro, com um JWT válido, clique em **Authorize** e informe só o
token, sem `Bearer`; a interface acrescenta o header. Escopos e isolamento continuam
valendo. A autorização não é persistida entre recarregamentos; use **Logout** no
diálogo de autorização ao terminar e não compartilhe capturas com credenciais.

O Compose habilita a documentação por padrão. Configure `SWAGGER_ENABLED=false`
para desativar. Fora do Compose, ela vem desativada e exige `SWAGGER_ENABLED=true`.
Após atualizar o código, reconstrua a imagem antes de abrir a página:

```bash
docker compose up -d --build --wait --wait-timeout 180
```

## 10. Criar e acompanhar uma solicitação autenticada

Somente depois de obter um access token real do emissor, use a função `api_curl`
da [seção API do README](../README.md#api). Ela pede o token em um prompt oculto
e o entrega ao curl pela entrada padrão. Não digite o token literalmente no
comando, não o envie no chat e não ative logs de headers.

Com a função carregada no mesmo terminal:

```bash
api_curl -i -X POST http://localhost:8080/api/v1/requests \
  -H 'Content-Type: application/json' \
  -d '{"externalId":"DOCKER-001","customerId":"CUST-001","productCode":"INTERNET-500"}'
```

Espere `201`, um header `Location` e um JSON com `id`. A representação inicial
tem `PENDING`. A ativação ocorre depois, em segundo plano. Copie o `id` retornado
e informe-o quando solicitado:

```bash
read -r -p 'UUID retornado no POST: ' request_id
api_curl -i "http://localhost:8080/api/v1/requests/$request_id"
api_curl -i 'http://localhost:8080/api/v1/requests?page=0&size=20'
```

Com o produto comum, o pedido deve chegar a `COMPLETED`. A consulta requer
`requests:read`. Repetir exatamente o POST com o mesmo consumidor retorna `200`
com o registro existente. Alterar seu conteúdo mantendo `externalId` retorna
`409`. Outro consumidor não pode consultar esse registro: recebe `404` pelo id,
e a listagem mostra somente os pedidos dele.

Para testar o mock, crie novos `externalId` com `TEMPORARY_FAILURE`,
`PERMANENT_FAILURE`, `RATE_LIMITED` ou `TIMEOUT_AFTER_ACTIVATION` no `productCode`.
Os comportamentos e tempos estão na seção WireMock do README. Consulte o estado
novamente após o processamento; um `201` não confirma a conclusão da ativação.

Ao terminar:

```bash
unset ACCESS_TOKEN request_id
unset -f api_curl
```

## 11. Acompanhar logs e identificar problemas

```bash
docker compose logs --tail=100 app
docker compose logs --tail=100 provider-mock
docker compose logs --tail=100 db
docker compose logs -f --tail=50 app
```

`-f` acompanha novas linhas. `Ctrl+C` encerra o acompanhamento, mantendo os
serviços ativos. Os logs da aplicação incluem identificador de correlação e
resultados sanitizados. Não habilite log de tokens, bodies sensíveis ou headers.
Logs de infraestrutura devem receber o mesmo cuidado.

| Sintoma | Verificação |
| --- | --- |
| Erro exigindo `SECURITY_*` | Emissor externo e `.env` ainda não configurados |
| Erro em `DB_PASSWORD_FILE` | Caminho absoluto existente fora do repositório |
| Permissão negada ao ler segredo | Pasta host protegida e arquivo legível pelo usuário do container |
| Porta 8080 ocupada | Configure `APP_PORT=8082` em `.env`, recrie o app e use 8082 no curl |
| `401` com JWT | Assinatura RS256, issuer/aud/sub/exp/iat, horário e acesso HTTPS ao JWK Set |
| `403` | Token válido sem o escopo da operação |
| `404` em consulta | Registro inexistente ou de outro consumidor |
| Banco rejeita a senha | Senha do arquivo diferente da usada ao inicializar o volume |
| Build falha por certificado/proxy | Configuração do build e CA no apêndice |
| `up --wait` falha | Consulte `ps` e logs do serviço; saúde não implica exclusão automática dos containers |

## 12. Parar, retomar e preservar os pedidos

```bash
docker compose stop
docker compose start --wait --wait-timeout 180
```

`stop` solicita desligamento e mantém os containers. `start` retoma os containers
existentes. Para remover containers e rede, preservando os dados do PostgreSQL:

```bash
docker compose down
docker compose up -d --wait --wait-timeout 180
```

O volume `postgres_data`, com o prefixo do projeto Compose, mantém os pedidos.
O mock guarda idempotência em memória e perde esse histórico quando reinicia.
Não reinicie o mock durante processamento supondo que ele imita a durabilidade
de um provedor real. Registros `PROCESSING` abandonados são recuperados pela
aplicação após o prazo configurado, respeitando o orçamento de tentativas.

Após alterar código, reconstrua e recrie a aplicação:

```bash
docker compose up -d --build --wait --wait-timeout 180
```

`restart` sozinho não reconstrói imagens nem aplica mudanças de configuração.
Alterar a senha do arquivo também não muda automaticamente a senha de um banco
já inicializado: isso exige rotação coordenada no PostgreSQL e na aplicação.

**Somente para descartar intencionalmente todos os dados locais:**

```bash
docker compose down --volumes
```

Esse comando apaga o volume do banco deste projeto. Não o use para uma parada
normal ou para resolver um problema sem antes entender o que será perdido.

## Apêndice: build em rede com proxy e CA própria

O Dockerfile aceita dois segredos opcionais de BuildKit: `proxy_ca`, certificado
público da CA, e `maven_settings`, XML de configuração do Maven. São montados
apenas durante o build. O trust store temporário fica em memória e não é copiado
para as imagens finais. Os mesmos passos de compilação funcionam sem esses
segredos em uma rede normal.

Em uma rede que exige proxy, configure esse acesso para o Docker e o Maven.
O Java também precisa confiar na CA utilizada pela rede. Defina `BUILD_CA_FILE`
com o caminho absoluto do certificado público da CA, fora do repositório.
Os comandos abaixo obtêm o proxy da configuração do Docker ou de `HTTPS_PROXY`.

O arquivo temporário de settings usa o repositório local padrão do Maven dentro
do container; não reutilize um settings com caminho local de outra máquina.
Com Python 3 disponível, é possível gerá-lo sem imprimir credenciais:

```bash
build_settings_dir=$(mktemp -d)
chmod 700 "$build_settings_dir"
export DEROYQUE_BUILD_SETTINGS="$build_settings_dir/settings.xml"
python3 - <<'PY'
import json
import os
from pathlib import Path
from urllib.parse import urlparse, unquote
from xml.etree.ElementTree import Element, SubElement, ElementTree

config_path = Path(os.environ.get('DOCKER_CONFIG', str(Path.home() / '.docker'))) / 'config.json'
config = json.loads(config_path.read_text()) if config_path.exists() else {}
defaults = config.get('proxies', {}).get('default', {})
proxy = urlparse(defaults.get('httpsProxy') or defaults.get('httpProxy')
                 or os.environ['HTTPS_PROXY'])
root = Element('settings')
node = SubElement(SubElement(root, 'proxies'), 'proxy')
fields = {'id': 'build-proxy', 'active': 'true', 'protocol': 'http',
          'host': proxy.hostname, 'port': str(proxy.port or 80),
          'nonProxyHosts': 'localhost|127.0.0.1'}
if proxy.username is not None:
    fields['username'] = unquote(proxy.username)
if proxy.password is not None:
    fields['password'] = unquote(proxy.password)
for name, value in fields.items():
    SubElement(node, name).text = value
path = os.environ['DEROYQUE_BUILD_SETTINGS']
descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
with os.fdopen(descriptor, 'wb') as output:
    ElementTree(root).write(output, encoding='utf-8', xml_declaration=True)
PY
docker build --secret "id=proxy_ca,src=${BUILD_CA_FILE:?Defina o caminho do certificado publico da CA}" \
  --secret "id=maven_settings,src=$DEROYQUE_BUILD_SETTINGS" \
  --target application -t deroyque-app:local .
docker build --secret "id=proxy_ca,src=${BUILD_CA_FILE:?Defina o caminho do certificado publico da CA}" \
  --secret "id=maven_settings,src=$DEROYQUE_BUILD_SETTINGS" \
  --target provider-mock -t deroyque-provider-mock:local .
rm -f "$DEROYQUE_BUILD_SETTINGS"
rmdir "$build_settings_dir"
unset DEROYQUE_BUILD_SETTINGS build_settings_dir
```

Se algum build falhar, corrija a causa antes de continuar e remova o settings
temporário ao terminar. Não o envie ao GitHub. Depois dos builds, com os
metadados reais configurados, use `docker compose up -d --no-build --wait
--wait-timeout 180` para reutilizar as imagens e não repetir o build sem os
segredos. Nunca use `--insecure` ou desative a validação de certificados.

Se o emissor real exigir acesso HTTPS através de proxy com CA própria também
em runtime, será preciso montar um trust store externo somente leitura e
configurar o cliente Java para esse ambiente. A CA do ambiente não é incorporada
automaticamente à imagem distribuída. Isso depende do emissor e da rede reais.
