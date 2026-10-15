package com.jgraph.analyzer.model;

/**
 * Bytecode instruction used for a call. Only {@link #VIRTUAL} and {@link #INTERFACE} dispatch on the runtime type of
 * the receiver; {@link #SPECIAL} ({@code super.m()}, constructors) and {@link #STATIC} always execute the named method
 * (ADR-0008).
 */
public enum Invocation {
    VIRTUAL,
    INTERFACE,
    SPECIAL,
    STATIC;

    public boolean dispatches() {
        return this == VIRTUAL || this == INTERFACE;
    }
}
