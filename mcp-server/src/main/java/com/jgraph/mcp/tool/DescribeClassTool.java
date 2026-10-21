package com.jgraph.mcp.tool;

import com.jgraph.mcp.graph.GraphQueries;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * MCP tool {@code describe_class}: where a class sits in the type hierarchy and what it declares, read from bytecode
 * (ADR-0009). Registered by the Spring AI annotation scanner because it is a bean with {@link McpTool} methods.
 * Specification: {@code DescribeClassToolIT}.
 */
@Component
public class DescribeClassTool {

    private final GraphQueries queries;

    public DescribeClassTool(GraphQueries queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @McpTool(
            name = "describe_class",
            description = """
                    Describes a class of the analyzed application, read from bytecode: its kind (class, interface, \
                    enum, record, annotation), its direct supertypes and direct subtypes, and the methods it \
                    declares, constructors (<init>) included. Supertypes outside the analysis (JDK, libraries) are \
                    flagged with inAnalysis false; bodies of lambdas are flagged with isLambdaBody. Use it to see \
                    what a class contains and to walk its hierarchy one step at a time, for example to find the \
                    supertype that declares an inherited method. Every class matching the name is returned; when \
                    none matches, the note explains why.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Describe class",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public DescribeClassResult describeClass(
            @McpToolParam(description = "Class, interface, enum or record to describe: simple name (OrderService) "
                    + "or qualified name (com.acme.OrderService); nested classes as Outer.Inner")
            String className) {
        List<ClassDescription> classes = queries.describeClasses(SourceNames.dottedClassName(className)).stream()
                .map(ClassDescription::of)
                .toList();
        return new DescribeClassResult(classes, classes.isEmpty() ? Notes.classNotFound(className) : null);
    }
}
