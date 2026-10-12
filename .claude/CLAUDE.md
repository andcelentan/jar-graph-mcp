# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Bytecode analyzer (ASM) → Neo4j knowledge graph → MCP server that lets AI assistants query real code structure
(classes, methods, calls, hierarchies) deterministically instead of guessing it.

- `analyzer`: JAR parsing + graph persistence (current focus).
- `mcp-server`: IntelliJ placeholder `Main`, no MCP code yet.
- Target: event-driven on AWS, provisioned with Terraform (S3 upload → EventBridge → ECS/Fargate analysis task;
  Neo4j Aura/self-managed or Neptune; MCP server as an ECS service; OAuth 2.1 for MCP clients, e.g. Cognito; IAM roles
  between AWS services). Local design choices should not block that path.

## Commands

```bash
docker compose up -d                 # local Neo4j 5.26 (bolt://localhost:7687, browser http://localhost:7474)
mvn -pl analyzer -am test            # unit tests, no Docker needed
mvn -pl analyzer -am verify          # + integration tests (*IT, Testcontainers): Docker must be running

# Single unit test / single integration test method
mvn -pl analyzer -am test -Dtest=JarReaderTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl analyzer -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='Neo4jGraphRepositoryIT#saveShouldBeIdempotent'

# Run the analyzer (compile first). It WIPES the target Neo4j database before writing.
mvn -q -pl analyzer exec:java -Dexec.mainClass=com.jgraph.analyzer.AppAnalyzer -Dexec.args=path/to/app.jar
```

Java 21, Maven multi-module; dependency versions and BOMs live in the root `pom.xml`. No linter/formatter configured.
DB config: `analyzer/src/main/resources/application.properties`, overridden by env vars `NEO4J_URI`,
`NEO4J_USERNAME`, `NEO4J_PASSWORD`.

## Analyzer architecture

`AppAnalyzer` (CLI) → `JarReader` → `OverrideResolver` → `GraphRepository.initSchema()` / `deleteAll()` / `save()`,
passing an `AnalysisResult` along (extract → transform → load).

- `JarReader` feeds every top-level `.class` entry to **one shared** `ModelBuildingClassVisitor`, which appends to plain
  lists. Per method it creates a `ModelBuildingMethodVisitor` that emits a `MethodCall` per `visitMethodInsn` and the
  `JavaMethod` itself in `visitEnd`. The whole model is held in memory before persistence.
- **Cross-file invariant:** the method signature string is built in two places, `JavaMethod.signature()` and
  `ModelBuildingMethodVisitor` (caller and call-target signatures). Both must stay `owner#name + descriptor`, or
  `CALLS` stop matching declared methods; `OverrideResolver` also looks methods up by that format.
- The parser only records facts written in each `.class` (local); `OverrideResolver` derives facts that need the whole
  hierarchy (ADR-0006). It is stateless: per call it builds an adjacency list (type → superclass + interfaces), a
  signature index and a bridge index (bridge → the single same-name method its body calls, ADR-0007), then walks up
  depth-first per method, stopping at the nearest valid declaration on each path. An override found for a bridge is
  attributed to its bridged method, and dropped if that would be a self-override (visibility bridges).
  Overrides are not in bytecode (`@Override` has SOURCE retention), so they can only be derived.
- `GraphRepository` is the persistence port (no Neo4j types, to keep a Neptune adapter possible);
  `Neo4jGraphRepository` is the only adapter. `save()` writes in client-side chunks, one `executeWrite` per chunk,
  in dependency order: classes → `EXTENDS` → `IMPLEMENTS` → methods (+ `DECLARES`) → `OVERRIDES` → `CALLS`. Later
  steps `MATCH` nodes created by earlier ones. The `Driver` constructor is for tests/shared drivers, but `close()` still
  closes that driver.
- Tests: `OverrideResolverTest` is the resolver's specification (hand-built hierarchies + real javac bytecode).
  `Neo4jGraphRepositoryIT` runs against `neo4j:5.26-community` via Testcontainers (no driver mocks) and uses the
  package-private `(Driver, chunkSize)` constructor with tiny chunks to cover chunk boundaries. `TestJars` builds JARs
  from compiled test classes (`JarReaderTestFixture`, `HierarchyFixture`, `TypeKindFixture`, `BridgeFixture`); keep
  fixtures free of generics and covariant returns unless the test is about bridge methods (`BridgeFixture`).
  Validate a new specification (red tests) against a throwaway reference implementation before handing it over.

### Graph model

```
(:Class {fqcn, simpleName, kind, isAbstract})-[:EXTENDS]->(:Class)          class -> superclass, interface -> superinterface
(:Class)-[:IMPLEMENTS]->(:Class)                                            class -> interface only
(:Class)-[:DECLARES]->(:Method {signature, name, descriptor, returnType, parameterTypes,
                                visibility, isStatic, isAbstract, isBridge, isTransactional})
(:Method)-[:CALLS]->(:Method)
(:Method)-[:OVERRIDES]->(:Method)                                           nearest overridden declaration
```

- `fqcn` is the JVM internal name (`com/acme/Foo`), while `returnType`/`parameterTypes` are dotted (`com.acme.Foo`).
- Types/methods referenced but not included in the analysis (JDK, libraries) become stub nodes with no `DECLARES`.
- `kind`: `CLASS`, `INTERFACE`, `ENUM`, `RECORD`, `ANNOTATION` (`ClassKind`; `isInterface()` is derived from it).
  Implicit supertypes (`java/lang/Object`, `Record`, `Enum`, `annotation/Annotation`) are never linked (supernodes);
  `JavaClass.superClass` is null for interfaces and when the superclass is implicit.
- `CALLS` targets the static receiver type from the bytecode (often an interface); resolve the code that runs with
  `(caller)-[:CALLS]->(declared)<-[:OVERRIDES*0..]-(impl) WHERE NOT impl.isAbstract`.
- `OVERRIDES` only links types included in the analysis (`result.classes()`), not the artifact layout.
- Bridge methods are kept (`isBridge = true`, with their `CALLS` to the bridged method) but never own `OVERRIDES`
  (ADR-0007): the graph keeps every method in bytecode, flagged rather than hidden. Queries listing methods filter
  `WHERE NOT m.isBridge`. Calls that target a visibility bridge directly still resolve to the bridge (known limit).

## Working agreement

- Conversation in Italian; code, comments, commits and docs in English.
- Enterprise-grade Java: strong typing, explicit exception handling, no fragile shortcuts; reason about architectural
  impact and graph design, not only the local change.
- This is also a learning project for tools the developer doesn't know yet (Neo4j/Cypher, Testcontainers, Terraform,
  AWS). When introducing one: explain the concept and why it fits here, the discarded alternatives, and the idiomatic
  way vs. the merely working one. Leave room for the developer to write parts themselves when they ask.
- Propose before large changes; one concern per PR.

## Architecture decisions

Recorded as ADRs in `docs/adr/` (Nygard format, index in `docs/adr/README.md`). Read the relevant ones before changing
persistence, graph model, configuration or test strategy.

- An architecturally significant change ships with a new ADR in the same PR: next sequential number, status
  `Accepted`, and a row in the index.
- Never edit an accepted ADR: write a new one that supersedes it and set the old status to `Superseded by ADR-NNNN`.

## Backlog (agreed, not started)

- `ClassFileSource` abstraction (separate PR): recurse into nested archives (Spring Boot fat jar, WAR, EAR), package
  include filter, skip `META-INF/versions/**`, first-wins on duplicate classes. Input is the CI build artifact
  (ADR-0002); multi-JAR inputs and `target/classes` dirs are out of scope unless a new ADR says otherwise.
- Calls to inherited methods (next, ADR-0008): bytecode names the receiver's static type (JLS §13.1), so
  `sub.inherited()` becomes `CALLS -> Sub#inherited`, a stub not linked to the declaring superclass method. Mirror JVM
  method resolution (JVMS §5.4.3.3) in the resolver with a derived `(:Method)-[:RESOLVES_TO]->(:Method)`, keeping
  `CALLS` as in bytecode. Higher impact than bridges (template method pattern, `this.helper()`).
- Parser fidelity: `invokedynamic` (lambda/method-ref call chains), exact `@Transactional` descriptors + class-level
  annotation, consistent type naming.
- Repository: mark stub nodes (`external = true`); driver ownership in `close()`.
- CLI: non-zero exit codes on failure (ECS/Fargate job status), fail-fast connectivity check.
