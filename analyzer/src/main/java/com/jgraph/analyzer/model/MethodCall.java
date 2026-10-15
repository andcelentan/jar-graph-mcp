package com.jgraph.analyzer.model;

public record MethodCall(
        String callerSignature,
        String targetSignature, // Declared method once resolved (ADR-0008); as written in bytecode otherwise
        Invocation invocation,
        String receiverFqcn     // Class named in the call instruction (qualifying type): never rewritten
) {
    /**
     * @return this call pointing at {@code declaredSignature}, with invocation and receiver unchanged
     */
    public MethodCall withTarget(String declaredSignature) {
        return new MethodCall(callerSignature, declaredSignature, invocation, receiverFqcn);
    }
}
