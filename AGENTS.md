# Codex instructions — postgres-mcp-server

## Current mode: DISCUSS, NOT IMPLEMENT

The user is continuing an architectural conversation about upgrades to https://github.com/Evyuel/postgres-mcp-server. Read `MCP_DEVELOPMENT_PLAN.md` first. It is a **discussion draft**, not an approved specification.

**Until the user explicitly requests implementation, do not edit files, install dependencies, run migrations, commit, or create PRs.** Source inspection, read-only commands and discussion are appropriate. Do not interpret the user's desire to eventually implement all proposed tools as authorization to start coding now.

## Working approach

- Inspect current source code, Gradle files, MCP tool declarations, SQL repositories, config, tests, and README before making code-specific assertions.
- Distinguish user-approved requirements from earlier assistant suggestions and from your own proposals.
- First discuss unresolved criteria: PostgreSQL version support; EXPLAIN defaults/options/SQL parameters; 300-second end-to-end timeouts and cancellation; safety of ANALYZE and DML; access roles; concurrency; response volume; statistics availability; custom type depth; integration tests.
- Recommend concrete trade-offs, ask targeted follow-ups, and record accepted decisions in an updated design before coding.
- Preserve public MCP tool naming/contract compatibility where feasible; flag breaking changes explicitly.
- Use parameterized SQL for values, validate identifiers/options, avoid N+1 catalog queries, and use PostgreSQL system catalogs/views appropriately.
- For diagnostics always plan bounded execution, clear errors, and test database compatibility.
- When authorized to implement, provide tests for custom types, EXPLAIN option combinations, timeout/cancellation, permissions, stats extensions, and MCP contracts. Do not claim code is tested until it has actually been run.

## First response in Codex

Acknowledge the agreed scope (custom types, 8 proposed new MCP tools, EXPLAIN options, up to 300-second queries) and begin by discussing the remaining implementation criteria. Do **not** start implementation by default.
