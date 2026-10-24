package com.jgraph.mcp.cypher;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * MCP tool {@code query_graph}: free exploration of the code graph with read-only Cypher written by the assistant
 * (ADR-0009). The answer is only as good as the query, so the description carries the rules of the graph model and
 * sends the assistant to the dedicated tools first.
 */
@Component
public class QueryGraphTool {

    private static final String EMPTY_NOTE = "The query returned no rows. Common causes: fqcn is the JVM internal "
            + "name with '/' and '$' (com/acme/Outer$Inner), while returnType and parameterTypes use dots; classes "
            + "outside the analysis are stub nodes without kind or DECLARES; relationships go from subtype to "
            + "supertype and from overriding to overridden method. See the resource graph://model.";
    private static final String TRUNCATED_NOTE = "Truncated: the query has more rows than the %d returned. Narrow it, "
            + "aggregate (count, collect) or add ORDER BY ... LIMIT.";

    private final ReadOnlyCypherRunner runner;
    private final int maxRows;

    QueryGraphTool(ReadOnlyCypherRunner runner, QueryGraphProperties properties) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.maxRows = properties.maxRows();
    }

    @McpTool(
            name = "query_graph",
            description = """
                    Runs a read-only Cypher query on the code graph of the analyzed application and returns its \
                    rows (limited in number and time; writes, LOAD CSV, procedure calls and SHOW/TERMINATE \
                    commands are rejected). Prefer find_implementations, find_callers and describe_class when they \
                    answer the question: they apply the rules below for you. Model: \
                    (:Class {fqcn, simpleName, kind, isAbstract}), fqcn being the JVM internal name \
                    (com/acme/Outer$Inner); classes without kind are stubs outside the analysis. \
                    (:Class)-[:EXTENDS|IMPLEMENTS]->(:Class), from subtype to supertype. \
                    (:Class)-[:DECLARES]->(:Method {signature 'owner#name(descriptor)', name, returnType, \
                    parameterTypes, visibility, isStatic, isAbstract, isBridge, isTransactional}): exclude bridges \
                    with NOT m.isBridge. (:Method)-[:CALLS {invocation, receiver}]->(:Method) points at the declared \
                    method; (:Method)-[:OVERRIDES]->(:Method) at the nearest overridden one. Code that may run for a \
                    call: (caller)-[c:CALLS]->(d)<-[:OVERRIDES*0..]-(impl) WHERE NOT impl.isAbstract AND \
                    (impl = d OR c.invocation IN ['VIRTUAL', 'INTERFACE']). Always name relationship types in \
                    variable-length patterns. Full model and examples: resource graph://model.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Query the code graph",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public QueryGraphResult queryGraph(
            @McpToolParam(description = "A single read-only Cypher statement, e.g. MATCH (c:Class)-[:DECLARES]->"
                    + "(m:Method) WHERE NOT m.isBridge RETURN c.fqcn, count(m) AS methods ORDER BY methods DESC "
                    + "LIMIT 10")
            String cypher) {
        if (cypher == null || cypher.isBlank()) {
            throw new IllegalArgumentException("A Cypher query is required, e.g. MATCH (c:Class) RETURN c.fqcn LIMIT 10");
        }
        QueryRows result = runner.run(cypher.strip());
        String note = null;
        if (result.truncated()) {
            note = TRUNCATED_NOTE.formatted(maxRows);
        } else if (result.rows().isEmpty()) {
            note = EMPTY_NOTE;
        }
        return new QueryGraphResult(result.columns(), result.rows(), result.truncated(), note);
    }
}
