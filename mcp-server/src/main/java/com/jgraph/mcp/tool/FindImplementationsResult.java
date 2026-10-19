package com.jgraph.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Answer of {@code find_implementations}, serialized to JSON for the assistant: one entry per matching method, every
 * matching class and overload included. A {@code null} note is left out of the JSON.
 *
 * @param methods the matching methods with their implementations; empty if nothing matched, and the note says why
 * @param note    why nothing matched, when {@code methods} is empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FindImplementationsResult(
        List<MethodImplementations> methods,
        String note
) {

    /**
     * @param method          a method declared with the requested name
     * @param implementations the concrete methods that may run when it is called: the method itself if it has a body,
     *                        plus its overrides; empty if none is in the analysis, and the note says why
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MethodImplementations(
            MethodSummary method,
            List<MethodSummary> implementations,
            String note
    ) {
    }
}
