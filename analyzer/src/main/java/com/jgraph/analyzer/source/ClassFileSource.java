package com.jgraph.analyzer.source;

import java.io.IOException;

/**
 * Where the class files of one analysis come from (ADR-0012). A source only finds {@code .class} files and hands them
 * over: it knows nothing of bytecode, and does not decide which classes belong to the analysis.
 */
public interface ClassFileSource {

    /**
     * Hands every class file to the consumer, always in the same order for the same input.
     *
     * @throws IOException if the input cannot be read, or the consumer fails
     */
    void forEach(Consumer consumer) throws IOException;

    /** Receives the class files of a source, one at a time. */
    @FunctionalInterface
    interface Consumer {

        void accept(ClassFile classFile) throws IOException;
    }
}
