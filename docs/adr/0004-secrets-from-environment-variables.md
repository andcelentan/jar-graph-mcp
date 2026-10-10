# 4. Database credentials from environment variables

Date: 2026-10-10

## Status

Accepted

## Context

The analyzer needs Neo4j credentials. Locally they come from `application.properties` and `docker-compose.yml`. On AWS,
the analyzer will run as an ECS/Fargate task and the credentials will live in AWS Secrets Manager.

Reading secrets with the AWS SDK would tie the analyzer to AWS and add a dependency used only in one environment.
ECS task definitions can inject Secrets Manager values into containers as environment variables (`secrets.valueFrom`).

## Decision

We will read connection settings from environment variables (`NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`), which
override `application.properties`. The code does not depend on any AWS SDK, and the configuration object must never
print the password (e.g. in `toString()`).

## Consequences

- The same binary runs locally and on ECS; only the environment changes.
- The production password never appears in the repository, in code or in logs.
- Secret rotation is handled by Secrets Manager and ECS, not by the application.
