package com.jgraph.mcp.tool;

import com.jgraph.mcp.graph.TypeLink;

import java.util.List;

/**
 * A type next to a class in the hierarchy, as tools show it to an assistant.
 *
 * @param className  binary name, e.g. {@code com.acme.Outer$Inner}
 * @param relation   {@code EXTENDS} or {@code IMPLEMENTS}, read from the class being described
 * @param inAnalysis false for types outside the analysis (JDK, libraries): no tool can tell more about them
 */
public record TypeSummary(
        String className,
        String relation,
        boolean inAnalysis
) {

    static TypeSummary of(TypeLink link) {
        return new TypeSummary(SourceNames.binaryName(link.fqcn()), link.relation(), link.analyzed());
    }

    static List<TypeSummary> of(List<TypeLink> links) {
        return links.stream().map(TypeSummary::of).toList();
    }
}
