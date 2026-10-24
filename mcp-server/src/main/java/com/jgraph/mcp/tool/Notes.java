package com.jgraph.mcp.tool;

import java.util.List;

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

    /**
     * @param classes internal names of the classes that match the name but do not declare the method
     */
    static String methodNotDeclared(List<String> classes, String methodName) {
        List<String> names = classes.stream().map(SourceNames::binaryName).toList();
        return ("%s: no method named '%s' is declared there. It may be inherited from a supertype, or from the JDK "
                + "or a library.").formatted(String.join(", ", names), methodName);
    }

    /** No class matches, or the matching classes do not declare the method. */
    static String nothingFound(String className, String methodName, List<String> classes) {
        return classes.isEmpty() ? classNotFound(className) : methodNotDeclared(classes, methodName);
    }
}
