package com.jgraph.mcp.shared;

/**
 * Converts between the names an assistant writes, taken from source code ({@code com.acme.Outer.Inner},
 * {@code Store<Order>}), and the names of the graph ({@code com/acme/Outer$Inner}).
 */
public final class SourceNames {

    private SourceNames() {
    }

    /**
     * The dotted form the queries match class names in: {@code com.acme.Outer$Inner}, {@code com/acme/Outer$Inner}
     * and {@code com.acme.Outer.Inner} all become {@code com.acme.Outer.Inner}; type arguments are dropped.
     *
     * @throws IllegalArgumentException if the name is blank
     */
    public static String dottedClassName(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("A class name is required, e.g. OrderService or com.acme.OrderService");
        }
        return eraseTypeArguments(className).strip().replace('/', '.').replace('$', '.');
    }

    /** {@code com/acme/Outer$Inner} → {@code com.acme.Outer$Inner}, the name returned by {@link Class#getName()}. */
    public static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    /** {@code Store<Map<String, Order>>} → {@code Store}: classes are named without type arguments. */
    private static String eraseTypeArguments(String type) {
        StringBuilder erased = new StringBuilder(type.length());
        int depth = 0;
        for (char c : type.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                erased.append(c);
            }
        }
        return erased.toString();
    }
}
