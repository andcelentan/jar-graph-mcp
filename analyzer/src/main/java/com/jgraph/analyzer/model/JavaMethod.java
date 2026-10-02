package com.jgraph.analyzer.model;

import java.util.List;

public record JavaMethod(
        String ownerClassFqcn,          // es. "com/example/OrderServiceImpl"
        String name,                    // es. "process"
        String descriptor,              // es. "(Ljava/lang/String;)V" (essenziale per l'univocità su ASM)
        String returnType,              // es. "void" oppure "com.example.Order"
        List<String> parameterTypes,    // es. ["java.lang.String", "int"]
        boolean isTransactional
) {
    /**
     * Unique signature
     */
    public String signature() {
        return ownerClassFqcn + "#" + name + descriptor;
    }
}
