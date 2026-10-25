package com.jgraph.mcp.transport;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The client side of an MCP connection over STDIO, for tests: writes JSON-RPC messages to the standard input of the
 * server, one per line, and collects what the server writes to its standard output.
 * <p>
 * It speaks raw JSON-RPC instead of using the client of the MCP SDK, whose STDIO transport has the same unserialized
 * outbound queue as the server one (ADR-0011): concurrent calls through it could fail on the client side and blame the
 * server. Writing requests one after the other from one thread, without waiting for responses, is also what an
 * assistant does when it runs tools in parallel.
 * <p>
 * A daemon thread reads the server output all the time, so the server never blocks on a full pipe. Every wait has a
 * timeout: a lost response must fail the test, never hang it.
 */
final class JsonRpcPeer implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OutputStream toServer;
    /** Every message the server wrote, in order; filled by the reading thread, drained by the test thread. */
    private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    private final Map<Integer, JsonNode> responses = new HashMap<>();
    private final List<String> malformedLines = new CopyOnWriteArrayList<>();
    /** Set once the server output is closed: no response can arrive after what is already received. */
    private volatile boolean outputClosed;

    /**
     * @param toServer   standard input of the server
     * @param fromServer standard output of the server
     */
    JsonRpcPeer(OutputStream toServer, InputStream fromServer) {
        this.toServer = toServer;
        Thread.ofPlatform().daemon().name("json-rpc-peer-reader").start(() -> readLines(fromServer));
    }

    static Map<String, Object> request(int id, String method, Map<String, ?> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    static Map<String, Object> toolCall(int id, String tool, Map<String, ?> arguments) {
        return request(id, "tools/call", Map.of("name", tool, "arguments", arguments));
    }

    /**
     * The MCP handshake: {@code initialize} with id 0, its response, then {@code notifications/initialized}. Before
     * it, the server answers no other request.
     *
     * @return the response to {@code initialize}
     */
    JsonNode initialize(Duration timeout) throws IOException, InterruptedException {
        send(request(0, "initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "json-rpc-peer", "version", "1"))));
        JsonNode response = awaitResponses(List.of(0), timeout).get(0);
        if (response == null) {
            throw new AssertionError("No response to initialize within " + timeout);
        }
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        return response;
    }

    /** Writes one message as one line, the framing of the MCP STDIO transport. */
    synchronized void send(Map<String, ?> message) throws IOException {
        toServer.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
    }

    /**
     * Waits until the server has answered every id, the timeout expires or the server closes its output (it exited,
     * for instance: waiting longer is pointless).
     *
     * @return every response received so far, by id: a test can name the ids that never got one
     */
    Map<Integer, JsonNode> awaitResponses(Collection<Integer> ids, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!responses.keySet().containsAll(ids) && System.nanoTime() < deadline) {
            // Short polls, so a closed output is noticed without waiting for the whole timeout
            JsonNode message = received.poll(100, TimeUnit.MILLISECONDS);
            if (message == null) {
                if (outputClosed && received.isEmpty()) {
                    break;
                }
                continue;
            }
            // Responses carry the id of their request; notifications from the server have none
            if (message.has("id") && (message.has("result") || message.has("error"))) {
                responses.put(message.get("id").asInt(), message);
            }
        }
        return Map.copyOf(responses);
    }

    /** Lines of the server output that are not one whole JSON message: two messages written over each other, say. */
    List<String> malformedLines() {
        return List.copyOf(malformedLines);
    }

    /** Closes the standard input of the server: end of input, as when the client goes away. */
    void closeInput() throws IOException {
        toServer.close();
    }

    @Override
    public void close() throws IOException {
        closeInput();
    }

    private void readLines(InputStream fromServer) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(fromServer, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                try {
                    received.add(JSON.readTree(line));
                } catch (JacksonException e) {
                    malformedLines.add(line);
                }
            }
        } catch (IOException e) {
            // The server output was closed: nothing more to read
        } finally {
            outputClosed = true;
        }
    }
}
