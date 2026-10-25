package com.jgraph.mcp.transport;

import com.jgraph.mcp.fixture.FixtureGraph;
import com.jgraph.mcp.fixture.McpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static com.jgraph.mcp.transport.JsonRpcPeer.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packaged server over STDIO, end to end (ADR-0009, ADR-0011): started as an MCP client starts it, on the graph of
 * {@link McpFixture}, it answers every call when an assistant runs many tools in parallel, and exits when the client
 * closes its standard input. Without {@link StdioTransportConfiguration} the SDK transport answered between 28 and 109
 * of the 200 calls, then nothing at all: this test is what tells whether an upgrade of the SDK or Spring AI brings the
 * lost responses back.
 */
class StdioTransportIT {

    private static final Duration STARTUP = Duration.ofSeconds(60);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** One call per tool, sent in turn: every query of the server runs while the others write their responses. */
    private static final List<Call> CALLS = List.of(
            new Call("find_callers", Map.of("className", "PaymentService", "methodName", "pay")),
            new Call("find_implementations", Map.of("className", "PaymentService", "methodName", "pay")),
            new Call("describe_class", Map.of("className", "Checkout")),
            new Call("query_graph", Map.of("cypher", "MATCH (c:Class) WHERE c.kind IS NOT NULL RETURN count(c) AS n")));

    private McpServerProcess server;

    @BeforeEach
    void startServer() throws Exception {
        server = McpServerProcess.start(FixtureGraph.boltUrl());
        server.initialize(STARTUP);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.close();
    }

    @Test
    void shouldAnswerEveryCallWhenToolsRunInParallel() throws Exception {
        List<Integer> ids = IntStream.rangeClosed(1, 200).boxed().toList();

        for (int id : ids) {
            Call call = CALLS.get(id % CALLS.size());
            server.client().send(toolCall(id, call.tool(), call.arguments()));
        }
        Map<Integer, JsonNode> responses = server.client().awaitResponses(ids, TIMEOUT);

        assertThat(responses).as("responses by id (server log: %s)", server.stderr())
                .containsKeys(ids.toArray(Integer[]::new));
        assertThat(ids).allSatisfy(id -> {
            JsonNode response = responses.get(id);
            boolean failed = response.has("error") || response.path("result").path("isError").asBoolean();
            assertThat(failed).as("error in response %s", response).isFalse();
        });
        assertThat(server.client().malformedLines()).as("lines that are not one whole message").isEmpty();
    }

    @Test
    void shouldExitWhenTheClientClosesStandardInput() throws Exception {
        server.client().closeInput();

        // Otherwise every session closed by a client would leave a server process behind
        assertThat(server.awaitExit(TIMEOUT)).as("exit code (server log: %s)", server.stderr()).isEqualTo(0);
    }

    private record Call(String tool, Map<String, String> arguments) {
    }
}
