package com.jgraph.mcp.tool;

import com.jgraph.mcp.graph.CallerSearch;
import com.jgraph.mcp.graph.Callers;
import com.jgraph.mcp.graph.GraphQueries;
import com.jgraph.mcp.tool.FindCallersResult.MethodCallers;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * MCP tool {@code find_callers}: the calls that may run a method, resolved from bytecode (ADR-0008, ADR-0009).
 * Registered by the Spring AI annotation scanner because it is a bean with {@link McpTool} methods. Specification:
 * {@code FindCallersToolIT}.
 */
@Component
public class FindCallersTool {

    /** The answer an assistant most often misreads: "no callers" is not "dead code". */
    private static final String NO_CALLERS_NOTE = "No caller in the analysis. That does not make it unused: it may be "
            + "an entry point started by the JVM (main), invoked by the framework (request mappings, event listeners, "
            + "scheduled jobs: not recognized yet), by reflection, from another jar, or through a method reference "
            + "(not analyzed yet).";

    private final GraphQueries queries;

    public FindCallersTool(GraphQueries queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @McpTool(
            name = "find_callers",
            description = """
                    Lists the methods that may call a method, resolved from bytecode: every direct call, plus every \
                    call to a method it overrides that may dispatch to it at run time. For the latter, "through" is \
                    the overridden method the call names: at run time it may run this method or another \
                    implementation. super calls and static calls never dispatch. A caller flagged isLambdaBody is \
                    the body of a lambda, and its name tells the method that contains it (lambda$<method>$<n>). \
                    Calls through method references (this::method) are not analyzed yet and are missing. Every class \
                    matching the name and every overload is returned; when nothing is found, the note explains why.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Find callers",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public FindCallersResult findCallers(
            @McpToolParam(description = "Class or interface that declares the method: simple name (PaymentService) "
                    + "or qualified name (com.acme.PaymentService); nested classes as Outer.Inner")
            String className,
            @McpToolParam(description = "Method name only, without parameters, e.g. pay")
            String methodName) {
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("A method name is required, e.g. pay");
        }
        CallerSearch search = queries.findCallers(SourceNames.dottedClassName(className), methodName.strip());

        List<MethodCallers> methods = search.methods().stream()
                .map(FindCallersTool::toMethodCallers)
                .toList();
        return new FindCallersResult(methods,
                methods.isEmpty() ? Notes.nothingFound(className, methodName, search.classes()) : null);
    }

    private static MethodCallers toMethodCallers(Callers found) {
        List<CallerSummary> callers = found.calls().stream()
                .map(call -> CallerSummary.of(call, found.method()))
                .toList();
        return new MethodCallers(
                MethodSummary.of(found.method()),
                callers,
                callers.isEmpty() ? NO_CALLERS_NOTE : null);
    }
}
