# 11. Serialize tool calls over STDIO with immediate execution

Date: 2026-10-25

## Status

Accepted. Amends decision 2 of [ADR-0009](0009-mcp-server-with-spring-ai.md).

## Context

[ADR-0009](0009-mcp-server-with-spring-ai.md) serves MCP over STDIO with the transport Spring AI configures from the
MCP SDK 2.0.0, `StdioServerTransportProvider`. Used from Claude Code, a `find_callers` call never got its response:
the client waited for 20 minutes, while three other calls sent at the same time were answered in milliseconds.

The cause, read in the bytecode of the SDK (`javap`) and reproduced:

- Every outgoing message goes through one Reactor queue, `Sinks.many().unicast().onBackpressureBuffer()`, filled with
  `tryEmitNext`. That queue accepts one producer at a time: a second thread emitting at the same instant gets
  `FAIL_NON_SERIALIZED`, and the transport turns it into `RuntimeException: Failed to enqueue message`, logged and
  dropped. The response is lost and the client is never told.
- A `SYNC` server runs every tool call with `subscribeOn(Schedulers.boundedElastic())`, one thread per call
  (`McpServerFeatures.AsyncToolSpecification.fromSync`), so two calls finishing together are two producers. An
  assistant runs tools in parallel all the time.
- The lost response is not the only damage. The error reaches the end of the inbound pipeline of the transport,
  `inboundSink.asFlux().flatMap(...).subscribe()`, a subscription without an error handler: it ends with
  `ErrorCallbackNotImplemented`, the calls in flight are cancelled, and from then on the server reads standard input
  but handles no request. After the first collision the server never answers again.
- Measured on the packaged server, with 200 calls written without waiting for responses: 28, 29 and 109 answered in
  three runs; in the last one, a call sent afterwards, alone, got no answer.
- The SDK has an option, `immediateExecution`, that runs a synchronous tool on the thread delivering the request
  instead of `boundedElastic`. The STDIO transport delivers requests from its single thread reading standard input.
  Spring AI turns the option on by itself for servlet applications, not for a server without a web stack like this
  one.
- The Streamable HTTP transports are not affected: they give every request its own response stream and write under a
  lock.

Options considered:

- **Wait for an upstream fix**: the local server keeps hanging assistants meanwhile.
- **`synchronized` tool methods**: the response is written after the method returns, outside the lock. It narrows the
  window without closing it.
- **An own STDIO transport** on the public SPI of the SDK (`McpServerTransportProvider`), writing whole lines under a
  lock: tool calls stay parallel. It was written and passes the same tests, but it is about 210 lines of transport,
  plus its own specification, on an SPI that may change with every upgrade.
- **`immediateExecution(true)` for STDIO**: about ten lines of configuration, and the 200 calls all get their
  response. Calls are served one at a time: 200 calls took about 1.5 s longer than with the own transport; with the
  three or four calls an assistant sends at once, each taking milliseconds, the difference does not show. A slow
  `query_graph` delays the calls behind it, at most by its timeout.

## Decision

When the server runs over STDIO (`spring.ai.mcp.server.stdio=true`), `StdioTransportConfiguration` declares a
`McpSyncServerCustomizer` that sets `immediateExecution(true)`. Tool calls run on the single thread reading standard
input, one at a time in arrival order, and every response is emitted by that thread: the queue of the SDK never sees
two producers.

`StdioTransportIT` starts the packaged jar as an MCP client does, writes 200 tool calls without waiting and requires
every response; it also requires the server to exit when the client closes standard input. It talks raw JSON-RPC
through a test peer, since the STDIO client of the SDK has the same queue for its outgoing messages. It is the
end-to-end test ADR-0009 planned, through that peer instead of the SDK client.

## Consequences

- Parallel tool calls from an assistant all get their response, served one after the other.
- The fix depends on how the SDK delivers requests: if an upgrade delivers them from several threads, or moves
  synchronous tools elsewhere, responses can be lost again. `StdioTransportIT` shows it, and must pass after every
  upgrade of the MCP SDK or Spring AI.
- If serial calls ever become a real cost (many slow `query_graph` calls, for instance), the own transport considered
  above is the next step, through a new ADR.
- Spring AI injects a single `McpSyncServerCustomizer`. It declares its own one only for servlet applications, which
  never run over STDIO, so the two do not coexist; a customizer added for another purpose must be merged with this
  one.
- Streamable HTTP, planned for AWS, keeps the transport of Spring AI unchanged: Spring AI already turns immediate
  execution on there, and each request has its own thread and response stream.
- On Windows the packaged jar is locked while an MCP client runs it, so `package` (and the integration test) needs that
  server stopped first.
