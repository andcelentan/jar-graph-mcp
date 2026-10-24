package com.jgraph.mcp.classes;

import com.jgraph.mcp.shared.MethodInfo;

import java.util.List;

/**
 * A class of the analysis with its direct neighbours in the type hierarchy and its declared methods. Facts only:
 * turning them into a description for the assistant is the job of the tool.
 *
 * @param fqcn       internal name, e.g. {@code com/acme/Outer$Inner}
 * @param kind       as stored in the graph: {@code CLASS}, {@code INTERFACE}, {@code ENUM}, {@code RECORD},
 *                   {@code ANNOTATION}
 * @param supertypes the types this class points to with {@code EXTENDS} or {@code IMPLEMENTS}
 * @param subtypes   the classes of the analysis that point to this class with {@code EXTENDS} or {@code IMPLEMENTS}
 * @param methods    the methods the class declares, bridges excluded (ADR-0007)
 */
record ClassInfo(
        String fqcn,
        String kind,
        boolean isAbstract,
        List<TypeLink> supertypes,
        List<TypeLink> subtypes,
        List<MethodInfo> methods
) {
}
