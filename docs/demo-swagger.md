# Demonstração autenticada pelo Swagger

Este guia detalha a **opção 1 do README**: testar o fluxo completo manualmente
sem configurar um emissor OAuth externo. Não precisa criar `.env`, preparar senha
de banco ou iniciar o Compose. JDK 21 e Docker em execução são necessários.
O launcher e o emissor estão exclusivamente em `src/test`: não entram no JAR de
produção nem no artefato do mock. O `.env` e o Compose existentes não são alterados.

A aplicação usa as mesmas classes de produção, validação de JWT, escopos,
isolamento, banco PostgreSQL, migrações e processamento assíncrono. Somente os
endereços e credenciais desta execução apontam para recursos efêmeros de teste.
Não existe bypass de autenticação nem endpoint de token dentro da API.

## 1. Preparar o IntelliJ

Abra o projeto Maven e use JDK 21 no Project SDK, no Maven e na configuração de
execução. O Docker deve estar funcionando e acessível ao seu usuário:

```bash
java -version
javac -version
docker version
```

Você pode manter o Compose na porta 8080 ou pará-lo com `docker compose stop`.
Esta demonstração usa a porta **8090** e um banco temporário independente; não
precisa do Compose nem utiliza seus dados. As demais portas são alocadas automaticamente.

## 2. Iniciar explicitamente a demonstração

No terminal da raiz do projeto:

```bash
./mvnw test-compile spring-boot:test-run \
  -Dspring-boot.run.main-class=br.com.deroyque.activation.demo.SwaggerDemoApplication
```

No PowerShell:

```powershell
.\mvnw.cmd test-compile spring-boot:test-run -Dspring-boot.run.main-class=br.com.deroyque.activation.demo.SwaggerDemoApplication
```

Alternativamente, abra `src/test/java/br/com/deroyque/activation/demo/SwaggerDemoApplication.java`
no IntelliJ e execute seu método `main`. A primeira inicialização pode baixar a
imagem PostgreSQL usada por Testcontainers e dependências Maven. Não use `java -jar`
para esta demonstração: o JAR de produção não contém o launcher ou o emissor.

O console mostrará duas URLs públicas locais, **sem imprimir tokens**:

- Swagger: `http://127.0.0.1:8090/swagger-ui.html`.
- Página de tokens: `http://127.0.0.1:<porta-aleatoria>/`.

Abra as URLs no navegador da mesma máquina que executou o comando. Se a porta
8090 estiver ocupada, libere-a antes de iniciar. O acesso fica vinculado ao loopback;
não publique essas portas, não use dados reais e não use esta execução em produção.

## 3. Autorizar no Swagger

1. Abra a URL da página de tokens mostrada no console.
2. Escolha **Consumidor A — leitura e escrita**.
3. Clique em **Gerar token** e **Copiar token**.
4. Abra o Swagger da **porta 8090**, não o da porta 8080 do Compose.
5. Clique em **Authorize**, cole somente o token e confirme em **Authorize**.
6. Feche o diálogo e execute as operações usando **Try it out → Execute**.

O token dura cinco minutos. Quando expirar, gere outro e substitua a autorização
no Swagger. Um token novo do mesmo consumidor continua acessando seus pedidos
naquela execução. Recarregar a página de tokens limpa o campo; o Swagger também
não persiste a autorização entre recarregamentos. Não salve tokens em arquivos,
histórico de comandos, capturas ou logs. O clipboard é controlado pelo seu sistema;
use uma máquina confiável e limpe-o depois da demonstração.

## 4. Criar e acompanhar um pedido

No `POST /api/v1/requests`, use:

```json
{
  "externalId": "DEMO-001",
  "customerId": "CUST-001",
  "productCode": "INTERNET-500"
}
```

Espere `201`, copie o `id` e consulte em `GET /api/v1/requests/{id}`.
A criação retorna `PENDING`; o resultado deve chegar a `COMPLETED`. O processamento
pode ser rápido demais para observar `PROCESSING` manualmente. Liste os pedidos
em `GET /api/v1/requests`, com `page=0` e `size=20`.

Repita o mesmo POST: deve retornar `200` com o pedido existente. Mude o cliente
mantendo `DEMO-001`: deve retornar `409`.

## 5. Demonstrar o WireMock pelo mesmo fluxo

Crie um `externalId` novo para cada cenário e altere somente `productCode`:

| Produto | Resultado esperado |
| --- | --- |
| `TEMPORARY_FAILURE` | Duas falhas 500; terceira tentativa conclui |
| `PERMANENT_FAILURE` | Falha 400; termina em FAILED |
| `RATE_LIMITED` | Primeiro 429; aguarda Retry-After e conclui |
| `TIMEOUT_AFTER_ACTIVATION` | Primeira resposta atrasa; retry confirma sem reativar |

Consulte os pedidos novamente e observe os logs sanitizados no console. Os nomes
especiais pertencem ao mock; a aplicação decide pelo resultado HTTP. `FAILED`
após timeout não prova que não houve ativação externa.

## 6. Demonstrar segurança e isolamento

- Em **Authorize**, faça **Logout** e execute uma consulta: `401`.
- Gere um token **Consumidor A — somente leitura**, substitua a autorização e
  tente um POST: `403`. As consultas aos pedidos de A continuam permitidas.
- Gere um token **Consumidor B — leitura e escrita** e consulte o id criado por A:
  `404`. A listagem de B não inclui pedidos de A.
- Volte ao token de A para consultar novamente os próprios pedidos.

Use **Logout** antes de trocar a autorização para evitar usar um token anterior.

## 7. Encerrar

No terminal, pressione **Ctrl+C**; no IntelliJ, encerre a execução normalmente.
A aplicação fecha antes dos mocks e do banco. Os dados temporários são removidos;
ao iniciar novamente, haverá banco vazio e nova chave de assinatura. Tokens da
execução anterior não funcionam na nova. Uma interrupção abrupta pode impedir o
desligamento normal; Testcontainers também usa Ryuk para limpeza de recursos.

Para executar os testes automatizados, use o comando habitual:

```bash
./mvnw verify
```

O comando de testes não deixa a demonstração rodando. Uma integração com emissor
OAuth real continua sendo melhoria futura; este helper é exclusivo de demonstração.
