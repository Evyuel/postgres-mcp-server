# PostgreSQL MCP Server — утверждённая спецификация

> Статус: реализовано в ветке расширения metadata/diagnostics. Этот документ фиксирует согласованные критерии, а не черновик обсуждения.

## Совместимость и стек

- Java 17, Spring Boot 4.1.1, Spring AI 2.0.1 — версии не обновляются.
- Поддерживается любой PostgreSQL 14.x (`140000 <= server_version_num < 150000`).
- Интеграционный образ: `postgres:14.22-alpine`.
- Необязательные возможности определяются через capabilities; отсутствие `pg_stat_statements` не мешает запуску.

## Безопасность данных

- MCP не возвращает значения строк таблиц, MCV/histogram contents, SQL из `pg_stat_activity`/`pg_stat_statements`, JDBC parameters, client address, application name или подробности database errors.
- Разрешены row estimates/counts, actual rows, `null_frac`, `n_distinct`, timings, buffer/WAL counters и прочие агрегаты.
- Литералы, которые клиент сам передал в SQL, могут повториться в EXPLAIN JSON.
- Schema allowlist отсутствует. Сервер работает на тестовом стенде с переданными credentials и не создаёт отдельную роль.
- SELECT может вызвать функцию с побочными эффектами; read-only transaction не является абсолютной песочницей. Этот риск явно документирован.

## MCP tools

Сохраняются семь исходных contracts: `list_schemas`, `list_tables`, `describe_table`, `get_table_ddl`, `list_indexes`, `list_foreign_keys`, `list_functions`.

Добавляются восемь tools: `describe_type`, `explain_query`, `get_table_statistics`, `get_column_statistics`, `get_index_statistics`, `get_extended_statistics`, `get_query_statistics`, `get_active_queries`.

Новые tools используют envelope `{status,data,warnings,error,truncated}` со статусами `SUCCESS`, `INVALID_ARGUMENT`, `NON_SELECT_STATEMENT`, `INVALID_EXPLAIN_OPTIONS`, `TIMEOUT`, `CANCELLED`, `CAPACITY_EXCEEDED`, `INSUFFICIENT_PRIVILEGES`, `FEATURE_UNAVAILABLE`, `RESULT_TOO_LARGE`, `DATABASE_ERROR`.

## Пользовательские типы

- ENUM и DOMAIN получают реконструированный structural DDL с отметкой `STRUCTURAL_COMPLETE`.
- COMPOSITE, RANGE и MULTIRANGE получают структурированное описание с отметкой `INFORMATIONAL` и без migration-ready DDL.
- ARRAY рекурсивно раскрывает element type; DOMAIN — base type. Максимальная глубина 4.
- Максимум 1000 enum labels/attributes.
- Owner, grants и comments не входят даже в structural DDL; это отмечается в limitations.
- `describe_table` получает additive `typeDetails`, `get_table_ddl` — топологически упорядоченные `typeDependencies`.

## EXPLAIN

- Только один полностью literalized `SELECT` либо `WITH ... SELECT`; bindings отсутствуют.
- DML, DDL, utility statements, multi-statement и `SELECT INTO` запрещены.
- Используются lexical validation, PreparedStatement/extended protocol, preflight EXPLAIN и проверка modifying plan.
- Выполнение проходит в отдельном Hikari pool (max 2), read-only transaction, `SET LOCAL statement_timeout`, `lock_timeout`, rollback в `finally`.
- Третий одновременный EXPLAIN получает fail-fast `CAPACITY_EXCEEDED`.
- При timeout/interrupt вызывается JDBC `Statement.cancel()`.
- Defaults: `analyze=false`, `buffers=false`, `verbose=true`, `settings=true`, `wal=false`, `timing=false`, `summary=true`, `format=JSON`, `timeoutSeconds=300`.
- WAL/TIMING разрешены только при `analyze=true`; flags не включаются автоматически.
- Max SQL 2 MiB UTF-8, plan JSON 16 MiB, полный response 20 MiB. Частичный JSON plan не возвращается.

## Статистика

- Table: sizes, estimates, scan/tuple/vacuum/analyze counters, reset time, partitions; `includePartitions=true`, max 500.
- Column: `null_frac`, average width, `n_distinct` с интерпретацией, correlation, target, inherited; без value arrays.
- Index: usage, tuple counters, size, flags, AM, definition, reset time; нулевой scan не является рекомендацией удалить индекс.
- Extended: columns/expressions, kinds, dependencies, ndistinct и только boolean наличия MCV.
- Query: IDs, top-level, calls/plans/timings/rows, block/WAL/JIT metrics; SQL text исключён. Default limit 50, max 500.
- Active: PID/DB/user/state/durations/waits/backend type/blockers; SQL/client details исключены. `minDurationMs=1000`, `includeIdle=true`, `includeOwn=false`.
- Списки могут быть частично возвращены с `truncated=true`; EXPLAIN plan — только целиком.

## Проверка

- Unit tests проверяют SQL lexical policy и metadata validation.
- Testcontainers PostgreSQL 14.22 проверяет типы, partitions, statistics, `pg_stat_statements`, EXPLAIN defaults/rejections/timeout и отсутствие business values.
- Все 15 tools перечисляются и вызываются через настоящий Streamable HTTP MCP client.
- Отсутствие Docker не должно молча пропускать интеграционные тесты.
