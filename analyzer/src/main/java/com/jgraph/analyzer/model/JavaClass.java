package com.jgraph.analyzer.model;

import java.util.List;

public record JavaClass(
        String fqcn,            // JVM internal name: e.g. "com/example/OrderServiceImpl"
        String simpleName,      // Simple name: e.g. "OrderServiceImpl"
        ClassKind kind,
        boolean isAbstract,     // Also true for interfaces
        String superClass,      // FQCN of the explicit superclass; null for interfaces and implicit supertypes
        List<String> interfaces // FQCN of the implemented interfaces (of the superinterfaces, for an interface)
) {
    public boolean isInterface() {
        return kind.isInterface();
    }
}
