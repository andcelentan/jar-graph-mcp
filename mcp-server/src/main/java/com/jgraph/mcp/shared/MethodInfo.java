package com.jgraph.mcp.shared;

import java.util.List;

/**
 * A method declared by a class of the analysis, as stored in the graph.
 *
 * @param signature      {@code owner#name(descriptor)}, the graph key
 * @param ownerFqcn      JVM internal name of the declaring class, e.g. {@code com/acme/OrderService}
 * @param name           method name
 * @param returnType     dotted Java type, e.g. {@code com.acme.Order}
 * @param parameterTypes dotted Java types, in declaration order
 * @param visibility     {@code PUBLIC}, {@code PROTECTED}, {@code PACKAGE_PRIVATE} or {@code PRIVATE}
 */
public record MethodInfo(
        String signature,
        String ownerFqcn,
        String name,
        String returnType,
        List<String> parameterTypes,
        String visibility,
        boolean isStatic,
        boolean isAbstract
) {
}
