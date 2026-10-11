package com.jgraph.analyzer.model;

/**
 * Kind of a type, as declared in bytecode. Mirrors {@code Class.isInterface()}, {@code isEnum()}, {@code isRecord()}
 * and {@code isAnnotation()} in reflection.
 */
public enum ClassKind {
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION;

    /**
     * Whether the JVM treats this kind as an interface: annotation types are interfaces too.
     */
    public boolean isInterface() {
        return this == INTERFACE || this == ANNOTATION;
    }
}
