package com.jgraph.mcp.tool;

/**
 * Notes shared by the tools, so that the same situation is explained with the same words whatever the tool
 * (ADR-0009: never an empty answer without a reason).
 */
final class Notes {

    private Notes() {
    }

    static String classNotFound(String className) {
        return ("No class of the analysis is named '%s': it may belong to the JDK, a library or another jar, or the "
                + "name may be misspelled.").formatted(className);
    }
}
