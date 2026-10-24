package com.jgraph.mcp.callers;

import java.util.List;

/**
 * Result of looking up the callers of a method by class name and method name. Facts only: the tool turns them into
 * words.
 *
 * @param classes internal names of the analyzed classes matching the class name, whether or not they declare the
 *                method; empty if no class matches
 * @param methods every declared method with that name in those classes (all overloads), with its callers
 */
record CallerSearch(
        List<String> classes,
        List<Callers> methods
) {
}
