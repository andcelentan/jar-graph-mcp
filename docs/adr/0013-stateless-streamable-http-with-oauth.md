# 13. Serve MCP over stateless Streamable HTTP, as an OAuth 2.1 resource server

Date: 2026-11-22

## Status

Accepted. Amends decision 2 of [ADR-0009](0009-mcp-server-with-spring-ai.md) (STDIO first).

## Context

[ADR-0009](0009-mcp-server-with-spring-ai.md) serves MCP over STDIO only: the client starts the server as a child
process on the same machine. The target architecture runs the server as an ECS service, reachable over the network and
shared by several users, so it needs a network transport and a way to know who is calling. ADR-0009 deferred both to
this ADR.

**Transports.** MCP has two over HTTP:

- **HTTP+SSE** (specification 2024-11-05): the client keeps a `GET /sse` stream open for the whole session and sends
  requests to `POST /message`; the response arrives on the stream, not on the connection of the request. Behind a load
  balancer with several tasks the two connections can reach different tasks, so sticky sessions are required, and an
  idle client still holds a connection. Spring AI 2.0.1 starts this transport when nothing else is configured.
- **Streamable HTTP** (specification 2025-03-26): one endpoint (`/mcp`). Each message is a `POST`, and the response
  comes back on that same request, as plain JSON or, when the server wants to send notifications first, as an SSE
  stream that ends with the response. A `GET` may open a stream for messages the server starts itself.

Streamable HTTP runs **with a session** (`Mcp-Session-Id`, state kept in the memory of one task) or **without**.
A session is needed only when the server talks first: sampling, elicitation, notifications it starts. The four tools
and the `graph://model` resource never do: each call runs one read-only query and returns. Spring AI exposes the
three variants as `spring.ai.mcp.server.protocol`: `SSE`, `STREAMABLE`, `STATELESS`.

**Packaging, checked with a spike** (2026-11-22). Replacing `spring-ai-starter-mcp-server` with
`spring-ai-starter-mcp-server-webmvc` puts Tomcat in the jar without forcing it on:

- with the current configuration (`web-application-type: none`, `stdio: true`) the jar answers over standard output
  only, Tomcat never starts, and `StdioTransportIT` (200 calls sent without waiting, ADR-0011) still passes;
- with a profile setting `web-application-type: servlet`, `stdio: false` and `protocol: STATELESS`, `POST /mcp`
  answers `initialize` and `tools/list` as JSON, sends no `Mcp-Session-Id`, serves `tools/list` without a prior
  `initialize`, and answers `GET /mcp` with 405.

**Authorization in MCP.** The MCP authorization specification builds on OAuth 2.1 and makes the MCP server a
**resource server**: it never sees passwords and never issues tokens, it validates the access tokens an authorization
server issued. It requires the server to:

- answer a request without a valid token with `401` and a `WWW-Authenticate` header pointing at its **Protected
  Resource Metadata** (RFC 9728), a JSON document at `/.well-known/oauth-protected-resource` naming the resource, the
  authorization servers it trusts and the scopes it supports. From that document a client it has never seen finds
  where to log in;
- accept only tokens issued **for itself**: clients send the `resource` parameter (RFC 8707) and the authorization
  server writes it in the `aud` claim. Without the audience check, a token valid for another service of the same
  authorization server would be accepted here (confused deputy);
- never pass the token it received on to other services (token passthrough);
- validate the `Origin` header of HTTP requests (DNS rebinding).

The `Origin` check is not what CORS does. In a DNS rebinding attack a page on `evil.example:8080` gets its own name
resolved again to the address of the server, and calls `evil.example:8080/mcp`: for the browser the page and the target
are the same origin, so the same-origin policy and CORS never apply. On the server side, Spring decides whether a
request is cross-origin by comparing `Origin` with the host of the request itself, equal here, so a CORS configuration
lets the request through. Only an explicit check of `Origin` against a list of allowed origins stops it.

**Authorization server, checked in the AWS documentation** (2026-11-22). Amazon Cognito supports resource indicators
since 2025-10-27: with managed login, in the authorization code and implicit grants, the `resource` value becomes the
`aud` of the access token, and refreshed tokens keep it; without it, Cognito access tokens carry `client_id` and no
`aud` at all, so an audience check would reject them all. It requires the Essentials or Plus feature plan. Custom
scopes belong to a Cognito resource server and appear in the token as `<resource server identifier>/<scope name>`
(the scope name may contain `:`, not `/`). Essentials
(the default for new user pools) has a free tier of 10,000 monthly active users that does not expire. Whether Cognito
supports dynamic client registration (RFC 7591) is unclear (sources disagree), and the MCP specification of
2026-07-28 deprecates it in favour of Client ID Metadata Documents, still allowing pre-registered clients.

Options considered:

- **Transport.** `SSE`: deprecated by the specification, sticky sessions on ECS. `STREAMABLE`: works, but sessions
  live in one task, so several tasks need sticky sessions or a shared store, and a deploy drops them, for features we
  do not use. `STATELESS`: any task answers any request.
- **Packaging.** Two jars, one per transport: two builds and two artifacts for the same code. HTTP only, also
  locally: trying the server would need an authorization server, or a switch that turns security off, which is the
  shortcut that ends up in production. One jar, STDIO by default and HTTP in a profile: the spike shows it works.
- **Authentication.** A static API key: one shared secret that never expires, identifies nobody and cannot be revoked
  for one user, and that MCP clients do not discover. Opaque tokens with introspection (RFC 7662): immediate
  revocation, but one call to the authorization server per request. JWT access tokens validated locally: the
  signature is checked with the public keys (JWKS) of the issuer, cached; revocation waits for expiry, which short
  token lifetimes bound. Cognito issues JWTs.
- **Protected Resource Metadata.** A controller of our own plus a custom `401`; the security library of the Spring AI
  community (not part of Spring AI, may lag behind 2.0.1); the support Spring Security 7 (Spring Boot 4) has for it in
  the resource server configuration.
- **Scopes.** None (any token for our audience can do everything), one per tool, or one for the whole server. Every
  tool reads the same data and none writes; the cost of `query_graph` is already bounded by its timeout and row limit
  (ADR-0010).
- **Tests.** Spring Security Test `jwt()` or a mocked `JwtDecoder`: they skip decoding, which is what has to be
  proved. An authorization server in a container (Keycloak): heavy and slow, it also tests the browser login, and it
  is not Cognito anyway. Tokens signed by the test with a key of its own, published as a JWKS on a local HTTP server:
  the server goes through the same validation as in production; only the issuer is fake.

## Decision

1. **One jar, two transports.** The server depends on `spring-ai-starter-mcp-server-webmvc`. The default
   configuration stays STDIO without a web server, as today. A Spring profile `http` turns on the web server, sets
   `stdio: false` and `protocol: STATELESS`; the MCP endpoint is `/mcp`. The listen address defaults to `127.0.0.1`
   and is opened by configuration where the container needs it.
2. **HTTP is always authenticated.** There is no unauthenticated HTTP mode, locally either. Unauthenticated local use
   is STDIO.
3. **The server is an OAuth 2.1 resource server** (Spring Security, `spring-boot-starter-oauth2-resource-server`)
   accepting JWT access tokens. Only the issuer is configured: its JWKS is found through its discovery document and
   fetched from the issuer over HTTPS, never from the client, cached and fetched again for an unknown `kid` (key
   rotation). Every request is validated locally: signature with `RS256` only (the algorithm is never taken from the
   token, so `none` or `HS256` tokens are rejected), `exp` and `nbf`, `iss` equal to the issuer, `aud` containing the
   resource URL, then the scope.
   - The resource URL (e.g. `https://mcp.example.com/mcp`) is **one configured value** that feeds both the audience
     check and the metadata document (decision 5), so the two cannot diverge; it is compared exactly.
   - The issuer, the resource URL and the scope name come from environment variables (ADR-0004), with no default in
     the `http` profile: a missing value stops the server at startup, so a deployment that forgot one fails visibly
     instead of trusting a default. An issuer that is configured but unreachable does not stop the startup: requests
     get `401` until it is reachable again.
4. **One scope**, required by every tool and resource, its name configured: `graph:read` by default, and with Cognito
   the full name it writes in the token, `<resource server identifier>/graph:read`. A token without it gets `403`; a
   missing, malformed, expired, foreign or wrongly addressed token gets `401` with the `WWW-Authenticate` header of
   RFC 9728.
5. **Protected Resource Metadata through Spring Security 7**, at `/.well-known/oauth-protected-resource`, public,
   naming the resource URL, the issuer and the configured scope.
6. **An explicit `Origin` check, not CORS.** A request carrying an `Origin` header that is not in the allowed list gets
   `403` before any token check; the list is empty by default, since MCP clients are not browsers, and configurable
   for a browser-based client. A request without `Origin` goes on to the token check. With authentication always on,
   a rebinding page has no token anyway: the check is required by the specification and stays as defence in depth.
7. **The token is never forwarded.** The server calls only Neo4j, with its own credentials.
8. **Tests.** A new specification (`*IT`) runs the `http` profile against the fixture graph with tokens signed by the
   test, and covers: no token, malformed token, unsigned (`alg: none`) token, other signing key, expired, other
   issuer, other audience, no scope, valid token, public metadata, disallowed `Origin` (`403` even with a valid
   token), no `Origin`. It also sends parallel tool calls and requires every response, as `StdioTransportIT`
   does for STDIO. `StdioTransportIT` keeps guarding the default configuration.

## Consequences

- The same jar serves a local assistant over STDIO and a shared server on ECS; only the profile and the environment
  change.
- Stateless lets the ECS service scale out and redeploy without sticky sessions and without dropping clients. The
  server can never start sampling, elicitation or notifications; a tool that needs them requires a new ADR.
- Every HTTP request is checked without calling the authorization server; a revoked token stays usable until it
  expires, so the authorization server must issue short-lived access tokens.
- The issuer and the resource URL become trust anchors: whoever can change them decides which tokens are valid. On
  AWS, write access to the task definition must be restricted like access to a secret.
- The jar grows by the web stack (Tomcat, Spring MVC, Spring Security) even when it runs over STDIO, where none of it
  starts.
- The infrastructure, not the server, decides how clients are registered: for the first version, a client registered
  in Cognito by Terraform, on the Essentials plan with managed login, with a Cognito resource server whose identifier
  is the resource URL. Changing authorization server only changes configuration, the scope name included.
- The load balancer health check needs an unauthenticated endpoint; it is decided with the ECS deployment.
- Spring AI injects a single `McpSyncServerCustomizer` (ADR-0011): the STDIO one stays conditional on
  `spring.ai.mcp.server.stdio=true`, and any customizer needed for HTTP must be merged with it.
- Upgrades of the MCP SDK, Spring AI or Spring Security must pass both transport tests.
