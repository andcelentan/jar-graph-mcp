package com.jgraph.analyzer.model;

/**
 * A method overriding the nearest matching declaration in one of its owner's supertypes (see ADR-0006).
 */
public record MethodOverride(
        String overridingSignature,
        String overriddenSignature
) {}
