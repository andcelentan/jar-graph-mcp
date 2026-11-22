package com.jgraph.mcp.security;

import com.jgraph.mcp.JarGraphMcpApplication;
import com.jgraph.mcp.fixture.FixtureGraph;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specification of the {@code http} profile (ADR-0013): the server, started for real on a port, answers MCP over
 * stateless Streamable HTTP only to requests carrying a valid access token, and tells a client without one where to
 * get it. Tokens come from {@link TestAuthorizationServer}, so they go through the same discovery, key download and
 * validation as in production.
 * <p>
 * The configuration is passed as the environment variables production sets, so the wiring of the profile is tested
 * too. Every rejected token is a valid token with one thing wrong, so each test proves one check.
 */
@SpringBootTest(classes = JarGraphMcpApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("http")
class HttpSecurityIT {

    private static final String SCOPE = "graph:read";
    private static final String ALLOWED_ORIGIN = "https://allowed.test";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern RESOURCE_METADATA = Pattern.compile("resource_metadata=\"([^\"]+)\"");

    private static final TestAuthorizationServer AUTHORIZATION_SERVER = TestAuthorizationServer.start();
    // Chosen before the context starts: the resource URL must name the address the server will listen on
    private static final int PORT = freePort();
    private static final String BASE_URL = "http://127.0.0.1:" + PORT;
    private static final String RESOURCE_URL = BASE_URL + "/mcp";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("NEO4J_URI", FixtureGraph::boltUrl);
        registry.add("NEO4J_USERNAME", () -> "neo4j");
        registry.add("NEO4J_PASSWORD", () -> "unused");
        registry.add("MCP_OAUTH_ISSUER_URI", AUTHORIZATION_SERVER::issuer);
        registry.add("MCP_RESOURCE_URL", () -> RESOURCE_URL);
        registry.add("MCP_REQUIRED_SCOPE", () -> SCOPE);
        registry.add("MCP_ALLOWED_ORIGINS", () -> ALLOWED_ORIGIN);
    }

    @AfterAll
    static void stopAuthorizationServer() {
        AUTHORIZATION_SERVER.close();
    }

    // ---- A client without a token learns where to get one (RFC 9728) ----

    @Test
    void shouldAnswer401WithTheAddressOfTheMetadataWhenTheTokenIsMissing() {
        HttpResponse<String> response = post(listTools(1), Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response)).startsWith("Bearer").contains("resource_metadata=\"");
    }

    @Test
    void shouldPublishTheMetadataWithoutAToken() {
        // Followed from the 401, as a client that has never seen this server does
        String metadataUrl = resourceMetadataUrl(post(listTools(1), Map.of()));

        HttpResponse<String> response = get(metadataUrl);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode metadata = JSON.readTree(response.body());
        assertThat(metadata.path("resource").asString()).isEqualTo(RESOURCE_URL);
        assertThat(texts(metadata.path("authorization_servers"))).containsExactly(AUTHORIZATION_SERVER.issuer());
        assertThat(texts(metadata.path("scopes_supported"))).containsExactly(SCOPE);
    }

    // ---- Authentication: who are you? Any doubt is 401 ----

    @Test
    void shouldRejectAMalformedToken() {
        assertUnauthorized("not-a-jwt");
    }

    @Test
    void shouldRejectAnUnsignedToken() {
        assertUnauthorized(validToken().unsigned().serialize());
    }

    @Test
    void shouldRejectATokenSignedWithAnotherKey() {
        assertUnauthorized(validToken().signedWithForeignKey().serialize());
    }

    @Test
    void shouldRejectAnExpiredToken() {
        // Five minutes: beyond the clock skew resource servers tolerate
        assertUnauthorized(validToken().expired(Duration.ofMinutes(5)).serialize());
    }

    @Test
    void shouldRejectATokenOfAnotherIssuer() {
        assertUnauthorized(validToken().issuer("https://elsewhere.test").serialize());
    }

    @Test
    void shouldRejectATokenIssuedForAnotherService() {
        // Same issuer, valid signature, another audience: the confused deputy
        assertUnauthorized(validToken().audience("https://other-service.test").serialize());
    }

    // ---- Authorization: you are known, but may you? A missing permission is 403 ----

    @Test
    void shouldForbidATokenWithoutScope() {
        assertForbiddenForScope(validToken().withoutScope().serialize());
    }

    @Test
    void shouldForbidATokenWithAnotherScope() {
        assertForbiddenForScope(AUTHORIZATION_SERVER.token(RESOURCE_URL, "profile").serialize());
    }

    // ---- The happy path ----

    @Test
    void shouldServeAToolCallWithAValidToken() {
        HttpResponse<String> response = post(callFindImplementations(7), bearer(validToken().serialize()));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("id").asInt()).isEqualTo(7);
        assertThat(body.has("error")).as(response.body()).isFalse();
        assertThat(body.path("result").path("isError").asBoolean()).as(response.body()).isFalse();
        assertThat(body.path("result").toString()).contains("CardPaymentService");
    }

    @Test
    void shouldAnswerEveryCallSentInParallel() {
        String token = validToken().serialize();
        List<Integer> ids = IntStream.rangeClosed(1, 50).boxed().toList();

        List<CompletableFuture<HttpResponse<String>>> calls = ids.stream()
                .map(id -> http.sendAsync(postRequest(callFindImplementations(id), bearer(token)),
                        HttpResponse.BodyHandlers.ofString()))
                .toList();

        assertThat(calls).allSatisfy(call -> {
            HttpResponse<String> response = call.orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(JSON.readTree(response.body()).path("result").path("isError").asBoolean()).isFalse();
        });
        // Every response answers its own request: none lost, none swapped
        assertThat(calls.stream().map(call -> JSON.readTree(call.join().body()).path("id").asInt()))
                .containsExactlyInAnyOrderElementsOf(ids);
    }

    // ---- Origin: not CORS, an explicit check before the token (DNS rebinding) ----

    @Test
    void shouldForbidADisallowedOriginEvenWithAValidToken() {
        Map<String, String> headers = new HashMap<>(bearer(validToken().serialize()));
        headers.put("Origin", "https://evil.test");

        assertThat(post(listTools(1), headers).statusCode()).isEqualTo(403);
    }

    @Test
    void shouldForbidADisallowedOriginBeforeLookingAtTheToken() {
        // An invalid token, which the token filter answers with 401 as soon as it reads it: 403 proves the Origin
        // check ran first. Without a token the order would not show, since that filter lets such requests through.
        Map<String, String> headers = new HashMap<>(bearer("not-a-jwt"));
        headers.put("Origin", "https://evil.test");

        assertThat(post(listTools(1), headers).statusCode()).isEqualTo(403);
    }

    @Test
    void shouldServeAnAllowedOrigin() {
        Map<String, String> headers = new HashMap<>(bearer(validToken().serialize()));
        headers.put("Origin", ALLOWED_ORIGIN);

        assertThat(post(listTools(1), headers).statusCode()).isEqualTo(200);
    }

    // ---- A configuration that is missing stops the server, a default never stands in for it ----

    @ParameterizedTest
    @ValueSource(strings = {"MCP_OAUTH_ISSUER_URI", "MCP_RESOURCE_URL", "MCP_REQUIRED_SCOPE"})
    void shouldRefuseToStartWithoutARequiredSetting(String missing) {
        Map<String, Object> settings = new HashMap<>(Map.of(
                "server.port", 0,
                "MCP_OAUTH_ISSUER_URI", AUTHORIZATION_SERVER.issuer(),
                "MCP_RESOURCE_URL", RESOURCE_URL,
                "MCP_REQUIRED_SCOPE", SCOPE));
        settings.remove(missing);
        SpringApplicationBuilder application = new SpringApplicationBuilder(JarGraphMcpApplication.class)
                .profiles("http")
                .properties(settings);

        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext started = application.run()) {
                // Reached only if the server started anyway: closing it keeps the port free
            }
        }).hasStackTraceContaining(missing);
    }

    // ---- Helpers ----

    private TestAuthorizationServer.Token validToken() {
        return AUTHORIZATION_SERVER.token(RESOURCE_URL, SCOPE);
    }

    private void assertUnauthorized(String token) {
        HttpResponse<String> response = post(callFindImplementations(1), bearer(token));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(wwwAuthenticate(response)).startsWith("Bearer");
    }

    private void assertForbiddenForScope(String token) {
        HttpResponse<String> response = post(callFindImplementations(1), bearer(token));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        // RFC 6750: the client learns that a new login would not help, a wider scope would
        assertThat(wwwAuthenticate(response)).contains("error=\"insufficient_scope\"");
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private static String listTools(int id) {
        return jsonRpc(id, "tools/list", Map.of());
    }

    private static String callFindImplementations(int id) {
        return jsonRpc(id, "tools/call", Map.of(
                "name", "find_implementations",
                "arguments", Map.of("className", "PaymentService", "methodName", "pay")));
    }

    private static String jsonRpc(int id, String method, Map<String, ?> params) {
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    private HttpResponse<String> post(String body, Map<String, String> headers) {
        return send(postRequest(body, headers));
    }

    private HttpRequest postRequest(String body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(RESOURCE_URL))
                .timeout(TIMEOUT)
                // Streamable HTTP: the client accepts both a plain JSON answer and an SSE stream
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return request.build();
    }

    private HttpResponse<String> get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Request to the server failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + request.uri(), e);
        }
    }

    private static String wwwAuthenticate(HttpResponse<String> response) {
        return response.headers().firstValue("WWW-Authenticate").orElse("");
    }

    private static String resourceMetadataUrl(HttpResponse<String> unauthorized) {
        Matcher matcher = RESOURCE_METADATA.matcher(wwwAuthenticate(unauthorized));
        assertThat(matcher.find()).as("resource_metadata in WWW-Authenticate: %s", wwwAuthenticate(unauthorized))
                .isTrue();
        return matcher.group(1);
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the server under test", e);
        }
    }
}
