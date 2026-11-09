# Codex instructions — postgres-mcp-server

Read `MCP_DEVELOPMENT_PLAN.md` before changing the metadata, diagnostics, type or EXPLAIN contracts. It contains the user-approved architecture and security boundaries.

## Working rules

- Preserve the seven original MCP response shapes; additions to them must remain backward compatible.
- New diagnostics tools use the shared structured envelope and sanitized error statuses.
- Never expose table values, MCV/histogram contents, activity/statement SQL, parameters or unsafe database errors.
- Keep EXPLAIN SELECT-only, single-statement, read-only, bounded and cancellable. `ANALYZE` remains opt-in.
- Keep metadata and long-running diagnostics on separate Hikari pools.
- Target PostgreSQL 14.x and run integration tests against PostgreSQL 14.22.
- Use parameterized catalog queries, quote the rare catalog-derived identifier, and avoid per-column catalog N+1 queries.
- Do not change Java 17, Spring Boot 4.1.1 or Spring AI 2.0.1 without a separate user decision.
- Test changes with unit tests and the Testcontainers/MCP transport suite. Do not claim container tests passed when Docker was unavailable.
