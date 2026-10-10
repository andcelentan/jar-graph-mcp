# 2. One artifact per analysis run

Date: 2026-10-10

## Status

Accepted

## Context

The analyzer persists the model of a JAR into Neo4j. Two options were considered:

- **Single-artifact graph:** each run replaces the whole graph.
- **Multi-artifact graph:** several artifacts (or versions of the same artifact) coexist, scoped by an `:Artifact` node
  and a composite key such as artifact + FQCN.

The analyzer runs in CI on the deployable artifact produced by the build (typically a Spring Boot fat jar, WAR or EAR),
which already bundles the application's internal dependencies.

The current use case is analyzing one application at a time to feed an AI assistant with its structure. A multi-artifact
model adds scoping to every query and every constraint, and would be harder to port to Amazon Neptune, which does not
support Cypher schema constraints.

## Decision

We will keep one artifact per graph:

- every run calls `deleteAll()` before writing;
- `Class.fqcn` and `Method.signature` are globally unique, enforced by Neo4j uniqueness constraints.

## Consequences

- Queries and constraints stay simple: a class or method is identified by its name alone.
- A run destroys the previous graph; analyzing a second artifact means losing the first.
- Two analysis jobs must not run concurrently against the same database. When analysis moves to parallel jobs on
  AWS Fargate/ECS, this has to be revisited (database per job, locking, or a multi-artifact model) with a new ADR.
- Modules of a multi-module application can still be analyzed together, as long as they are packaged into a single
  artifact.
- Inputs made of several separate JARs (libraries, container builds that skip the fat jar) are out of scope; supporting
  them requires a new ADR.
