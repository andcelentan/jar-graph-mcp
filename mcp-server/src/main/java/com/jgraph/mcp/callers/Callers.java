package com.jgraph.mcp.callers;

import com.jgraph.mcp.shared.MethodInfo;

import java.util.List;

/**
 * A method and the calls that may run it: direct calls, and calls that may reach it by dispatch (ADR-0008).
 *
 * @param method the method asked about
 * @param calls  the calls that may run it, bridges excluded as callers; empty if none is in the analysis
 */
record Callers(
        MethodInfo method,
        List<CallInfo> calls
) {
}
