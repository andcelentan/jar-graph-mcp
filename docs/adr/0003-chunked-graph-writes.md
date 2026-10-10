# 3. Chunked graph writes with managed transactions

Date: 2026-10-10

## Status

Accepted

## Context

`Neo4jGraphRepository.save()` originally wrote classes, methods and relationships in a single transaction. On large
artifacts (e.g. a Spring Boot application with tens of thousands of methods and hundreds of thousands of calls) a single
transaction puts heavy pressure on server memory and risks timeouts.

Options considered:

- **`CALL { ... } IN TRANSACTIONS OF n ROWS`** (server-side batching). It only runs in implicit (auto-commit)
  transactions, so it gets no automatic retry from the driver on transient errors, and the whole batch is still sent as
  one parameter.
- **Client-side chunking:** split each batch in Java and write each chunk in its own managed transaction
  (`Session.executeWrite`).

## Decision

We will chunk on the client side: 10,000 rows per chunk by default, one `executeWrite` per chunk. Steps run strictly in
dependency order (classes → `IMPLEMENTS` → methods and `DECLARES` → `CALLS`) because later steps `MATCH` nodes created
by earlier ones. Every statement uses `MERGE`, so re-running a chunk is safe. Duplicate calls are removed before they
are written.

`CALL { ... } IN TRANSACTIONS` stays the right tool for `deleteAll()`, which has no input batch to send.

## Consequences

- Transaction size is bounded, and the driver can retry a single failed chunk instead of the whole import.
- There is no global atomicity: a failed run leaves a partial graph. This is acceptable because every run starts with
  `deleteAll()` (see [ADR-0002](0002-one-artifact-per-analysis-run.md)).
- The chunk size can be injected, so tests can cover chunk boundaries with small fixtures.
