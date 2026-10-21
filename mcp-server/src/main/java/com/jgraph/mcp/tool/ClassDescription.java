package com.jgraph.mcp.tool;

import com.jgraph.mcp.graph.ClassInfo;

import java.util.List;

/**
 * A class as {@code describe_class} shows it to an assistant: where it sits in the hierarchy and what it declares.
 *
 * @param className  binary name, e.g. {@code com.acme.Outer$Inner}
 * @param kind       {@code CLASS}, {@code INTERFACE}, {@code ENUM}, {@code RECORD} or {@code ANNOTATION}
 * @param supertypes direct supertypes; those outside the analysis cannot be described further
 * @param subtypes   direct subtypes, all in the analysis
 * @param methods    declared methods, constructors ({@code <init>}) included, bridges excluded, lambda bodies flagged
 */
public record ClassDescription(
        String className,
        String kind,
        boolean isAbstract,
        List<TypeSummary> supertypes,
        List<TypeSummary> subtypes,
        List<MethodSummary> methods
) {

    static ClassDescription of(ClassInfo info) {
        return new ClassDescription(
                SourceNames.binaryName(info.fqcn()),
                info.kind(),
                info.isAbstract(),
                TypeSummary.of(info.supertypes()),
                TypeSummary.of(info.subtypes()),
                MethodSummary.of(info.methods()));
    }
}
