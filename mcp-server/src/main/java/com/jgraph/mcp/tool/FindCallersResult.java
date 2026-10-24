package com.jgraph.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Answer of {@code find_callers}, serialized to JSON for the assistant: one entry per matching method, every matching
 * class and overload included. A {@code null} note is left out of the JSON.
 *
 * @param methods the matching methods with their callers; empty if nothing matched, and the note says why
 * @param note    why nothing matched, when {@code methods} is empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FindCallersResult(
        List<MethodCallers> methods,
        String note
) {

    /**
     * @param method  a method declared with the requested name
     * @param callers the calls that may run it; empty if none is in the analysis, and the note says why
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MethodCallers(
            MethodSummary method,
            List<CallerSummary> callers,
            String note
    ) {
    }
}
