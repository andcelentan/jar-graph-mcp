# 5. Integration-test graph persistence against a real Neo4j

Date: 2026-10-10

## Status

Accepted

## Context

Most of the logic in `Neo4jGraphRepository` lives in Cypher queries and in how rows are split into chunks. A unit test
that mocks the Neo4j `Driver` only checks which strings are passed to `tx.run`: a wrong query would still pass.

## Decision

We will test the repository against a real Neo4j started with [Testcontainers](https://testcontainers.com/), using the
same image as `docker-compose.yml` (`neo4j:5.26-community`).

- Integration test classes end with `IT` and are run by `maven-failsafe-plugin` during `mvn verify`.
- `mvn test` runs unit tests only and does not need Docker.
- The Neo4j driver is not mocked.

## Consequences

- Queries, constraints, idempotence and chunk boundaries are verified against the real database.
- `mvn verify` requires a running Docker daemon, locally and in CI.
- Integration tests are slower (container start-up), which is why they run in a separate phase from unit tests.
