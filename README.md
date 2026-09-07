# PostgreSQL MCP Server

Запускаемый metadata-only MCP-сервер для безопасного изучения структуры PostgreSQL AI-клиентами. Сервер публикует настоящий Model Context Protocol поверх Streamable HTTP и не содержит инструмента выполнения произвольного SQL.

## Стек и совместимость

- Java 17;
- Spring Boot 4.1.1;
- Spring AI 2.0.1;
- MCP Java SDK 2.0.0 (транзитивно через Spring AI BOM);
- Spring MVC, Spring JDBC, HikariCP, PostgreSQL JDBC Driver;
- JUnit 5, AssertJ, Mockito, Testcontainers 2.0.5 / PostgreSQL 16.

Spring AI 2.0.x официально поддерживает Spring Boot 4.0/4.1, а Boot 4.1.1 требует Java 17 или новее. Для WebMVC Streamable HTTP используется официальный артефакт `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`, свойство `spring.ai.mcp.server.protocol=STREAMABLE`; подтверждённый endpoint по умолчанию — `POST /mcp`. См. [Spring AI MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) и [Streamable HTTP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html).

## Архитектура

`PostgresMetadataTools` — тонкий MCP-адаптер с `@McpTool`; `PostgresMetadataService` применяет allowlist, ограничения и собирает DDL; `PostgresCatalogRepository` выполняет только заранее определённые параметризованные запросы к `pg_catalog`; records в `MetadataModels` задают стабильный JSON-контракт. Большие списки возвращаются как `{items, truncated, limit}`.

Это не REST API. `/mcp` принимает MCP JSON-RPC lifecycle и методы `initialize`, `tools/list`, `tools/call`; отдельных HTTP endpoint для tools нет. Обычный `curl GET /list_tables` не поддерживается.

## Сборка и запуск

Требования: JDK 17+ и PostgreSQL 12+; Docker нужен только для интеграционных тестов.

```bash
./gradlew clean test
./gradlew bootJar
java -jar build/libs/postgres-mcp-server-0.1.0-SNAPSHOT.jar
```

Windows:

```powershell
.\gradlew.bat clean test
.\gradlew.bat bootRun
```

Docker:

```bash
docker build -t postgres-mcp-server .
docker run --rm -p 127.0.0.1:8080:8080 --env-file .env postgres-mcp-server
```

## PostgreSQL и конфигурация

Создайте отдельную роль скриптом [`docs/create-metadata-role.sql`](docs/create-metadata-role.sql). Он выдаёт только `CONNECT` к БД и `USAGE` на разрешённую схему — `SELECT` на бизнес-таблицы не выдаётся — и включает `default_transaction_read_only` и `statement_timeout`.

Пример переменных окружения:

```dotenv
APP_HOST=127.0.0.1
APP_PORT=8080
POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/postgres
POSTGRES_USERNAME=postgres_metadata
POSTGRES_PASSWORD=take-this-from-a-secret-manager
DB_POOL_MAX_SIZE=5
DB_POOL_MIN_IDLE=1
DB_CONNECTION_TIMEOUT_MS=5000
METADATA_QUERY_TIMEOUT_SECONDS=10
METADATA_MAX_OBJECTS=500
METADATA_ALLOWED_SCHEMAS=app,reporting
MCP_ENDPOINT=/mcp
```

Эквивалентный фрагмент `application.yml`:

```yaml
server:
  address: 127.0.0.1
  port: 8080
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/postgres
    username: postgres_metadata
    password: ${POSTGRES_PASSWORD}
    hikari:
      maximum-pool-size: 5
      read-only: true
  ai.mcp.server:
    protocol: STREAMABLE
    streamable-http.mcp-endpoint: /mcp
app.metadata:
  query-timeout-seconds: 10
  max-objects: 500
  allowed-schemas: [app, reporting]
```

Пустой `allowed-schemas` разрешает все несистемные схемы, доступные роли. Имена schema/table всегда передаются JDBC-параметрами; quoted identifiers и регистр сохраняются. Пароль и полный JDBC URL не журналируются приложением.

## MCP tools

Сервер регистрирует ровно семь tools:

1. `list_schemas()` — доступные несистемные схемы с владельцем и комментарием.
2. `list_tables(schema)` — таблицы, partitioned/foreign tables, views и materialized views; `estimatedRowCount` берётся из статистики и помечен как приблизительный.
3. `describe_table(schema, table)` — колонки с `format_type`, default/identity/generated, collation, PK, unique/check constraints и partitioning.
4. `get_table_ddl(schema, table)` — объяснительный DDL из каталогов; для views применяется `pg_get_viewdef`, индексы возвращаются отдельно.
5. `list_indexes(schema, table)` — access method, key/expression и INCLUDE части, predicate, flags, размер и `pg_get_indexdef`.
6. `list_foreign_keys(schema, table)` — составные FK с сохранением порядка пар колонок и referential actions.
7. `list_functions(schema)` — функции/процедуры и overload-safe signatures без исходного кода.

Пример сокращённого результата `describe_table`:

```json
{
  "schema": "app",
  "name": "customer",
  "objectType": "TABLE",
  "comment": "Customers",
  "columns": [{
    "name": "id",
    "ordinalPosition": 1,
    "dataType": "bigint",
    "nullable": false,
    "defaultExpression": null,
    "identity": "ALWAYS",
    "generated": null,
    "comment": null,
    "collation": null,
    "primaryKey": true
  }],
  "primaryKey": {"name": "customer_pkey", "columns": ["id"], "definition": "PRIMARY KEY (id)"},
  "uniqueConstraints": [],
  "checkConstraints": [],
  "partitioning": null
}
```

## Подключение клиента

Универсальные параметры для veai или другого клиента:

- transport: **Streamable HTTP**;
- URL: `http://127.0.0.1:8080/mcp`;
- authentication/headers: отсутствуют только для локального режима; для сетевого развертывания задаются вашим gateway;
- клиент обязан поддерживать Streamable HTTP и MCP lifecycle.

Конкретный формат конфигурационного файла veai здесь намеренно не приводится: универсальные параметры следует внести в UI плагина.

Для MCP Inspector:

```bash
npx @modelcontextprotocol/inspector
```

В UI выберите Streamable HTTP и URL `http://127.0.0.1:8080/mcp`, затем выполните Connect, `tools/list` и вызовите `describe_table` с `{"schema":"app","table":"customer"}`. Интеграционный тест `PostgresMcpIntegrationTest` делает ту же проверку официальным Java SDK client, а не обычным REST-вызовом.

## Безопасность

Базовая конфигурация слушает только `127.0.0.1`. Сам MCP starter не добавляет authentication/authorization: аннотация `@McpTool` — регистрация, а не security boundary. Для доступа по сети обязательны TLS и аутентификация/авторизация в reverse proxy, API gateway, Spring Security или совместимом MCP security layer; оставлять `/mcp` публичным нельзя.

Защита в глубину: отдельная роль без DML/DDL, read-only Hikari connections, role-level read-only transaction, allowlist schemas, query timeout, ограничение ответа, только catalog SQL и отсутствие `execute_sql`. Никакие строки бизнес-таблиц не читаются.

## Известные ограничения DDL

Сгенерированный DDL нужен модели для понимания структуры и не заявлен как эквивалент `pg_dump`. Не восстанавливаются grants, owner, comments, triggers, rules, tablespace, storage parameters, replica identity, RLS policies и часть extension-specific свойств. Для foreign tables отдельно нужны server/FDW options. Для дочерней partition возвращается parent/bound в metadata, но CREATE является структурной аппроксимацией. DDL индексов возвращается отдельным массивом; PK index не дублируется.

## Тесты

Testcontainers-схема содержит обычную таблицу, составной PK, FK, unique/check constraints, partial/expression/GIN indexes, view, partitioned table, перегруженные функции и процедуру. Если Docker недоступен, контейнерные тесты пропускаются аннотацией Testcontainers; unit-тесты продолжают работать.

