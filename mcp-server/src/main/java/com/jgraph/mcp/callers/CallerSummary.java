package com.jgraph.mcp.callers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jgraph.mcp.shared.MethodInfo;
import com.jgraph.mcp.shared.MethodSummary;

/**
 * A call that may run a method, as {@code find_callers} shows it to an assistant. A {@code null} {@code through} is
 * left out of the JSON.
 *
 * @param caller     the calling method; {@code caller.isLambdaBody} marks the body of a lambda, whose name tells the
 *                   method that contains it ({@code lambda$<method>$<n>})
 * @param invocation {@code VIRTUAL}, {@code INTERFACE}, {@code SPECIAL} ({@code super} calls, constructors) or
 *                   {@code STATIC}
 * @param through    for a call that reaches the method by dispatch, the overridden method the call names: at run time it
 *                   may run this method or another implementation; {@code null} for a direct call
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CallerSummary(
        MethodSummary caller,
        String invocation,
        MethodSummary through
) {

    static CallerSummary of(CallInfo call, MethodInfo method) {
        boolean direct = call.calledMethod().signature().equals(method.signature());
        return new CallerSummary(
                MethodSummary.of(call.caller()),
                call.invocation(),
                direct ? null : MethodSummary.of(call.calledMethod()));
    }
}
