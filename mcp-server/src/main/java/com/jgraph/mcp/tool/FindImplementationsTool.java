package com.jgraph.mcp.tool;

import com.jgraph.mcp.graph.GraphQueries;
import com.jgraph.mcp.graph.ImplementationSearch;
import com.jgraph.mcp.graph.Implementations;
import com.jgraph.mcp.tool.FindImplementationsResult.MethodImplementations;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * MCP tool {@code find_implementations}: the code that may run when a method is called, resolved from bytecode
 * (ADR-0006, ADR-0008, ADR-0009). Registered by the Spring AI annotation scanner because it is a bean with
 * {@link McpTool} methods.
 */
@Component
public class FindImplementationsTool {

    private static final String SPRING_DATA_NOTE = "Abstract method of a Spring Data repository: Spring Data "
            + "implements it at run time, deriving the query from the method name or taking it from @Query, so no "
            + "implementation exists in the jar.";
    private static final String NO_IMPLEMENTATION_NOTE = "Abstract method with no implementation in the analysis: "
            + "it may be implemented in another jar, by a lambda or method reference (not analyzed yet), or by a "
            + "proxy generated at run time.";

    private final GraphQueries queries;

    public FindImplementationsTool(GraphQueries queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @McpTool(
            name = "find_implementations",
            description = """
                    Lists the concrete methods that may run when a method is called: the method itself if it has a \
                    body, plus every override of it in the analyzed application, resolved from bytecode. Use it to \
                    find the implementations of an interface or abstract method instead of searching the source. \
                    Every class matching the name and every overload is returned, each with its class and parameter \
                    types; when nothing is found, the note explains why.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Find implementations",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public FindImplementationsResult findImplementations(
            @McpToolParam(description = "Class or interface that declares the method: simple name (PaymentService) "
                    + "or qualified name (com.acme.PaymentService); nested classes as Outer.Inner")
            String className,
            @McpToolParam(description = "Method name only, without parameters, e.g. pay")
            String methodName) {
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("A method name is required, e.g. pay");
        }
        ImplementationSearch search =
                queries.findImplementations(SourceNames.dottedClassName(className), methodName.strip());

        List<MethodImplementations> methods = search.methods().stream()
                .map(FindImplementationsTool::toMethodImplementations)
                .toList();
        return new FindImplementationsResult(methods,
                methods.isEmpty() ? notFoundNote(className, methodName, search.classes()) : null);
    }

    private static MethodImplementations toMethodImplementations(Implementations found) {
        String note = null;
        if (found.implementations().isEmpty()) {
            note = found.springDataRepository() ? SPRING_DATA_NOTE : NO_IMPLEMENTATION_NOTE;
        }
        return new MethodImplementations(
                MethodSummary.of(found.method()),
                MethodSummary.of(found.implementations()),
                note);
    }

    private static String notFoundNote(String className, String methodName, List<String> classes) {
        if (classes.isEmpty()) {
            return ("No class of the analysis is named '%s': it may belong to the JDK, a library or another jar, or "
                    + "the name may be misspelled.").formatted(className);
        }
        List<String> names = classes.stream().map(SourceNames::binaryName).toList();
        return ("%s: no method named '%s' is declared there. It may be inherited from a supertype, or from the JDK "
                + "or a library.").formatted(String.join(", ", names), methodName);
    }
}
