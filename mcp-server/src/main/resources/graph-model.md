# Code graph model

The graph is written by the analyzer from the compiled bytecode of one application jar: facts from the code, not
guesses. Read it with the `query_graph` tool (read-only Cypher). Prefer `find_implementations`, `find_callers` and
`describe_class` when they answer the question: they apply the rules below for you.

## Nodes and relationships

```
(:Class {fqcn, simpleName, kind, isAbstract})-[:EXTENDS]->(:Class)       class -> superclass, interface -> superinterface
(:Class)-[:IMPLEMENTS]->(:Class)                                         class -> interface
(:Class)-[:DECLARES]->(:Method {signature, name, descriptor, returnType, parameterTypes,
                                visibility, isStatic, isAbstract, isBridge, isTransactional})
(:Method)-[:CALLS {invocation, receiver}]->(:Method)                    the declared method a call points at
(:Method)-[:OVERRIDES]->(:Method)                                        the nearest overridden declaration
```

- Arrows go from subtype to supertype and from overriding to overridden method.
- `fqcn` and `receiver` are JVM internal names: `/` between packages, `$` for nested classes
  (`com/acme/Outer$Inner`). `returnType` and `parameterTypes` are dotted (`com.acme.Order`, `int`, `java.lang.String[]`).
- `signature` is `owner#name(descriptor)`, e.g. `com/acme/OrderService#save(Lcom/acme/Order;)V`; constructors are
  named `<init>`, static initializers `<clinit>`.
- `kind` is `CLASS`, `INTERFACE`, `ENUM`, `RECORD` or `ANNOTATION`. `visibility` is `PUBLIC`, `PROTECTED`,
  `PACKAGE_PRIVATE` or `PRIVATE`. `invocation` is `VIRTUAL`, `INTERFACE`, `SPECIAL` (super calls, constructors) or `STATIC`.

## Rules for correct queries

1. **Stubs.** Types and methods only referenced by the application (JDK, libraries) are stub nodes: classes without
   `kind`, methods without an incoming `DECLARES`. Filter analyzed classes with `c.kind IS NOT NULL`.
2. **Implicit supertypes are never linked**: `java/lang/Object`, `Record`, `Enum`, `annotation/Annotation`.
3. **Bridges.** javac adds bridge methods for generics and covariant returns. They are kept, flagged
   `isBridge = true`, and never own `OVERRIDES`: exclude them with `NOT m.isBridge` when listing methods.
4. **Lambdas.** The body of a lambda is a synthetic method named `lambda$<enclosing method>$<n>`. Calls made through
   lambdas and method references (`invokedynamic`) are not linked to the method that contains them yet.
5. **Calls point at declarations.** Like "Go to Declaration": `sub.inherited()` points at the superclass method.
   `receiver` keeps the type named in bytecode. Only `VIRTUAL` and `INTERFACE` calls dispatch at run time.
6. **Name relationship types**, above all in variable-length patterns: `[:OVERRIDES*0..]`, never `[*0..]`.
7. **Overrides are only known inside the analysis**: implementing a JDK or library interface creates no `OVERRIDES`.

## Examples

Code that may run when a method calls another (the rule of `find_implementations`):

```cypher
MATCH (caller:Method {signature: $signature})-[call:CALLS]->(declared:Method)<-[:OVERRIDES*0..]-(impl:Method)
WHERE NOT impl.isAbstract AND (impl = declared OR call.invocation IN ['VIRTUAL', 'INTERFACE'])
RETURN DISTINCT impl.signature
```

Calls that may run a method (the rule of `find_callers`):

```cypher
MATCH (method:Method {signature: $signature})-[:OVERRIDES*0..]->(called:Method)<-[call:CALLS]-(caller:Method)
WHERE NOT caller.isBridge AND (called = method OR call.invocation IN ['VIRTUAL', 'INTERFACE'])
RETURN DISTINCT caller.signature, call.invocation
```

Classes with the most methods:

```cypher
MATCH (c:Class)-[:DECLARES]->(m:Method)
WHERE NOT m.isBridge AND NOT m.name STARTS WITH 'lambda$'
RETURN c.fqcn, count(m) AS methods ORDER BY methods DESC LIMIT 10
```

Transactional methods and the repositories they call:

```cypher
MATCH (m:Method {isTransactional: true})-[:CALLS]->(target:Method)<-[:DECLARES]-(repo:Class)
WHERE repo.fqcn ENDS WITH 'Repository'
RETURN m.signature, collect(DISTINCT repo.fqcn) AS repositories
```
