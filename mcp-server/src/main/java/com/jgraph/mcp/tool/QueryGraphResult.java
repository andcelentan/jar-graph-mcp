package com.jgraph.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Answer of {@code query_graph}: the rows of a query whose shape the assistant decided. A {@code null} note is left
 * out of the JSON; {@code null} values inside rows are kept, they are part of the answer.
 *
 * @param columns   the columns, in the order of the query
 * @param rows      one map per row, column to value; nodes and relationships as maps of labels or type and properties
 * @param truncated whether the query had more rows than the limit
 * @param note      why the result is empty or truncated, and what to do
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryGraphResult(
        List<String> columns,
        List<Map<String, Object>> rows,
        boolean truncated,
        String note
) {
}
