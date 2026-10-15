# 6. Resolve inheritance and method overrides at ingestion time

Date: 2026-10-10

## Status

Accepted, amended by [ADR-0008](0008-point-calls-at-declared-method.md) (call resolution query)

## Context

Bytecode records every call against the type declared at the call site: `repository.save(...)` on a `GraphRepository`
variable compiles to `invokeinterface GraphRepository.save`. In the graph, `CALLS` therefore points to an abstract or
interface method with no body, while the code that actually runs lives in a subclass. In Spring applications, where
most collaborators are injected through interfaces, this happens on almost every call.

The graph cannot bridge that gap today:

- superclasses are not modelled (`superName` is ignored by the parser), so abstract base classes and inherited methods
  are invisible;
- interface inheritance is stored as `IMPLEMENTS`, while in Java an interface `extends` its superinterfaces;
- a query matching implementations by name and descriptor only works one level deep and misses inherited methods.

Options considered:

- **A. Resolve at query time** with variable-length paths over `IMPLEMENTS`/`EXTENDS`, looking for the most specific
  declaration of each method. Every query (and every future MCP tool) has to repeat this logic in Cypher, where Java's
  override rules (visibility, `static`, packages) are hard to express and can only be tested against a database.
- **B. Resolve at ingestion time**: compute overrides in Java from the in-memory model and persist them as an explicit
  relationship.

## Decision

We will resolve overrides at ingestion time (option B).

1. **Hierarchy with Java semantics:**
   - `(:Class)-[:EXTENDS]->(:Class)` from a class to its superclass;
   - `(:Class)-[:IMPLEMENTS]->(:Class)` from a class to the interfaces it implements;
   - `(:Class)-[:EXTENDS]->(:Class)` from an interface to its superinterfaces.

   No relationship to the supertypes the compiler adds implicitly: `java/lang/Object` (every class),
   `java/lang/Record` (every record), `java/lang/Enum` (every enum) and `java/lang/annotation/Annotation` (every
   annotation type). The developer never writes them, so they carry no design information, and each would become a
   supernode: a node with hundreds or thousands of relationships that slows down traversals and makes "related within
   N hops" queries explode.
2. **New properties:**
   - `kind` on `Class` (`CLASS`, `INTERFACE`, `ENUM`, `RECORD`, `ANNOTATION`), replacing `isInterface`: it keeps the
     information the implicit supertypes would have carried, as one typed value instead of several booleans that
     could contradict each other;
   - `isAbstract` on `Class` and `Method`; `isStatic` and `visibility` (`PUBLIC`, `PROTECTED`, `PACKAGE_PRIVATE`,
     `PRIVATE`) on `Method`.
3. **`(:Method)-[:OVERRIDES]->(:Method)`**, computed by a resolver step between parsing and persistence
   (`AppAnalyzer` → `JarReader` → `OverrideResolver` → `GraphRepository`), following the Java rules
   ([JLS §8.4.8.1](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.4.8.1)):
   - same name and same descriptor;
   - both are instance methods (static methods hide, they do not override); constructors and static initializers are
     excluded;
   - the overridden method is not `private`, and a package-private method is only overridden from the same package.

   Each method points to the **nearest** matching declaration along every supertype path; farther ones are reached
   transitively. Resolving a call becomes a single traversal:

   ```cypher
   MATCH (caller:Method)-[:CALLS]->(declared:Method)<-[:OVERRIDES*0..]-(impl:Method)
   WHERE NOT impl.isAbstract
   RETURN caller.signature, impl.signature
   ```
4. **Scope:** only types included in the analysis, i.e. read by the parser, are resolved. Other supertypes (the JDK,
   and libraries the analysis does not read) are known by name only: their methods are unknown, so no `OVERRIDES`
   relationship points to them. The resolver relies on the parsed model, not on the artifact's layout, so reading
   more types (e.g. nested archives) extends the resolution with no change to it.

## Consequences

- Call resolution, including methods inherited from abstract base classes, is one simple traversal that every query
  and MCP tool can reuse.
- Override rules live in plain Java and are covered by unit tests that need no database.
- Ingestion does more work: one walk up the hierarchy per method, on a model already held in memory.
- Implementations of JDK or library interfaces (e.g. `Comparable#compareTo`) are not linked to the interface method.
- Overrides involving generics are still misresolved, because the matching method with the same descriptor is a
  compiler-generated bridge. Handling bridge methods is a separate decision (next ADR).
- Package-private methods overridden transitively through an intermediate class
  ([JVMS §5.4.5](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html#jvms-5.4.5)) are covered by the
  "nearest valid declaration" rule: the walk goes past declarations the method cannot override and stops at those it
  can, and transitive overrides are reached through `OVERRIDES*`.
- Records, enums and annotation types are found by `kind`, not by a relationship to `java/lang/Record`,
  `java/lang/Enum` or `java/lang/annotation/Annotation`.
