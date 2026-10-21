package com.jgraph.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Answer of {@code describe_class}, serialized to JSON for the assistant: one entry per matching class. A
 * {@code null} note is left out of the JSON.
 *
 * @param classes every class of the analysis matching the name; empty if none, and the note says why
 * @param note    why nothing matched, when {@code classes} is empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DescribeClassResult(
        List<ClassDescription> classes,
        String note
) {
}
