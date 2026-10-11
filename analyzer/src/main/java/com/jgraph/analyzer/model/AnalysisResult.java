package com.jgraph.analyzer.model;

import java.util.List;

public record AnalysisResult(
        List<JavaClass> classes,
        List<JavaMethod> methods,
        List<MethodCall> calls,
        List<MethodOverride> overrides
) {
    /**
     * Raw parsing output: overrides are derived later from the complete model (see ADR-0006).
     */
    public AnalysisResult(List<JavaClass> classes, List<JavaMethod> methods, List<MethodCall> calls) {
        this(classes, methods, calls, List.of());
    }

    public AnalysisResult withOverrides(List<MethodOverride> overrides) {
        return new AnalysisResult(classes, methods, calls, overrides);
    }
}
