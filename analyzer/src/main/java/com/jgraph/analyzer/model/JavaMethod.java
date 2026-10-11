package com.jgraph.analyzer.model;

import java.util.List;

public record JavaMethod(
        String ownerClassFqcn,          // e.g. "com/example/OrderServiceImpl"
        String name,                    // e.g. "process"
        String descriptor,              // e.g. "(Ljava/lang/String;)V" (essential for uniqueness in ASM)
        String returnType,              // e.g. "void" or "com.example.Order"
        List<String> parameterTypes,    // e.g. ["java.lang.String", "int"]
        Visibility visibility,
        boolean isStatic,
        boolean isAbstract,
        boolean isTransactional
) {
    /**
     * Unique signature
     */
    public String signature() {
        return ownerClassFqcn + "#" + name + descriptor;
    }
}
