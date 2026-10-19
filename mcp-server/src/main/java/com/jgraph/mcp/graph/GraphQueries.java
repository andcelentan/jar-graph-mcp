package com.jgraph.mcp.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only queries over the code graph written by the analyzer (ADR-0009). Every query runs in a read transaction
 * and returns facts in graph identifiers (JVM internal names, signatures): the words for the assistant are the job of
 * the tools.
 * <p>
 * Classes are looked up by name as an assistant knows it: {@code className} is dotted, nested classes included
 * ({@code Outer.Inner}), and matches every class of the analysis whose name is it or ends with it on a segment
 * boundary: {@code JarReader}, {@code parser.JarReader} and {@code com.acme.parser.JarReader} all find
 * {@code com/acme/parser/JarReader}, while {@code Reader} does not.
 */
@Component
public class GraphQueries {

    /**
     * For every class matching the name, its methods with the given name (all overloads, bridges excluded) and the
     * code that may run when each is called: following {@code OVERRIDES} down from it, zero or more steps, keeping
     * concrete methods only (ADR-0006, ADR-0008). A matching class that declares no such method still gives a row,
     * with a null method, so the tool can tell "no such class" from "no such method".
     * <p>
     * Only analyzed classes have a {@code kind} (stubs only have {@code fqcn}). Spring Data types are stubs without
     * supertypes of their own, so the path stops at the first one ({@code JpaRepository}, not {@code Repository}):
     * repository types are recognized by package, and all of them, store-specific or not, live in a {@code repository}
     * package.
     */
    private static final String FIND_IMPLEMENTATIONS = """
            MATCH (owner:Class)
            WHERE owner.kind IS NOT NULL
            WITH owner, replace(replace(owner.fqcn, '/', '.'), '$', '.') AS dottedName
            WHERE dottedName = $className OR dottedName ENDS WITH '.' + $className
            OPTIONAL MATCH (owner)-[:DECLARES]->(method:Method {name: $methodName})
            WHERE NOT method.isBridge
            OPTIONAL MATCH (method)<-[:OVERRIDES*0..]-(impl:Method)
            WHERE NOT impl.isAbstract AND NOT impl.isBridge
            WITH owner, method, collect(DISTINCT impl) AS implementations
            RETURN owner.fqcn AS fqcn,
                   method,
                   implementations,
                   EXISTS {
                       (owner)-[:EXTENDS|IMPLEMENTS*1..]->(supertype:Class)
                       WHERE supertype.fqcn =~ 'org/springframework/data/(.+/)?repository/.+'
                   } AS springDataRepository
            ORDER BY fqcn, method.signature
            """;

    private final Driver driver;

    public GraphQueries(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    /**
     * @param className  dotted class name, simple or qualified, e.g. {@code PaymentService} or {@code Outer.Inner}
     * @param methodName e.g. {@code pay}
     */
    public ImplementationSearch findImplementations(String className, String methodName) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> toSearch(
                    tx.run(FIND_IMPLEMENTATIONS, Map.of("className", className, "methodName", methodName)).list()));
        }
    }

    private static ImplementationSearch toSearch(List<Record> rows) {
        List<String> classes = rows.stream()
                .map(row -> row.get("fqcn").asString())
                .distinct()
                .toList();
        List<Implementations> methods = rows.stream()
                .filter(row -> !row.get("method").isNull())
                .map(GraphQueries::toImplementations)
                .toList();
        return new ImplementationSearch(classes, methods);
    }

    private static Implementations toImplementations(Record row) {
        List<MethodInfo> implementations = row.get("implementations").asList(value -> toMethodInfo(value.asNode()))
                .stream()
                .sorted(Comparator.comparing(MethodInfo::signature))
                .toList();
        return new Implementations(
                toMethodInfo(row.get("method").asNode()),
                implementations,
                row.get("springDataRepository").asBoolean());
    }

    private static MethodInfo toMethodInfo(Node node) {
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
