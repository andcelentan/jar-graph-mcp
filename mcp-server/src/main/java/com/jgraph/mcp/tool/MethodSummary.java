package com.jgraph.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jgraph.mcp.graph.MethodInfo;

import java.util.List;

/**
 * A method as tools show it to an assistant: names in source form, so it can be passed back to another tool as is.
 *
 * @param className    binary name of the declaring class, e.g. {@code com.acme.Outer$Inner}
 * @param isLambdaBody whether the method is the body of a lambda, moved there by javac: kept, because it is code the
 *                     developer wrote, but flagged; only written to the JSON when true
 */
public record MethodSummary(
        String className,
        String name,
        List<String> parameterTypes,
        String returnType,
        String visibility,
        boolean isStatic,
        boolean isAbstract,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean isLambdaBody
) {

    static MethodSummary of(MethodInfo method) {
        return new MethodSummary(
                SourceNames.binaryName(method.ownerFqcn()),
                method.name(),
                method.parameterTypes(),
                method.returnType(),
                method.visibility(),
                method.isStatic(),
                method.isAbstract(),
                isLambdaBody(method.name()));
    }

    /**
     * javac moves the body of each lambda to a synthetic method named {@code lambda$<enclosing method>$<n>}, e.g.
     * {@code lambda$checkoutAll$0}. Recognized by name because the graph does not record {@code ACC_SYNTHETIC}: {@code $}
     * is legal in identifiers but by convention reserved to generated code (JLS §3.8), so the prefix is reliable.
     */
    private static boolean isLambdaBody(String methodName) {
        return methodName.startsWith("lambda$");
    }

    static List<MethodSummary> of(List<MethodInfo> methods) {
        return methods.stream().map(MethodSummary::of).toList();
    }
}
