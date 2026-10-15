# 8. Point calls at the declared method and record how they are invoked

Date: 2026-10-12

## Status

Accepted

Amends [ADR-0006](0006-resolve-overrides-at-ingestion.md): refines its call resolution query.

## Context

### Calls name the receiver's type, not the declaring class

The compiler writes in a call instruction the static type of the receiver (the *qualifying type*,
[JLS §13.1](https://docs.oracle.com/javase/specs/jls/se21/html/jls-13.html#jls-13.1)), not the class that declares the
method, so that a later override in that type does not require recompiling callers:

```
sub.foo();                // foo() declared in PublicBase, sub of type PublicSub
invokevirtual PublicSub.foo:()V

SubHelper.util();         // static util() declared in Helper
invokestatic  SubHelper.util:()V

abstractService.pay();    // pay() declared only in the PaymentService interface
invokevirtual AbstractPaymentService.pay:()V
```

At run time the JVM looks the method up in the named class, then in its superclasses, then in its superinterfaces
([JVMS §5.4.3.3](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html#jvms-5.4.3.3)). The source code and
the IDE see the call differently: "Go to Declaration" on `sub.foo()` opens `PublicBase.foo`, the *compile-time
declaration* of the call.

In the graph, `CALLS` points to a node `PublicSub#foo` for a method that does not exist in the source code. It has no
`DECLARES` and is linked to nothing, so:

- the resolution query of ADR-0006 returns no result for these calls: the AI assistant gets no answer and guesses;
- the rule "a method without `DECLARES` is outside the analysis" no longer holds: queries listing external
  dependencies report application code as a library dependency.

This affects the template method pattern, calls to inherited helpers (`this.helper()`), inherited static methods and
calls through abstract classes to methods declared only in their interfaces.

### Not every call dispatches

The JVM chooses the method to execute on the runtime type of the object (dispatch) only for `invokevirtual` and
`invokeinterface`. `invokespecial` (`super.m()`, constructors) and `invokestatic` always execute the method named in
the instruction. The graph does not record the kind of invocation, so these two calls look identical:

```java
class PaypalPaymentService extends AbstractPaymentService {
    @Override void refund(Order o) { super.refund(o); }      // A: always AbstractPaymentService.refund
}
class Checkout {
    void cancel(AbstractPaymentService s, Order o) { s.refund(o); }   // B: any override of refund
}
```

```
(PaypalPaymentService#refund) -[:CALLS]-> (AbstractPaymentService#refund)    A
(Checkout#cancel)             -[:CALLS]-> (AbstractPaymentService#refund)    B
```

The resolution query treats both as B: for A it answers that `PaypalPaymentService#refund` calls itself, an infinite
recursion that does not exist.

### Options considered

Resolution:

- **A. Keep `CALLS` as written in bytecode and add `(:Method)-[:RESOLVES_TO]->(:Method)`** from the phantom node to the
  declaration. Faithful to the instruction, but it keeps nodes for methods that do not exist, adds a relationship and
  adds a step to every query.
- **B. Point `CALLS` at the declaration and keep the bytecode reference as a property.** Matches the compile-time
  declaration, the source code and the IDE; no phantom nodes, no new relationship.
- **C. Resolve at query time in Cypher:** the objections of ADR-0006 option A (logic repeated in every query, rules hard
  to express, tests only against a database).

Dispatch:

- **Infer `super` calls** from "the caller overrides the target": misses `super.otherMethod()`.
- **Record the instruction**, which bytecode states explicitly and ASM already passes to the parser.

## Decision

We will point calls at the declared method (option B) and record how each call is invoked.

1. **Call properties.** Every `CALLS` relationship carries:
   - `invocation`: `VIRTUAL`, `INTERFACE`, `SPECIAL` or `STATIC`, from the opcode of the instruction;
   - `receiver`: the class named in the instruction (the qualifying type), always set.
2. **Resolution.** For every call whose target `C#m` belongs to a class `C` included in the analysis but not declared
   by `C`, the resolver looks the method up like the JVM:
   - first along the superclass chain, stopping at the first class that declares it;
   - then in the superinterfaces, with the "nearest declaration on every path" rule of ADR-0006, but only if the chain
     ended at an implicit supertype (`java/lang/Object`, `Enum`, `Record`), as for `abstractService.pay()`. A chain
     that reaches an explicit superclass outside the analysis (`extends HttpServlet`) stops there: that class may
     declare the method, and the analysis cannot know.

   If exactly one declaration is found inside the analysis, the call targets it, and `receiver` keeps `C`. Static and
   instance methods are resolved alike. If none is found inside the analysis (the chain leaves it, e.g.
   `Visibility#name()` declared in `java/lang/Enum`) or more than one is found (conflicting `default` methods), the call
   is left as written in bytecode: no link is invented.
3. **Resolution query.** The query of ADR-0006 follows `OVERRIDES` only for calls that dispatch:

   ```cypher
   MATCH (caller:Method)-[call:CALLS]->(declared:Method)<-[:OVERRIDES*0..]-(impl:Method)
   WHERE NOT impl.isAbstract
     AND (impl = declared OR call.invocation IN ['VIRTUAL', 'INTERFACE'])
   RETURN DISTINCT caller.signature, impl.signature
   ```

   `DISTINCT` is needed because two calls of the same method through different receivers (`sub.foo()`,
   `base.foo()`) now point at the same declaration.

Two principles are made explicit by this decision:

- **No bytecode information is lost.** A derived fact may replace the shape of a fact read from bytecode only if the
  original stays recorded (`receiver`). This refines the rule followed by ADR-0007 ("facts are never changed").
- **A superset of candidates is better than no answer or a false one.** An AI assistant reasons well on "one of these",
  and guesses when it gets nothing.

## Consequences

- Calls to inherited instance and static methods, and to interface methods through abstract classes, resolve to their
  declaration. A method without `DECLARES` is again outside the analysis, except for the rare ambiguous calls left
  unresolved.
- `super` and static calls resolve to exactly one method; the false recursion disappears.
- The resolution query stays as simple as in ADR-0006.
- `CALLS` no longer mirrors the instruction literally: questions about the bytecode reference (e.g. "which calls go
  through `PublicSub`?" before overriding a method there) use `receiver`.
- A resolver bug would now corrupt `CALLS` itself rather than a separate relationship; it stays detectable by comparing
  `receiver` with the owner of the target, and the resolution rules are covered by unit tests.
- Virtual calls resolved to a superclass also return overrides from sibling branches (a superset: the right answer is
  always included). Restricting dispatch to the receiver's subtypes is deferred; `receiver` keeps it possible, e.g. in
  an MCP tool.
- Methods inherited from outside the analysis (the JDK, framework superclasses) stay on nodes named after the receiver.
- Calls to visibility bridges are unchanged: the bridge is declared, so there is nothing to resolve (ADR-0007).
