package com.jgraph.mcp.graph;

import java.util.List;
import java.util.Map;

/**
 * The result of a query written by an assistant. Its shape is decided by the query, so each row maps a column to a
 * plain value: {@code String}, {@code Long}, {@code Double}, {@code Boolean}, {@code null}, lists and maps of them
 * (see {@link CypherValues}).
 *
 * @param columns   the columns, in the order of the query
 * @param rows      at most {@link QueryGraphProperties#maxRows()} rows, each with the columns in that order
 * @param truncated whether the query had more rows than were returned
 */
public record QueryRows(
        List<String> columns,
        List<Map<String, Object>> rows,
        boolean truncated
) {
}
