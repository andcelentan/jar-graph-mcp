# 7. Keep bridge methods and attribute their overrides to the bridged method

Date: 2026-10-11

## Status

Accepted

## Context

The JVM identifies a method by its exact name and descriptor (parameter types **and** return type), and knows nothing
about generics. When the Java language considers a method an override but the two descriptors differ, javac generates
a **bridge method** in the subclass, flagged `ACC_BRIDGE | ACC_SYNTHETIC`. It carries the descriptor the JVM expects
and forwards to the method written by the developer (the *bridged* method). This happens with:

- **generics**, because of type erasure: `OrderRepository implements Repository<Order>` gets a bridge
  `save(Object)` that casts and calls `save(Order)`;
- **covariant return types**: `Dog copy()` overriding `Animal copy()` gets a bridge `Animal copy()`;
- **visibility**: a public class extending a package-private class gets bridges for the inherited public methods.

With the rules of [ADR-0006](0006-resolve-overrides-at-ingestion.md) (same name and descriptor), the override of a
generic method is attributed to the bridge, `OrderRepository#save(Object) -[:OVERRIDES]-> Repository#save(Object)`,
and the code the developer wrote is only reachable through one more `CALLS` hop. Every generic hierarchy inside the
analysis is affected (e.g. `AbstractCrudService<T>` and its concrete services, internal `Handler<T>` implementations).
Generic framework interfaces such as Spring's `Converter<S, T>` are outside the analysis: ADR-0006 creates no
`OVERRIDES` towards them, with or without bridges.

The link between the erased method and the bridged one is not written anywhere else in bytecode: it is the single call
in the bridge body.

Options considered:

- **A. Exclude methods by parameter type** (e.g. `Object`): a heuristic, wrong both ways. Real methods such as
  `equals(Object)` would be dropped; bounded generics (erased to the bound), covariant return types and visibility
  bridges would be missed.
- **B. Drop methods flagged `ACC_BRIDGE`**: no method is left with the erased descriptor, so no `OVERRIDES` is found
  at all and resolution hits a dead end on the abstract method.
- **C. Rebuild generic substitutions** from the `Signature` attributes along the hierarchy: reimplements part of the
  compiler's type checking, while javac has already written the answer into the bridge.
- **D. Collapse bridges**: redirect their overrides to the bridged method, then drop the bridge node. Resolution is
  correct, but the graph no longer describes every method in bytecode, and it would be inconsistent with keeping
  synthetic lambda bodies, which hold user code and are needed to follow `invokedynamic` call chains.
- **E. Keep bridges, flagged, and attribute their overrides to the bridged method.**

## Decision

We will keep bridge methods in the graph and attribute their overrides to the bridged method (option E).

1. **Parser:** reads `ACC_BRIDGE` into `isBridge`. Nothing else: the calls in the bridge body are already recorded
   like those of any other method.
2. **Resolver:** derives the bridged method from the bridge's calls: the single call to a method with the same name.
   If there is not exactly one such call (e.g. bytecode from another compiler), the bridge is treated as a regular
   method: a less clean graph is better than an invented link. A bridge with a known bridged method never owns an
   `OVERRIDES` relationship: overrides found for it are attributed to the bridged method, e.g.
   `OrderRepository#save(Order) -[:OVERRIDES]-> Repository#save(Object)`. An attribution that would make a method
   override itself is dropped: visibility bridges forward to the very method they would be attributed to.
3. **Graph:** `isBridge` is persisted on `Method`. The bridge keeps its `DECLARES` and the `CALLS` to the bridged
   method read from its body; no other relationship is added.

The resolution query of ADR-0006 is unchanged and needs no filter: the bridge has no `OVERRIDES`, so it is never
returned as an implementation.

## Consequences

- Calls to generic and covariant methods resolve to the code the developer wrote, with the same query as any other
  override.
- The graph keeps every method present in bytecode: compiler-generated methods are flagged, not hidden. This is the
  same policy that synthetic lambda bodies will follow.
- Queries and MCP tools that list methods must exclude bridges (`WHERE NOT m.isBridge`).
- Calls that target a bridge directly (mostly visibility bridges, rare in application code) still resolve to the
  bridge; its single `CALLS` leads to the real method.
- This replaces the backlog plan of dropping bridge nodes (option D).
