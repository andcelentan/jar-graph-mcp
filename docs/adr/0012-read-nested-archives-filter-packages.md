# 12. Read nested archives, analyze the packages chosen by configuration

Date: 2026-10-25

## Status

Accepted

## Context

[ADR-0002](0002-one-artifact-per-analysis-run.md) takes as input the artifact the CI build produces, which for a Spring
Boot application is a fat jar. The analyzer read every `.class` entry of that archive, but not the archives nested in
it. Measured on the fat jar of `mcp-server`:

- 38 classes of the application, under `BOOT-INF/classes/`: analyzed;
- 99 classes of the Spring Boot launcher, at the top level: analyzed as if they were the application;
- 67 jars in `BOOT-INF/lib/`, holding 13 251 classes: not read. In a real organization some of them are its own
  libraries (`acme-payments-core.jar`), whose interfaces and implementations the graph should link, as the README
  promises ("internal dependencies included").

Reading the nested jars is simple. Choosing which classes to analyze is not: nothing in a jar says whether it is the
organization's own code. Analyzing everything would make the graph hundreds of times larger, slower to build and to
store, and fill the answers of the MCP tools with library internals. Criteria considered:

- **The name of the jar file** (`acme-*.jar`): depends on how artifacts are named; fragile.
- **The Maven `groupId`** in `META-INF/maven/.../pom.properties`: only 49 of the 67 nested jars have it (Gradle does
  not write it by default).
- **The package of the classes** (`com.acme`): works whatever the build tool, class by class, and also leaves out the
  launcher, which is not in a nested jar.
- **Letting an LLM decide during the analysis**: not deterministic, and it would need an API key and network access in
  the CI job.

Which packages are the organization's own is something whoever runs the analysis knows. An inventory command that
listed the packages of the archive and suggested a filter was considered, and dropped: it added code to guess what
must be known, and listing an archive is what `jar tf` already does.

## Decision

- **`ClassFileSource`** (package `source`) is where the class files of an analysis come from; it knows nothing of
  bytecode and decides nothing. `ArchiveClassFileSource` reads a `.jar`, `.war` or `.ear` and every `.jar` or `.war`
  nested in it (`BOOT-INF/lib`, `WEB-INF/lib`, the modules and `lib/` of an EAR), as streams, without extracting
  anything, at most 4 levels deep. It skips `META-INF/versions/**` (multi-release copies), `module-info.class` and
  `package-info.class`, and hands every class file over with its origin (`app.jar!/BOOT-INF/lib/core.jar!/...`), in
  the order of the archive.
- **The package filter is the boundary of the analysis.** Patterns name a package and everything below it, matched on
  whole segments: `com.acme`, `com.acme.*` and `com.acme.**` all mean the same; `*` is everything. A class outside
  the filter is not analyzed; if the analyzed code refers to it, it becomes a stub node, like a class of the JDK. The
  filter comes from `--include` on the command line, else `ANALYZER_INCLUDE`, else `analyzer.include` in
  `application.properties` (environment over file, as in [ADR-0004](0004-secrets-from-environment-variables.md)).
- **The filter is required, with no default**: without one the analyzer stops and asks for it, whatever the archive.
  `--include-all` (or `*`) analyzes everything on purpose. A filter matching no class stops the analysis too, before
  the graph is wiped.
- **The class name comes from the bytecode**, never from the entry path. When two class files have the same name, the
  first one in the order of the archive is analyzed and the others are counted as skipped duplicates.
- **Exit codes**: 0 on success, 1 when the analysis fails (input/output, Neo4j), 2 when the input or the
  configuration is invalid, so a CI job or an ECS task fails with the analysis.

Several artifacts per analysis and `target/classes` folders stay out of scope (ADR-0002): `ClassFileSource` leaves
room for them.

## Consequences

- Fat jars, WARs and EARs are analyzed with their internal libraries and without the launcher, provided the filter
  names the organization's packages; CI sets `ANALYZER_INCLUDE` once.
- Every class file of the archive is read, also those the filter leaves out: the class name is in the bytecode.
  Reading the 13 388 class files of the `mcp-server` fat jar takes seconds.
- An analysis without a filter fails instead of quietly analyzing the libraries; analyzing a plain jar, which used to
  need only its path, now needs `--include` or `--include-all` too.
- `JarReader` is renamed `BytecodeReader`: it reads a `ClassFileSource`, not a jar.
