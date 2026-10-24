package com.jgraph.mcp.graph;

/**
 * One call that may run a method.
 *
 * @param caller       the calling method
 * @param invocation   the instruction: {@code VIRTUAL}, {@code INTERFACE}, {@code SPECIAL} or {@code STATIC}
 * @param calledMethod the declaration the call points at (ADR-0008): the method itself for a direct call, or a method
 *                     it overrides when the call reaches it by dispatch
 */
public record CallInfo(
        MethodInfo caller,
        String invocation,
        MethodInfo calledMethod
) {
}
