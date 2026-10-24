package com.jgraph.mcp.tool;

import com.jgraph.mcp.fixture.FixtureGraph;
import com.jgraph.mcp.fixture.McpFixture;
import com.jgraph.mcp.graph.QueryGraphProperties;
import com.jgraph.mcp.graph.QueryRejectedException;
import com.jgraph.mcp.graph.ReadOnlyCypherRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QueryGraphTool} on the graph of {@link McpFixture}: Cypher written by an assistant runs only if it is
 * read-only and uses no forbidden operation, within a timeout and a row limit (ADR-0009).
 */
class QueryGraphToolIT {

    private static final String PAYMENT_SERVICE = "com/jgraph/mcp/fixture/McpFixture$PaymentService";

    private static QueryGraphTool tool(Duration timeout, int maxRows) {
        QueryGraphProperties properties = new QueryGraphProperties(timeout, maxRows);
        return new QueryGraphTool(new ReadOnlyCypherRunner(FixtureGraph.driver(), properties), properties);
    }

    private static QueryGraphTool tool() {
        return tool(Duration.ofSeconds(5), 100);
    }

    @AfterEach
    void graphIsUnchanged() {
        // A write that slipped through would corrupt the graph shared by every test class
        QueryGraphResult probes = tool().queryGraph(
                "MATCH (n) WHERE n:Probe OR n.probe IS NOT NULL RETURN count(n) AS n");
        assertThat(probes.rows()).containsExactly(Map.of("n", 0L));
    }

    @Test
    void shouldReturnColumnsAndRowsInQueryOrder() {
        QueryGraphResult result = tool().queryGraph(
                "MATCH (c:Class {fqcn: '" + PAYMENT_SERVICE + "'}) RETURN c.kind AS kind, c.fqcn AS fqcn");

        assertThat(result.columns()).containsExactly("kind", "fqcn");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst()).containsExactly(
                Map.entry("kind", "INTERFACE"), Map.entry("fqcn", PAYMENT_SERVICE));
        assertThat(result.truncated()).isFalse();
        assertThat(result.note()).isNull();
    }

    @Test
    void shouldTurnNodesAndRelationshipsIntoPlainMaps() {
        QueryGraphResult result = tool().queryGraph(
                "MATCH (c:Class {fqcn: '" + PAYMENT_SERVICE + "'})-[r:DECLARES]->(m:Method) RETURN c, r, m");

        Map<String, Object> row = result.rows().getFirst();
        assertThat(row.get("c")).isEqualTo(Map.of(
                "labels", List.of("Class"),
                "properties", Map.of("fqcn", PAYMENT_SERVICE, "simpleName", "McpFixture$PaymentService",
                        "kind", "INTERFACE", "isAbstract", true)));
        assertThat(row.get("r")).isEqualTo(Map.of("type", "DECLARES", "properties", Map.of()));
        assertThat(row.get("m")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("labels", List.of("Method"))
                .extractingByKey("properties").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("name", "pay")
                .containsEntry("parameterTypes", List.of("java.lang.String"));
    }

    @Test
    void shouldTurnPathsIntoAlternatingNodesAndRelationships() {
        QueryGraphResult result = tool().queryGraph(
                "MATCH p = (:Class {fqcn: '" + PAYMENT_SERVICE + "'})-[:DECLARES]->(:Method) RETURN p");

        List<?> path = (List<?>) result.rows().getFirst().get("p");
        assertThat(path).hasSize(3);
        assertThat(((Map<?, ?>) path.get(0)).get("labels")).isEqualTo(List.of("Class"));
        assertThat(((Map<?, ?>) path.get(1)).get("type")).isEqualTo("DECLARES");
        assertThat(((Map<?, ?>) path.get(2)).get("labels")).isEqualTo(List.of("Method"));
    }

    @Test
    void shouldKeepNullValuesAndAllowSubqueries() {
        QueryGraphResult result = tool().queryGraph(
                "CALL { MATCH (c:Class) RETURN count(c) AS classes } RETURN classes, null AS nothing");

        assertThat(result.rows().getFirst()).containsKeys("classes", "nothing");
        assertThat(result.rows().getFirst().get("nothing")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE (p:Probe) RETURN p",
            "MERGE (p:Probe {id: 1})",
            "MATCH (c:Class) SET c.probe = true",
            "MATCH (c:Class) REMOVE c.kind",
            "MATCH (m:Method) DETACH DELETE m",
            "MATCH (c:Class) CALL (c) { SET c.probe = true } RETURN count(c)",
            "CREATE INDEX probe_index IF NOT EXISTS FOR (p:Probe) ON (p.id)"})
    void shouldRejectWrites(String cypher) {
        assertThatThrownBy(() -> tool().queryGraph(cypher))
                .isInstanceOf(QueryRejectedException.class)
                .hasMessageContaining("Only read-only queries are allowed");
    }

    @ParameterizedTest
    // All classified READ_ONLY by Neo4j: only the plan operators give them away
    @ValueSource(strings = {
            "LOAD CSV FROM 'file:///etc/passwd' AS row RETURN row",
            "CALL db.labels()",
            "CALL db.labels() YIELD label RETURN label",
            "SHOW TRANSACTIONS",
            "SHOW SETTINGS",
            "TERMINATE TRANSACTIONS 'neo4j-transaction-1'"})
    void shouldRejectForbiddenOperationsEvenWhenNeo4jCallsThemReadOnly(String cypher) {
        assertThatThrownBy(() -> tool().queryGraph(cypher))
                .isInstanceOf(QueryRejectedException.class)
                .hasMessageContaining("forbidden operations");
    }

    @Test
    void shouldTruncateAtTheRowLimit() {
        QueryGraphResult result = tool(Duration.ofSeconds(5), 3).queryGraph("MATCH (m:Method) RETURN m.signature");

        assertThat(result.rows()).hasSize(3);
        assertThat(result.truncated()).isTrue();
        assertThat(result.note()).contains("Truncated").contains("3 returned");
    }

    @Test
    void shouldStopQueriesThatRunTooLong() {
        // Ten billion rows: far beyond a 300 ms budget
        assertThatThrownBy(() -> tool(Duration.ofMillis(300), 100).queryGraph(
                "UNWIND range(1, 100000) AS a UNWIND range(1, 100000) AS b RETURN count(*) AS rows"))
                .isInstanceOf(QueryRejectedException.class)
                .hasMessageContaining("stopped after");
    }

    @Test
    void shouldExplainAnEmptyResult() {
        QueryGraphResult result = tool().queryGraph(
                "MATCH (c:Class {fqcn: 'com.jgraph.mcp.fixture.McpFixture.PaymentService'}) RETURN c");

        // Dots instead of slashes: the most common mistake
        assertThat(result.rows()).isEmpty();
        assertThat(result.note()).contains("'/'").contains("graph://model");
    }

    @Test
    void shouldReportInvalidCypher() {
        assertThatThrownBy(() -> tool().queryGraph("MATCH (c:Class RETURN c"))
                .isInstanceOf(QueryRejectedException.class)
                .hasMessageContaining("Neo4j rejected the query");
    }

    @Test
    void shouldRejectBlankQueries() {
        assertThatIllegalArgumentException().isThrownBy(() -> tool().queryGraph("  "));
    }
}
