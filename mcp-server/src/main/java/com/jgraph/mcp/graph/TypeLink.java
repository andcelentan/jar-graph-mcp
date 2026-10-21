package com.jgraph.mcp.graph;

/**
 * One direct {@code EXTENDS} or {@code IMPLEMENTS} relationship, seen from a class: the type at the other end.
 *
 * @param fqcn     internal name of the type at the other end
 * @param relation the relationship type, {@code EXTENDS} or {@code IMPLEMENTS}
 * @param analyzed whether that type is a class of the analysis, or a stub node (JDK, libraries)
 */
public record TypeLink(
        String fqcn,
        String relation,
        boolean analyzed
) {
}
