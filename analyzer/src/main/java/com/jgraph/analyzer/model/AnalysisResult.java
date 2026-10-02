package com.jgraph.analyzer.model;

import java.util.List;

public record AnalysisResult(
        List<JavaClass> classes,
        List<JavaMethod> methods,
        List<MethodCall> calls
) {}
