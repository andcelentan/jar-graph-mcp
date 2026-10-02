package com.jgraph.analyzer.model;

public record MethodCall(
        String callerSignature,
        String targetSignature //Some issue about JDK method signatures
) {}