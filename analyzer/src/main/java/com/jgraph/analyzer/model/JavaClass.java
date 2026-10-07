package com.jgraph.analyzer.model;

import java.util.List;

public record JavaClass(
        String fqcn,            // JVM internal name: e.g. "com/example/OrderServiceImpl"
        String simpleName,      // Simple name: e.g. "OrderServiceImpl"
        boolean isInterface,
        List<String> interfaces // FQCN of the interfaces implemented by this class
) {}