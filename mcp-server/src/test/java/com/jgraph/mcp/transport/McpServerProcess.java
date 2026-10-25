package com.jgraph.mcp.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The packaged MCP server, started as an MCP client starts it: {@code java -jar}, talking over its standard input and
 * output. Its standard error (the logs) goes to a file in {@code target}, so it can never fill a pipe, and is kept for
 * diagnosis.
 * <p>
 * The jar is the one built by {@code package}, passed by failsafe as the system property {@value #JAR_PROPERTY}; the
 * JVM is the one running the test, not whatever {@code java} is on the PATH.
 */
final class McpServerProcess implements AutoCloseable {

    static final String JAR_PROPERTY = "mcp-server.jar";

    private final Process process;
    private final Path stderr;
    private final JsonRpcPeer client;

    private McpServerProcess(Process process, Path stderr) {
        this.process = process;
        this.stderr = stderr;
        this.client = new JsonRpcPeer(process.getOutputStream(), process.getInputStream());
    }

    /** Starts the server on the graph served at {@code boltUrl}, a Neo4j without authentication. */
    static McpServerProcess start(String boltUrl) throws IOException {
        String jar = System.getProperty(JAR_PROPERTY);
        if (jar == null || !Files.isRegularFile(Path.of(jar))) {
            throw new IllegalStateException("No packaged server at " + JAR_PROPERTY + "=" + jar
                    + ": run the integration tests with mvn verify, after package");
        }
        String java = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("Cannot tell which java runs this test"));
        // Next to the jar, in target: kept to read after a failure, removed by mvn clean
        Path stderr = Files.createTempFile(Path.of(jar).toAbsolutePath().getParent(), "stdio-it-", ".log");
        ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar)
                .redirectError(stderr.toFile());
        // The same variables an MCP client sets (ADR-0004); the credentials are ignored without authentication
        builder.environment().putAll(Map.of(
                "NEO4J_URI", boltUrl,
                "NEO4J_USERNAME", "neo4j",
                "NEO4J_PASSWORD", "unused"));
        return new McpServerProcess(builder.start(), stderr);
    }

    JsonRpcPeer client() {
        return client;
    }

    /** The MCP handshake; when it fails, says whether the server is still running and where its log is. */
    void initialize(Duration timeout) throws IOException, InterruptedException {
        try {
            client.initialize(timeout);
        } catch (AssertionError e) {
            String state = process.isAlive() ? "still running" : "exited with code " + process.exitValue();
            throw new AssertionError(e.getMessage() + ": the server is " + state + ", log in " + stderr, e);
        }
    }

    /** The log file of the server, to read when a test fails. */
    Path stderr() {
        return stderr;
    }

    /**
     * @return the exit code, or {@code null} if the server is still running after {@code timeout}
     */
    Integer awaitExit(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) ? process.exitValue() : null;
    }

    /** Kills the server if it is still running, so a failed test never leaves it behind. */
    @Override
    public void close() throws InterruptedException {
        if (process.isAlive()) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
