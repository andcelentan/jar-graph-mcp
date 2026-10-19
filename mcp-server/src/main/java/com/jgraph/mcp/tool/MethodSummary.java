package com.jgraph.mcp.tool;

import com.jgraph.mcp.graph.MethodInfo;

import java.util.List;

/**
 * A method as tools show it to an assistant: names in source form, so it can be passed back to another tool as is.
 *
 * @param className binary name of the declaring class, e.g. {@code com.acme.Outer$Inner}
 */
public record MethodSummary(
        String className,
        String name,
        List<String> parameterTypes,
        String returnType,
        String visibility,
        boolean isStatic,
        boolean isAbstract
) {

    static MethodSummary of(MethodInfo method) {
        return new MethodSummary(
                SourceNames.binaryName(method.ownerFqcn()),
                method.name(),
                method.parameterTypes(),
                method.returnType(),
                method.visibility(),
                method.isStatic(),
                method.isAbstract());
    }

    static List<MethodSummary> of(List<MethodInfo> methods) {
        return methods.stream().map(MethodSummary::of).toList();
    }
}
