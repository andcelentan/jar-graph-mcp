# 9. MCP server with Spring AI: STDIO first, read-only graph queries

Date: 2026-10-18

## Status

Accepted, amended by [ADR-0010](0010-guard-query-graph-by-plan-operators.md) (query_graph checks) and
[ADR-0011](0011-serialize-stdio-tool-calls.md) (STDIO tool calls)

## Context

The graph built by the analyzer is only useful once an AI assistant can query it. The
[Model Context Protocol](https://modelcontextprotocol.io/) (MCP) lets an assistant (Claude Code, Claude Desktop,
IDE plugins) discover and call *tools* exposed by a server. The `mcp-server` module is still an IDE placeholder.

Questions to settle:

- **Library.** Options considered:
  - the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`), used directly: light, explicit, more code to wire;
  - **Spring AI MCP Server** (Spring AI 2.0.1, on Spring Boot 4.1.1 and MCP SDK 2.0.0): auto-configuration, tools
    declared with annotations (`@McpTool`, `@McpToolParam`), transports selected by starter and properties;
  - implementing the JSON-RPC protocol by hand: no reason to.
- **Transport.** STDIO: the client starts the server as a process and talks over stdin/stdout; ideal locally, no
  network, no authentication. Streamable HTTP (`spring-ai-starter-mcp-server-webmvc`, `protocol=STREAMABLE`): needed
  for a shared server on AWS (ADR roadmap), but its endpoint is unauthenticated by default.
- **How an assistant names a method.** It reasons with source names (`OrderService`, `save(Order)`), not bytecode
  names (`com/acme/OrderService#save(Lcom/acme/Order;)V`). It may not know the package of a class, since it does not
  always have the whole code base in context, nor which overload it means.
- **What an empty result means.** Some calls have no implementation in the analysis (Spring Data repositories, Feign
  clients are generated at run time; abstract methods with no implementation): an empty answer would make the
  assistant guess, the failure this project exists to prevent.
- **Dedicated tools or free exploration.** Neo4j's own MCP servers expose a schema tool and a generic read-only Cypher
  tool: the assistant can ask anything, but must get every rule of the graph right in every query (dispatch only for
  `VIRTUAL`/`INTERFACE` calls, no bridges...). A forgotten rule produces a wrong answer that looks like data, and an
  unbounded traversal can overload the database. Dedicated tools encode the rules and are correct by construction, but
  cannot answer unforeseen questions.

## Decision

1. **Spring AI MCP Server** (Spring AI 2.0.1, Spring Boot 4.1.1) in the `mcp-server` module, managed through the
   Spring Boot and Spring AI BOMs. Spring AI builds on the official SDK, so both levels stay visible; it is also the
   idiomatic choice for a Spring code base. Tools are plain Spring beans with `@McpTool` methods, `SYNC` server type.
2. **STDIO first** (`spring-ai-starter-mcp-server`, `spring.ai.mcp.server.stdio=true`). Standard output carries only
   JSON-RPC messages: no banner, no web server, logs on standard error. Tools do not depend on the transport, so
   Streamable HTTP can be added later with its own ADR, together with authentication (OAuth 2.1, as planned for AWS).
3. **Read-only access to Neo4j.** The server only reads: every query runs in a read transaction, through a query layer
   (`GraphQueries`) separate from the tools, which stay thin adapters (MCP request → query → response). It uses the
   Neo4j driver directly, configured from the same environment variables as the analyzer
   ([ADR-0004](0004-secrets-from-environment-variables.md)); no Spring Data Neo4j, since the queries are graph
   traversals rather than entity mapping.
4. **Dedicated tools for the core questions**, the primary way to query the graph:
   - `find_implementations`: given a method, the code that may run when it is called. It wraps the resolution query of
     [ADR-0008](0008-point-calls-at-declared-method.md), starting from the method itself.
   - `find_callers`: given a method, the methods whose calls may run it. The same pattern read the other way: a call
     reaches the method if it targets the method itself, or a method it overrides through a call that dispatches.
   - `describe_class`: kind, supertypes, known subtypes and declared methods (bridges excluded) of a class: where an
     assistant starts to find its way before asking about methods.
5. **A guarded tool for free exploration**, `query_graph`, for questions the dedicated tools do not cover:
   - read-only by two independent checks: the query is first run with `EXPLAIN` and rejected unless Neo4j classifies it
     as read-only (the technique of Neo4j's own MCP server), then executed in a read transaction;
   - a timeout and a maximum number of returned rows;
   - its description tells the assistant to prefer the dedicated tools when they apply.

   A read-only database user would add a third barrier, but roles are not available in Neo4j Community; it is planned
   for the managed database on AWS.
6. **The graph model as an MCP resource**, `graph://model`: nodes, relationships, properties and the rules a correct
   query must follow (dispatch filter, bridges, stub nodes). It is what lets an assistant write correct Cypher for
   `query_graph`. It is a Markdown file in the module, served as is: a change to the graph model must update it.
7. **Source-style input, lenient matching.** Tools take a class name as the assistant knows it: simple
   (`OrderService`), partially or fully qualified (`com.acme.OrderService`), nested classes with `.` or `$`, matched on
   whole name segments (`PaymentService` never matches `CardPaymentService`); and a method name. Every matching class
   and every overload is returned, each with its class and parameter types, so the assistant chooses instead of the
   tool guessing or refusing. Tools give context, not proof: a partial answer is better than a refusal, never at the
   cost of a wrong one. One query per tool call.
8. **Never an empty answer without a reason.** Results are structured objects with an explicit `note` when there is
   nothing to show: unknown class or method, "abstract, no implementation in the analysis", or "implemented at run time
   by Spring Data" for interfaces extending Spring Data repository types (recognizable from their supertypes in the
   graph). Bridge methods are never listed (ADR-0007).
9. **Tests.** `GraphQueries` against a real Neo4j (Testcontainers, [ADR-0005](0005-integration-tests-with-testcontainers.md)),
   loaded by running the analyzer pipeline on compiled fixtures, so the tests exercise the same graph the server
   reads in production. Tool beans are tested for input parsing and notes; one end-to-end test starts the server over
   STDIO and calls a tool through the MCP client of the SDK.

## Consequences

- An assistant can ask "what runs when this is called?", "who calls this?" and "what is this class?" without writing
  Cypher, and gets either an answer or the reason there is none.
- Unforeseen questions remain possible through `query_graph`, but their answers are only as good as the query the
  assistant writes: the model resource and the tool description reduce the risk, they do not remove it. Questions that
  keep coming back through `query_graph` are candidates for new dedicated tools.
- The `mcp-server` module becomes a Spring Boot application, with a larger footprint and a slower start than a minimal
  server: irrelevant for STDIO and ECS, to be weighed if AWS Lambda is ever considered.
- Spring AI evolves quickly: tool annotations changed between versions, so upgrades must be checked against its release
  notes.
- STDIO serves one assistant on one machine; a server shared by a team needs Streamable HTTP and authentication
  (future ADR).
- The server trusts the graph as written by the analyzer: both must agree on the graph model (properties, relationship
  types). A change to the model is a change to both modules and to the `graph://model` resource.
