package com.jgraph.mcp.graph;

import java.util.List;

/**
 * The code that may run when a method is called: the method itself if it has a body, and every override of it.
 * Facts only: turning them into a message for the assistant is the job of the tool.
 *
 * @param method                the method asked about
 * @param implementations       concrete methods that may run, bridges excluded (ADR-0007); empty if there are none in
 *                              the analysis
 * @param springDataRepository  whether the declaring type extends a Spring Data repository type, whose implementation
 *                              is generated at run time and therefore absent from the analysis
 */
public record Implementations(
        MethodInfo method,
        List<MethodInfo> implementations,
        boolean springDataRepository
) {
}
