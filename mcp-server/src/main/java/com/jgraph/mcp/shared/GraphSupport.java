package com.jgraph.mcp.shared;

import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;

/**
 * Pieces shared by the queries of every tool (ADR-0009). Queries run in read transactions and return facts in graph
 * identifiers (JVM internal names, signatures): the words for the assistant are the job of the tools.
 */
public final class GraphSupport {

    /**
     * Start of every query: the classes of the analysis matching {@code $className}, bound to {@code owner}.
     * {@code $className} is dotted, nested classes included ({@code Outer.Inner}, see
     * {@link SourceNames#dottedClassName}), and matches every class whose name is it or ends with it on a segment
     * boundary: {@code JarReader}, {@code parser.JarReader} and {@code com.acme.parser.JarReader} all find
     * {@code com/acme/parser/JarReader}, while {@code Reader} does not. Only analyzed classes have a {@code kind}
     * (stubs only have {@code fqcn}).
     */
    public static final String MATCH_CLASSES_BY_NAME = """
            MATCH (owner:Class)
            WHERE owner.kind IS NOT NULL
            WITH owner, replace(replace(owner.fqcn, '/', '.'), '$', '.') AS dottedName
            WHERE dottedName = $className OR dottedName ENDS WITH '.' + $className
            """;

    private GraphSupport() {
    }

    public static MethodInfo toMethodInfo(Node node) {
        String signature = node.get("signature").asString();
        return new MethodInfo(
                signature,
                signature.substring(0, signature.indexOf('#')),
                node.get("name").asString(),
                node.get("returnType").asString(),
                node.get("parameterTypes").asList(Value::asString),
                node.get("visibility").asString(),
                node.get("isStatic").asBoolean(),
                node.get("isAbstract").asBoolean());
    }
}
