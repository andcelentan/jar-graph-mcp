package com.jgraph.mcp.cypher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Limits of the read-only Cypher queries written by assistants ({@code jar-graph.query-graph.*} in
 * {@code application.yml}): a query that runs longer is terminated by Neo4j, and rows beyond the limit are dropped.
 */
@ConfigurationProperties("jar-graph.query-graph")
public record QueryGraphProperties(
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("100") int maxRows
) {

    public QueryGraphProperties {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("jar-graph.query-graph.timeout must be positive");
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException("jar-graph.query-graph.max-rows must be at least 1");
        }
    }
}
