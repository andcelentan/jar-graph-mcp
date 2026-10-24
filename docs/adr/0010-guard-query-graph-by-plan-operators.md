# 10. Guard query_graph by plan operators, not only by query type

Date: 2026-10-24

## Status

Accepted. Amends decision 5 of [ADR-0009](0009-mcp-server-with-spring-ai.md).

## Context

[ADR-0009](0009-mcp-server-with-spring-ai.md) made `query_graph` read-only by two independent checks: the query is
first planned with `EXPLAIN` and rejected unless Neo4j classifies it as read-only, then it runs in a read transaction.

Before implementing it, the classification was checked on Neo4j 5.26 for the queries the tool must reject. Writes and
schema changes are classified as expected (`READ_WRITE`, `WRITE_ONLY`, `SCHEMA_WRITE`), and the read transaction
refuses them anyway. But several dangerous queries are classified `READ_ONLY`, and the read transaction does not stop
them either, since they write nothing to the graph. Run in a read transaction, the procedure calls and the `SHOW` and
`TERMINATE` commands below completed without error; `LOAD CSV` of `file:///etc/passwd` was attempted, and failed only
because Neo4j confines `file:` URLs to its import directory by default (an `https:` URL would have been fetched):

| Query | `queryType` | Plan operator | Risk |
|-------|-------------|---------------|------|
| `LOAD CSV FROM 'file:///…'` / `'https://…'` | `READ_ONLY` | `LoadCSV` | the database reads local files or calls arbitrary URLs (server-side request forgery) |
| `CALL db.labels()` and other procedures | `READ_ONLY` | `ProcedureCall` | code beyond Cypher; the set of procedures depends on version and plugins |
| `SHOW TRANSACTIONS`, `SHOW SETTINGS`… | `READ_ONLY` | `ShowTransactions`, `ShowSettings`… | exposes the queries of other users and the database configuration |
| `TERMINATE TRANSACTIONS '…'` | `READ_ONLY` | `TerminateTransactions` | stops the work of other users |

The query type answers "does it write to the graph?", not "is it safe to run on behalf of an assistant?".

## Decision

`query_graph` keeps the `EXPLAIN` check and the read transaction, and also rejects a query whose `EXPLAIN` plan contains:

- a `LoadCSV` operator;
- a `ProcedureCall` operator: no procedure is allowed, read-only ones included; `CALL { … }` subqueries are Cypher,
  not procedures, and stay allowed;
- any operator whose name starts with `Show` or `Terminate` (administration commands).

Operators are read from the plan, not from the query text: the plan is what Neo4j will run, whatever the spelling,
comments or case of the query. The rejection message names the forbidden operations, so the assistant can rewrite the
query.

## Consequences

- An assistant cannot use procedures such as `db.schema.visualization()` to discover the graph: the model is served by
  the `graph://model` resource and summarized in the tool description instead.
- The list is a deny-list of operator names: a future Neo4j version may add an operator that needs the same treatment.
  The integration tests run the rejected queries against the pinned Neo4j version, so an upgrade that renames an
  operator makes them fail.
- A read-only database user, planned for the managed database on AWS, remains the barrier that does not depend on
  either check.
