# PostgreSQL MCP Server — контекст обсуждения и проектирование доработок

> **Статус: DISCUSSION DRAFT, НЕ утверждённое ТЗ.**
>
> Этот документ передаёт контекст разговора из ChatGPT в Codex. **Не приступай к реализации, рефакторингу или коммитам, пока пользователь явно не попросит.** Сначала изучи актуальный исходный код и обсуди с пользователем открытые критерии и технические компромиссы. Любые предложенные ниже интерфейсы и значения настроек — предмет обсуждения, если не помечены как согласованные.

## 1. Репозиторий и цель

- Репозиторий: https://github.com/Evyuel/postgres-mcp-server
- Язык/стек по предшествующему обсуждению: Java, Spring Boot, Spring AI MCP, Spring JDBC/HikariCP, PostgreSQL. **Проверь реальные версии, классы и структуру в текущем checkout; не считай эти сведения проверенным снимком кода.**
- Текущая задача: расширить возможности MCP-сервера для исследования PostgreSQL (включая пользовательские типы колонок), диагностики и оптимизации SQL-запросов.
- Сценарий: AI-агент вызывает MCP tools, получает полезные метаданные, статистику и планы выполнения, затем формирует обоснованные рекомендации по оптимизации.

## 2. Уже согласованные требования пользователя

1. **Среда диагностики — тестовый стенд PostgreSQL.** Риски блокировок при `EXPLAIN ANALYZE` не являются главным ограничением; при этом стоит обсудить защиту данных и предсказуемость работы.
2. **Тяжёлые SQL-запросы могут выполняться до пяти минут (300 секунд).** Нужно спроектировать согласованный timeout для PostgreSQL, JDBC, MCP-клиента/transport, прокси и конкурентных вызовов.
3. Инструмент EXPLAIN должен поддерживать параметры **`ANALYZE`, `BUFFERS`, `VERBOSE`, `SETTINGS`, `WAL`, `TIMING`, `SUMMARY`**. Требуется определить совместимость комбинаций с конкретными версиями PostgreSQL, дефолты и валидацию.
4. Пользователь хочет **сразу реализовать весь обсуждавшийся набор новых tools**, а не только `explain_query`.
5. Нужна информация о **пользовательских типах колонок**, в первую очередь ENUM; обсуждались DOMAIN, ARRAY, COMPOSITE, RANGE, MULTIRANGE.
6. Пользователь **хочет ДО реализации дополнительно обсудить критерии**. Именно это ближайшая задача Codex, а не немедленное написание кода.

## 3. Предварительно обсуждённый список tools

В предшествующей беседе упоминались существующие tools (проверить по реальному коду):

- `list_schemas`
- `list_tables`
- `describe_table`
- `get_table_ddl`
- `list_indexes`
- `list_foreign_keys`
- `list_functions`

Новые tools — **все входят в целевой объём**, но сигнатуры и контракты ещё обсуждаются:

| Tool | Назначение | Предварительный ввод / выход |
| --- | --- | --- |
| `describe_type` | Описать пользовательский тип | `schema`, `type` → тип, ENUM labels, DOMAIN constraints, состав и т. п. |
| `explain_query` | Получить EXPLAIN с настраиваемыми опциями | `sql`, опции, `format`, `timeoutSeconds` → план, метаданные, ошибки |
| `get_table_statistics` | Характеристики и статистика таблицы | `schema`, `table` → размеры, estimated rows, scans, analyze/vacuum |
| `get_column_statistics` | Статистика колонок | `schema`, `table`, `column?` → null_frac, n_distinct, MCV, histogram, correlation |
| `get_query_statistics` | `pg_stat_statements` | Фильтры/сортировка/лимит → calls, exec time, buffers/IO и т. д. |
| `get_index_statistics` | Статистика использования индексов | `schema`, `table` → idx_scan, idx_tup_read/fetch, размер, состояние |
| `get_extended_statistics` | Расширенная статистика | `schema`, `table` → dependencies, ndistinct, MCV / expressions |
| `get_active_queries` | Активные запросы и блокировки | Продолжительность/лимит/фильтры → activity, waits, blocking PIDs |

Итого **7 предполагаемых существующих + 8 новых = 15 tools**. Проверить количество по коду, прежде чем фиксировать окончательно.

Также предполагаются изменения существующих `describe_table` (поле `typeDetails`) и `get_table_ddl` (информация о зависимостях от пользовательских типов).

## 4. Пользовательские типы PostgreSQL

**Желаемый эффект:** AI должен понимать допустимые значения ENUM и смысл пользовательских типов, а не только получать строковое `dataType`.

Пример обсуждавшегося контракта:

```json
{
  "name": "status",
  "dataType": "app.order_status",
  "nullable": false,
  "typeDetails": {
    "schema": "app",
    "name": "order_status",
    "kind": "ENUM",
    "enumValues": ["NEW", "PROCESSING", "COMPLETED", "CANCELLED"]
  }
}
```

Технические ориентиры: `pg_type`, `pg_enum`, `pg_namespace`, `pg_attribute`, `pg_constraint`, `format_type(...)`; использовать OID типов и избегать N+1 запросов на колонку. Учитывать вложенные DOMAIN и массивы ENUM, schema-qualified names, quoted identifiers, атрибуты и ограничения. Сохранять обратную совместимость ответа, если это реально возможно в текущем контракте.

**Обсудить:** глубину рекурсивного раскрытия типов, лимит элементов, репрезентацию DOMAIN CHECK/DEFAULT/NOT NULL, COMPOSITE, RANGE/MULTIRANGE, вывод type DDL и зависимостей в `get_table_ddl`.

## 5. EXPLAIN: обсуждённый интерфейс (НЕ зафиксированный контракт)

```java
explainQuery(
    String sql,
    Boolean analyze,
    Boolean buffers,
    Boolean verbose,
    Boolean settings,
    Boolean wal,
    Boolean timing,
    Boolean summary,
    String format,
    Integer timeoutSeconds
)
```

Предыдущая рекомендация по дефолтам: `analyze=true`, `buffers=true`, `verbose=true`, `settings=true`, `wal=false`, `timing=true`, `summary=true`, `format=JSON`, `timeoutSeconds=300`. **Это предложение ассистента, а не явное утверждение всех дефолтов пользователем.** Обсудить явное `analyze` (опасность реального исполнения), цену `TIMING`, размер выдачи, поддержку `TEXT`/`JSON`, допустимость `WAL` и поведение несовместимых опций. Не менять флаги молча без решения пользователя.

Пример нужного синтаксиса:

```sql
EXPLAIN (
    ANALYZE TRUE,
    BUFFERS TRUE,
    VERBOSE TRUE,
    SETTINGS TRUE,
    WAL FALSE,
    TIMING TRUE,
    SUMMARY TRUE,
    FORMAT JSON
)
SELECT ...;
```

**Особый пункт:** `EXPLAIN ANALYZE` действительно выполняет анализируемый SQL. Нужно обсудить поддержку DML, откат изменений, запрет нескольких команд, порядок контроля функций с побочными эффектами, execution role, allowlist, и следует ли поддерживать только SELECT. Read-only транзакции несовместимы с анализом части DML; rollback не является универсальной защитой от побочных эффектов (например, sequence/external functions).

## 6. Таймауты и нагрузка — предложено ранее, согласовать детали

- Верхний предел выполнения SQL: **300 секунд — согласовано**.
- Возможная схема: `SET LOCAL statement_timeout` внутри транзакции; `lock_timeout`, JDBC timeout, внешний timeout MCP/HTTP/прокси, правильное закрытие/откат при ошибке или отмене.
- Разделить timeout метаданных и тяжёлой диагностики, не увеличивать глобально metadata timeout до 300 секунд.
- По прежним предложениям: `maxConcurrentAnalysis=2`, `maxSqlLength=65536`, `maxPlanBytes=5MiB`, metadata/statistics timeout ~15 секунд, отдельный `lock_timeout` ~10 секунд. **Всё, кроме предела 300 секунд, требует согласования.**
- Обсудить очередность при исчерпании concurrency (немедленный отказ или ожидание), отмену при исчезновении MCP-клиента, поведение пула Hikari, потоков Java, PostgreSQL sessions, `SET LOCAL` и внешних таймаутов.
- Тесты таймаута с малым значением (например, `pg_sleep(3)` при лимите 1 с), не 5-минутные тесты.

## 7. Данные и статистика

- `get_table_statistics`: `pg_class`, `pg_stat_user_tables`, `pg_statio_user_tables`, размеры; аккуратно обрабатывать partitioned tables и секции.
- `get_column_statistics`: `pg_stats`, `null_frac`, `n_distinct`, `most_common_vals`, `most_common_freqs`, `histogram_bounds`, `correlation`; отрицательное `n_distinct` — оценочная доля строк.
- `get_index_statistics`: `pg_stat_user_indexes`, `pg_index`, `pg_class`, `pg_get_indexdef`; не рекомендовать удалять индекс только по нулевому `idx_scan`.
- `get_extended_statistics`: `pg_stats_ext`, `pg_stats_ext_exprs` с учётом версии PostgreSQL.
- `get_query_statistics`: `pg_stat_statements`, выявлять отсутствие расширения и необходимые права, учитывать статистику по `dbid/userid/queryid/toplevel` и различие версий схемы представления.
- `get_active_queries`: `pg_stat_activity`, `pg_blocking_pids`, wait events, `query_start` vs `xact_start`, опционально idle in transaction, исключение собственного запроса.
- `pg_stats` и SQL из `pg_stat_statements` могут раскрывать реальные данные: определить, какие поля и в каком объёме можно передавать AI.

## 8. Предварительные архитектурные ориентиры

- Не превращать metadata-сервис в монолит диагностики: отдельные tools/service/repository/policy/DTO для diagnostics.
- Нужна централизованная политика исполнения (`QueryExecutionPolicy` или эквивалент).
- Результаты MCP — структурированные, с явными статусами: timeout, insufficient privileges, feature unavailable, invalid SQL, capacity exceeded.
- Ограничивать размер выходных данных, при необходимости отдавать краткую сводку + план (решение нужно согласовать).
- Изолировать SQL пользовательского запроса от конструируемых опций EXPLAIN; использовать parameter binding там, где возможно, и строго валидировать идентификаторы и опции.
- Учитывать особенности SQL-валидатора PostgreSQL: проверка `startsWith("select")` недостаточна.
- Для долгих операций продумать MCP progress/cancellation (проверить, поддерживает ли их текущий transport/SDK и клиент).

## 9. Критерии, которые надо дообсудить СЕЙЧАС (до реализации)

Codex должен обсудить с пользователем, приоритетно и предметно:

1. **Поддерживаемые версии PostgreSQL**: одна версия тестового стенда или диапазон, особенности EXPLAIN и статистических системных views.
2. **Граница безопасности**: SELECT-only vs DML, execution read-only vs отдельная роль, произвольные функции, мультистейтментность.
3. **EXPLAIN UX**: дефолты опций; FORMAT JSON/TEXT; опциональный `ANALYZE`; compatibility validation и поведение на ошибках; параметры SQL (`$1`) и способы передачи bindings.
4. **Таймауты и отмена**: 300 секунд только PostgreSQL statement или end-to-end; внешний MCP-клиент и прокси, отмена при disconnect; concurrency и очередь.
5. **Форма ответа и стоимость контекста AI**: полный plan JSON, короткая сводка, truncation, пагинация/сохранение больших результатов; максимальная длина текста запросов и MCV.
6. **Права и изоляция**: одна роль/пул или отдельные metadata/diagnostics connections; доступ к `pg_stat_statements`, `pg_read_all_stats`.
7. **Детализация типов**: какие kind обязательны в первой версии, глубина рекурсии и DDL зависимых типов.
8. **Модель статистики**: секционированные таблицы, схемы, сортировки, лимиты, неполная статистика, расширения.
9. **Error contract**: SQLSTATE, partial results, timeout, unavailable feature; диагностика по сообщениям AI.
10. **Тестирование**: Testcontainers, миграции, fixtures, интеграционные тесты и совместимость MCP tools.

Попроси пользователя выбрать решения там, где действительно есть варианты; не засыпай сразу всеми вопросами. Рекомендуется начать с 3–4 критичных архитектурных развилок, предложив разумные defaults, затем продолжить. Подготовить утверждаемый дизайн/план и **только после явной команды пользователя реализовать его**.

## 10. Инструкция Codex на первом ответе

1. Открой и фактически проанализируй текущий репозиторий и документацию; сообщи, что подтверждено кодом, а что было лишь из предыдущего разговора.
2. Сверь перечень tools и существующие классы, ограничения и тесты.
3. Кратко зафиксируй согласованные пункты и перечисли основные нерешённые критерии, с рекомендацией и обоснованием.
4. **Продолжай обсуждение, не изменяя файлы**. После согласования предложи финальное ТЗ и этапы внутри одного объёма реализации.
