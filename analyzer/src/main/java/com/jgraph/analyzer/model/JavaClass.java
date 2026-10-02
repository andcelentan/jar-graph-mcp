package com.jgraph.analyzer.model;

import java.util.List;

public record JavaClass(
        String fqcn,            // Nome interno JVM: es. "com/example/OrderServiceImpl"
        String simpleName,      // Nome semplice: es. "OrderServiceImpl"
        boolean isInterface,
        List<String> interfaces // Lista dei FQCN delle interfacce implementate
) {}