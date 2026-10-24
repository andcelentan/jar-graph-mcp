package com.jgraph.mcp.cypher;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * MCP resource {@code graph://model}: the graph model and the rules a correct query must follow, for assistants that
 * write Cypher with {@code query_graph} (ADR-0009). A resource is a document the assistant reads, not an action it
 * runs. Served from {@code graph-model.md}, read once at startup: a change to the graph model must update that file.
 */
@Component
public class GraphModelResource {

    static final String LOCATION = "graph-model.md";

    private final String model;

    public GraphModelResource() {
        try {
            this.model = new ClassPathResource(LOCATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the graph model from the classpath: " + LOCATION, e);
        }
    }

    @McpResource(
            uri = "graph://model",
            name = "graph-model",
            title = "Code graph model",
            description = "Nodes, relationships, properties and rules of the code graph, with example queries. Read it "
                    + "before writing Cypher for query_graph.",
            mimeType = "text/markdown")
    public String graphModel() {
        return model;
    }
}
