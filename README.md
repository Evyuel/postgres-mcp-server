# PostgreSQL MCP Server

Запускаемый MCP-сервер для изучения структуры и диагностики PostgreSQL 14.x AI-клиентами. Сервер публикует Model Context Protocol поверх Streamable HTTP, возвращает только метаданные и агрегированные показатели и умеет строить `EXPLAIN` только для одного `SELECT`.

## Стек и совместимость

- Java 17;
- Spring Boot 4.1.1;
- Spring AI 2.0.1;
- MCP Java SDK 2.0.0 (транзитивно через Spring AI BOM);
- Spring MVC, Spring JDBC, HikariCP, PostgreSQL JDBC Driver;
- JUnit 5, AssertJ, Mockito, Testcontainers 2.0.5 / PostgreSQL 14.22.

Поддерживается любой сервер PostgreSQL `14.x` (`server_version_num >= 140000 && < 150000`). Основной интеграционный образ — `postgres:14.22-alpine`. Версии Spring Boot 4.1.1, Spring AI 2.0.1 и Java 17 намеренно сохранены.

Spring AI 2.0.x официально поддерживает Spring Boot 4.0/4.1, а Boot 4.1.1 требует Java 17 или новее. Для WebMVC Streamable HTTP используется официальный артефакт `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`, свойство `spring.ai.mcp.server.protocol=STREAMABLE`; подтверждённый endpoint по умолчанию — `POST /mcp`. См. [Spring AI MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) и [Streamable HTTP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html).

## Архитектура

`PostgresMetadataTools` — тонкий MCP-адаптер с `@McpTool`. Catalog metadata, типы, статистика и EXPLAIN разделены на самостоятельные services/repositories. Короткие catalog-запросы используют metadata Hikari pool, а EXPLAIN — отдельный read-only diagnostics pool максимум на два соединения. Большие списки ограничены и явно помечаются `truncated`.

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

Сервер не создаёт и не настраивает роль PostgreSQL: используются переданные credentials тестового стенда. Для `EXPLAIN ANALYZE` у роли должен быть `SELECT` на затрагиваемые объекты, для глобальной статистики могут потребоваться дополнительные права PostgreSQL.

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
DIAGNOSTICS_MAX_CONCURRENT=2
DIAGNOSTICS_MAX_TIMEOUT_SECONDS=300
DIAGNOSTICS_MAX_SQL_BYTES=2097152
DIAGNOSTICS_MAX_PLAN_BYTES=16777216
DIAGNOSTICS_MAX_RESPONSE_BYTES=20971520
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
app.diagnostics:
  max-concurrent: 2
  max-timeout-seconds: 300
```

Сервер показывает все несистемные схемы, доступные текущей роли. Имена schema/table передаются JDBC-параметрами; quoted identifiers и регистр сохраняются. Пароль, JDBC URL, SQL из `pg_stat_statements` и `pg_stat_activity` не журналируются и не возвращаются.

## MCP tools

Сервер регистрирует 15 tools. Семь исходных контрактов сохранены:

1. `list_schemas()` — доступные несистемные схемы с владельцем и комментарием.
2. `list_tables(schema)` — таблицы, partitioned/foreign tables, views и materialized views; `estimatedRowCount` берётся из статистики и помечен как приблизительный.
3. `describe_table(schema, table)` — колонки с `format_type`, default/identity/generated, collation, PK, unique/check constraints и partitioning.
4. `get_table_ddl(schema, table)` — объяснительный DDL из каталогов; для views применяется `pg_get_viewdef`, индексы возвращаются отдельно.
5. `list_indexes(schema, table)` — access method, key/expression и INCLUDE части, predicate, flags, размер и `pg_get_indexdef`.
6. `list_foreign_keys(schema, table)` — составные FK с сохранением порядка пар колонок и referential actions.
7. `list_functions(schema)` — функции/процедуры и overload-safe signatures без исходного кода.

Восемь диагностических tools возвращают envelope `{status,data,warnings,error,truncated}`:

8. `describe_type(schema,type)` — ENUM и DOMAIN с `STRUCTURAL_COMPLETE` DDL; COMPOSITE, RANGE и MULTIRANGE как `INFORMATIONAL` описание; ARRAY раскрывает элемент до глубины 4.
9. `explain_query(sql,...)` — JSON EXPLAIN одного полностью заполненного SELECT. `analyze=false`, `buffers=false`, `verbose=true`, `settings=true`, `wal=false`, `timing=false`, `summary=true`, timeout 300 секунд.
10. `get_table_statistics(schema,table,includePartitions?)` — размеры, оценки строк, scans, tuple/vacuum/analyze counters и секции.
11. `get_column_statistics(schema,table,column?)` — `null_frac`, `avg_width`, `n_distinct`, correlation и statistics target без MCV/histogram values.
12. `get_index_statistics(schema,table)` — usage counters, размер, flags и определение индекса.
13. `get_extended_statistics(schema,table)` — kinds, ndistinct и dependencies; MCV contents исключены.
14. `get_query_statistics(...)` — агрегаты `pg_stat_statements` без SQL-текста и параметров.
15. `get_active_queries(...)` — длительности, состояния, waits и blocking PIDs без SQL, параметров, client address и application name.

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

EXPLAIN принимает максимум 2 MiB UTF-8 SQL, запрещает несколько statements, DML, DDL, utility commands и `SELECT INTO`, выполняет preflight plan и работает в read-only транзакции с rollback. План ограничен 16 MiB, полный ответ — 20 MiB; третий одновременный EXPLAIN немедленно получает `CAPACITY_EXCEEDED`. При timeout/disconnect сервер вызывает JDBC cancellation.

`ANALYZE=true` действительно выполняет SELECT. Read-only транзакция запрещает обычный DML, но PostgreSQL не является абсолютной песочницей: SELECT может вызвать пользовательскую функцию с внешними либо иными побочными эффектами. Этот риск принят для тестовых стендов; сервер не создаёт отдельную ограниченную роль.

В статистических ответах разрешены только агрегаты и служебные показатели. Реальные значения таблиц, MCV, histogram bounds, SQL из activity/statements, параметры и небезопасные database error details не возвращаются. Литералы, которые клиент сам передал в `explain_query`, могут повториться в плане PostgreSQL.

## Известные ограничения DDL

Сгенерированный DDL нужен модели для понимания структуры и не заявлен как эквивалент `pg_dump`. Не восстанавливаются grants, owner, comments, triggers, rules, tablespace, storage parameters, replica identity, RLS policies и часть extension-specific свойств. Для foreign tables отдельно нужны server/FDW options. Для дочерней partition возвращается parent/bound в metadata, но CREATE является структурной аппроксимацией. DDL индексов возвращается отдельным массивом; PK index не дублируется.

## Тесты

Testcontainers-схема содержит обычные и partitioned tables, ключи и индексы, ENUM, DOMAIN, COMPOSITE, RANGE, ARRAY, extended statistics и `pg_stat_statements`. Проверяются все 15 tools через официальный MCP Java client, EXPLAIN options/SELECT-only/timeout, отсутствие бизнес-значений и type DDL. Контейнерные тесты требуют Docker и не пропускаются молча.

