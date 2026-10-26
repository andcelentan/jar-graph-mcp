# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Bytecode analyzer (ASM) → Neo4j knowledge graph → MCP server that lets AI assistants query real code structure
(classes, methods, calls, hierarchies) deterministically instead of guessing it.

- `analyzer`: archive parsing (jar, war, ear, nested jars; ADR-0012) + graph persistence.
- `mcp-server`: Spring AI 2.0.1 / Spring Boot 4.1.1 MCP server over STDIO (ADR-0009): `find_implementations`,
  `describe_class`, `find_callers`, `query_graph` and the `graph://model` resource.
- Target: event-driven on AWS, provisioned with Terraform (S3 upload → EventBridge → ECS/Fargate analysis task;
  Neo4j Aura/self-managed or Neptune; MCP server as an ECS service; OAuth 2.1 for MCP clients, e.g. Cognito; IAM roles
  between AWS services). Local design choices should not block that path.

## Commands

```bash
docker compose up -d                 # local Neo4j 5.26 (bolt://localhost:7687, browser http://localhost:7474)
mvn -pl analyzer -am test            # unit tests, no Docker needed
mvn -pl analyzer -am verify          # + integration tests (*IT, Testcontainers): Docker must be running

# Single unit test / single integration test method
mvn -pl analyzer -am test -Dtest=BytecodeReaderTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl analyzer -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='Neo4jGraphRepositoryIT#saveShouldBeIdempotent'

# Run the analyzer (compile first) on a .jar/.war/.ear. It WIPES the target Neo4j database before writing.
mvn -q -pl analyzer exec:java -Dexec.mainClass=com.jgraph.analyzer.AppAnalyzer -Dexec.args="path/to/app.jar --include com.acme.*"

# Build the MCP server (executable jar); an MCP client starts it with `java -jar` and talks over stdin/stdout
mvn -pl mcp-server -am package
mvn -pl mcp-server -am verify        # analyzer + server tests (server ITs need Docker)
# Single server IT: with -am the analyzer has no matching IT, hence the failsafe flag
mvn -pl mcp-server -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=FindImplementationsToolIT
```

`mcp-server` imports the Spring Boot and Spring AI BOMs in its own `dependencyManagement`, so they do not affect the
analyzer. Effective versions differ between modules: JUnit 6 (from Spring Boot) in `mcp-server`, JUnit 5 in `analyzer`;
the Neo4j driver version of the root `pom.xml` wins in both. With STDIO, stdout carries only JSON-RPC: no banner, no
web server, logs on stderr (`logback-spring.xml`). Do not probe the server from PowerShell 5.1: it writes a UTF-8 BOM
on stdin and the first message fails to parse; use a bash coprocess or the SDK client.
On Windows a running MCP client (a Claude Code session with the `jar-graph` server) locks the packaged jar: stop it
before `package`/`verify`, or `repackage` fails with "Unable to rename".

Java 21, Maven multi-module; dependency versions and BOMs live in the root `pom.xml`. No linter/formatter configured.
Analyzer config: `analyzer/src/main/resources/application.properties` (`config.Settings`), each key overridden by an
env var: `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`, and `ANALYZER_INCLUDE` for `analyzer.include` (a
`--include` on the command line wins over both).

## Analyzer architecture

`AppAnalyzer` (CLI) → `ArchiveClassFileSource` → `BytecodeReader` → `ModelResolver` (`CallResolver` →
`OverrideResolver`) → `GraphRepository.initSchema()` / `deleteAll()` / `save()`, passing an `AnalysisResult` along
(extract → transform → load). `ModelResolver` is the only public class of the `resolver` package and fixes the order
of its steps.

- Input (ADR-0012, package `source`, no ASM): `ClassFileSource` hands over class files (`ClassFile`: bytes +
  origin like `app.jar!/BOOT-INF/lib/core.jar!/com/acme/Foo.class`) and decides nothing. `ArchiveClassFileSource`
  reads a `.jar`/`.war`/`.ear` and every nested `.jar`/`.war` as streams (`ZipInputStream` on the entry, wrapped so
  closing it does not close the outer one), at most `MAX_NESTING` = 4 levels; skips `META-INF/versions/**`,
  `module-info.class`, `package-info.class`; keeps archive order. `PackageFilter` is the analysis boundary: `com.acme`
  = `com.acme.*` = `com.acme.**` (package and below, whole segments), `*` = everything.
- `BytecodeReader` (was `JarReader`) per class file: name from the bytecode (`ClassReader.getClassName()`, never the
  entry path), skip it if outside the filter, skip it if the name was already read (first one wins, counted in
  `ReadResult.duplicatesSkipped`), else feed it to **one shared** `ModelBuildingClassVisitor`, which appends to plain
  lists. Per method that creates a `ModelBuildingMethodVisitor` that emits a `MethodCall` per `visitMethodInsn` and
  the `JavaMethod` itself in `visitEnd`. The whole model is held in memory before persistence.
- CLI (`CommandLine`, `AnalysisConfig`): the filter is required for every archive (`--include`, `ANALYZER_INCLUDE` or
  `analyzer.include`; `--include-all` for everything), no default and no guessing; a filter matching nothing is
  refused before `deleteAll()`. Exit codes 0 / 1 (I/O, Neo4j) / 2 (invalid input or configuration).
- **Cross-file invariant:** the method signature string is built in two places, `JavaMethod.signature()` and
  `ModelBuildingMethodVisitor` (caller and call-target signatures). Both must stay `owner#name + descriptor`, or
  `CALLS` stop matching declared methods; the resolvers build and split it with the helpers of `TypeHierarchy`.
- The parser only records facts written in each `.class` (local), including each call's `Invocation` (opcode) and
  receiver (owner named in the instruction). The resolvers derive facts that need the whole hierarchy and are
  stateless: per call they build a `TypeHierarchy` (package-private: types, adjacency list type → superclass +
  interfaces, signature index; only types included in the analysis).
  - `CallResolver` (ADR-0008) points each call whose named type is in the analysis but does not declare the method at
    the declaration the JVM would resolve: superclass chain first; then superinterfaces (nearest declaration per path)
    only if the chain ended at an implicit supertype. It leaves the call as written if the chain reaches an explicit
    external superclass, or if zero or several declarations are found. `receiver` and `invocation` never change.
  - `OverrideResolver` (ADR-0006, ADR-0007) builds a bridge index (bridge → the single same-name method its body calls,
    from the already resolved calls), then walks up depth-first per method, stopping at the nearest valid declaration
    on each path. An override found for a bridge is attributed to its bridged method, and dropped if that would be a
    self-override (visibility bridges). Overrides are not in bytecode (`@Override` has SOURCE retention).
- `GraphRepository` is the persistence port (no Neo4j types, to keep a Neptune adapter possible);
  `Neo4jGraphRepository` is the only adapter. `save()` writes in client-side chunks, one `executeWrite` per chunk,
  in dependency order: classes → `EXTENDS` → `IMPLEMENTS` → methods (+ `DECLARES`) → `OVERRIDES` → `CALLS`. Later
  steps `MATCH` nodes created by earlier ones. The `Driver` constructor is for tests/shared drivers, but `close()` still
  closes that driver.
- Tests: `CallResolverTest` and `OverrideResolverTest` are the resolvers' specifications (hand-built hierarchies + real
  javac bytecode). `Neo4jGraphRepositoryIT` runs against `neo4j:5.26-community` via Testcontainers (no driver mocks),
  uses the package-private `(Driver, chunkSize)` constructor with tiny chunks to cover chunk boundaries, and checks
  call resolution with `resolveCallsFrom`, the query of ADR-0008. `TestJars` builds JARs from compiled test classes
  (`JarReaderTestFixture`, `HierarchyFixture`, `TypeKindFixture`, `BridgeFixture`, `CallFixture`, `ArchiveFixture`
  plus `org.thirdparty.library.ThirdPartyClient` as a library), nested archives with `TestJars.archive()` (classes in
  any folder, `nested`/`nestedStored` jars), and reads them with `TestJars.parse` (the real source and reader, filter
  `*`); keep fixtures free of generics and covariant returns unless the test is about bridge methods
  (`BridgeFixture`). `ArchiveClassFileSourceTest` uses fake class bytes: the source never reads bytecode.
  Validate a new specification (red tests) against a throwaway reference implementation before handing it over.

### Graph model

```
(:Class {fqcn, simpleName, kind, isAbstract})-[:EXTENDS]->(:Class)          class -> superclass, interface -> superinterface
(:Class)-[:IMPLEMENTS]->(:Class)                                            class -> interface only
(:Class)-[:DECLARES]->(:Method {signature, name, descriptor, returnType, parameterTypes,
                                visibility, isStatic, isAbstract, isBridge, isTransactional})
(:Method)-[:CALLS {invocation, receiver}]->(:Method)                       declared method that is called
(:Method)-[:OVERRIDES]->(:Method)                                           nearest overridden declaration
```

- `fqcn` is the JVM internal name (`com/acme/Foo`), while `returnType`/`parameterTypes` are dotted (`com.acme.Foo`).
- Types/methods referenced but not included in the analysis (JDK, libraries) become stub nodes with no `DECLARES`.
- `kind`: `CLASS`, `INTERFACE`, `ENUM`, `RECORD`, `ANNOTATION` (`ClassKind`; `isInterface()` is derived from it).
  Implicit supertypes (`java/lang/Object`, `Record`, `Enum`, `annotation/Annotation`) are never linked (supernodes);
  `JavaClass.superClass` is null for interfaces and when the superclass is implicit.
- `CALLS` targets the declared method (ADR-0008), like "Go to Declaration"; `receiver` keeps the type named in bytecode
  and `invocation` the instruction. Resolve the code that may run with
  `(caller)-[call:CALLS]->(declared)<-[:OVERRIDES*0..]-(impl) WHERE NOT impl.isAbstract AND (impl = declared OR
  call.invocation IN ['VIRTUAL', 'INTERFACE']) RETURN DISTINCT ...`. Known superset: virtual calls resolved to a
  superclass also return sibling overrides (deferred; `receiver` allows filtering them later).
- A `Method` without `DECLARES` is outside the analysis, except calls left unresolved on purpose (methods inherited
  from the JDK or a framework superclass, ambiguous `default` methods), which stay on a node named after the receiver.
- `OVERRIDES` only links types included in the analysis (`result.classes()`), not the artifact layout.
- Bridge methods are kept (`isBridge = true`, with their `CALLS` to the bridged method) but never own `OVERRIDES`
  (ADR-0007): the graph keeps every method in bytecode, flagged rather than hidden. Queries listing methods filter
  `WHERE NOT m.isBridge`. Calls that target a visibility bridge directly still resolve to the bridge (known limit).

## MCP server architecture

Packages by feature, one per tool: `implementations`, `callers`, `classes`, `cypher` (`query_graph` and the
`graph://model` resource), plus `shared` (`MethodInfo`, `MethodSummary`, `SourceNames`, `Notes`, `GraphSupport` with
`MATCH_CLASSES_BY_NAME` and `toMethodInfo`) and `transport` (STDIO configuration, below). Inside a feature: `XxxTool` (`@McpTool`) → `XxxQuery` (Cypher and
mapping) → the Neo4j `Driver` auto-configured by Spring Boot (`spring.neo4j.*` in `application.yml`, same env vars as
the analyzer). One query per tool call. **A new tool is a new package.** Only `shared`, the tools and the records
serialized to JSON are `public`; queries, fact records and constructors are package-private (Spring injects them
anyway). Tests mirror the packages.

- Tools are `@Component` beans with `@McpTool` methods, registered by Spring AI's annotation scanner. Arguments are
  bound and named in the schema after the Java parameters, so the module compiles with `-parameters`
  (`maven.compiler.parameters`). Results are records that Spring AI serializes with its own Jackson 3 mapper as text
  content (no output schema); `@JsonInclude` drops fields that do not apply. A `RuntimeException` becomes a result
  with `isError: true` that the model reads: throw `IllegalArgumentException` for malformed input.
- Queries return facts in graph identifiers (`MethodInfo`, `ClassInfo`, internal names, signatures), always in
  `executeRead`; tools convert them to the records the assistant sees (`MethodSummary`, `ClassDescription`,
  `TypeSummary`, `CallerSummary`, built by their static `of(...)`) and write the words. Fact records never reference
  view records. Every query starts with `GraphSupport.MATCH_CLASSES_BY_NAME` (analyzed classes are the `Class` nodes
  with a `kind`; stubs only have `fqcn`). Several lists per row come from `COLLECT { }` subqueries, never from chained `OPTIONAL MATCH`es
  (they multiply rows); hierarchy traversals always name their relationship types.
- `find_callers` reads the ADR-0008 resolution query backwards: `(method)-[:OVERRIDES*0..]->(calledMethod)<-[call:CALLS]-
  (caller)`, zero steps for direct calls (any instruction), more only for `VIRTUAL`/`INTERFACE`. A dispatched call
  shows `through` (the overridden method it names); bridges are never callers. Known superset: sibling overrides via
  calls on a concrete inheriting type (receiver filtering deferred, exact between classes only). Callers through
  method references are missing (`invokedynamic`), and the tool description says so.
- `query_graph` runs Cypher written by the assistant through `ReadOnlyCypherRunner` (ADR-0009, ADR-0010): `EXPLAIN`
  first, rejected unless `READ_ONLY` **and** free of the plan operators `LoadCSV`, `ProcedureCall`, `Show*`,
  `Terminate*` (Neo4j classifies those as read-only too); then a read transaction with a timeout, reading at most
  `max-rows` rows plus one to detect truncation (`QueryGraphProperties`, `jar-graph.query-graph.*`). Rejections throw
  `QueryRejectedException` with a message telling the assistant what to change. `CypherValues` turns any result into
  plain values (nodes and relationships as maps, no internal ids). `GraphModelResource` serves
  `src/main/resources/graph-model.md` as `graph://model`: **a change to the graph model must update that file**, and
  the summary in the `query_graph` description.
- Tools are context, not proof: partial answers beat refusals. Class names match like the assistant knows them:
  simple, partially or fully qualified, nested as `Outer.Inner` or `Outer$Inner` (`SourceNames.dottedClassName`), on
  whole name segments (`PaymentService` never matches `CardPaymentService`). Every matching class and every overload
  is returned, each with its class and parameter types, and the assistant picks. Output uses binary names
  (`com.acme.Outer$Inner`).
- An empty answer always has a `note` saying why (ADR-0009): no such class, class found but method not declared
  there, abstract with no implementation, Spring Data repository, no callers ("not necessarily unused": entry points,
  framework, reflection, other jars, method references). Notes shared by several tools live in `Notes`.
- Lambda bodies (`lambda$<enclosing>$<n>`, synthetic methods made by javac) are kept and flagged
  (`MethodSummary.isLambdaBody`, recognized by name, written to JSON only when true), like bridges in the graph.
  `describe_class` lists direct supertypes (`inAnalysis` false for stubs) and direct subtypes only: the assistant walks
  the hierarchy one class at a time.
- Tests: `FixtureGraph` writes the graph of `McpFixture` with the real analyzer pipeline (analyzer `test-jar`) to one
  Neo4j container shared by the whole test JVM (singleton container). Spring Data types are stand-ins under
  `src/test/java/org/springframework/data/`, never packaged in the fixture jar. Tools are tested by calling their
  methods; `DescribeClassToolIT`, `FindCallersToolIT` and `QueryGraphToolIT` are specifications (validated by
  mutating the implementation). `QueryGraphToolIT` checks after each test that no write reached the shared graph. Failsafe reads `classesDirectory` because `repackage` replaces the module jar.
- STDIO (ADR-0011): the SDK 2.0.0 transport queues outgoing messages in a `Sinks.many().unicast()` that accepts one
  producer at a time, so two tool calls finishing together on `boundedElastic` threads lose a response ("Failed to
  enqueue message"), and that error ends the transport's inbound pipeline: the server never answers again. `StdioTransportConfiguration` declares, only when
  `spring.ai.mcp.server.stdio=true`, a `McpSyncServerCustomizer` setting `immediateExecution(true)`: tools run on the
  single thread reading standard input, one at a time, and that thread emits every response. Spring AI injects a
  single customizer (`Optional`): merge any new one into this bean. `StdioTransportIT` runs the packaged jar as a
  process (failsafe passes its path as `mcp-server.jar`), sends 200 calls without waiting and requires every response
  and a clean exit on end of input; it talks raw JSON-RPC through `JsonRpcPeer`, never the SDK client (same queue on
  the client side). Re-run it on every SDK or Spring AI upgrade.

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

- MCP server (ADR-0009): all tools, the `graph://model` resource and the end-to-end STDIO test (`StdioTransportIT`,
  ADR-0011) done. Next (own branch): Streamable HTTP with authentication (own ADR) for AWS; set
  `spring.ai.mcp.server.protocol: STREAMABLE` and `stdio: false` explicitly (unset, Spring AI 2.0.1 starts the
  deprecated SSE transport).
  - Requirement for every tool: **never an empty answer without saying why.** Same gap as Spring Data for other
    runtime-generated implementations (Feign clients); MapStruct and Lombok are fine (code generated at compile time,
    present in the JAR).
- Dependency injection (from the review of `@Autowired` calls): DI itself needs nothing (an injected field is a plain
  `INTERFACE` call; one implementation gives an exact answer, several give a superset).
  - Framework entry points (own ADR, parser change): read Spring annotations with RUNTIME retention (`@GetMapping` and
    other mappings, `@EventListener`, `@Scheduled`, `@KafkaListener`...) and flag those methods as invoked by the
    framework, so that "no callers" is not mistaken for dead code. `publishEvent` → listeners is only linked at run
    time.
  - Later: narrow implementations with `@Qualifier`/`@Primary`; flag self-invocation of `@Transactional` methods
    (`this.m()` bypasses the Spring proxy, so no transaction starts).
- Deferred by ADR-0008: restrict dispatch to the receiver's subtypes (sibling overrides) using `receiver`, likely inside
  MCP tools (`find_callers`, `find_implementations`); exact only when receiver and method owner are both classes
  (single inheritance), since with interfaces a common subtype may exist outside the analysis. Resolve methods
  inherited from implicit/external supertypes (e.g. `Enum#name()`).
- Parser fidelity: `invokedynamic` (lambda/method-ref call chains), exact `@Transactional` descriptors + class-level
  annotation, consistent type naming.
- Repository: mark stub nodes (`external = true`); driver ownership in `close()`.
- CLI: fail-fast connectivity check before reading the archive (exit codes done, ADR-0012). Multi-artifact inputs
  and `target/classes` folders stay out of scope (ADR-0002) unless a new ADR says otherwise.
