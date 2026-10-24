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
     * Start of every query: the classes of the analysis matching {@code $className}, bound to {@code owner}. Only
     * analyzed classes have a {@code kind} (stubs only have {@code fqcn}).
     */
    private static final String MATCH_CLASSES_BY_NAME = """
            MATCH (owner:Class)
            WHERE owner.kind IS NOT NULL
            WITH owner, replace(replace(owner.fqcn, '/', '.'), '$', '.') AS dottedName
            WHERE dottedName = $className OR dottedName ENDS WITH '.' + $className
            """;

    /**
     * For every class matching the name, its methods with the given name (all overloads, bridges excluded) and the
     * code that may run when each is called: following {@code OVERRIDES} down from it, zero or more steps, keeping
     * concrete methods only (ADR-0006, ADR-0008). A matching class that declares no such method still gives a row,
     * with a null method, so the tool can tell "no such class" from "no such method".
     * <p>
     * Spring Data types are stubs without supertypes of their own, so the path stops at the first one
     * ({@code JpaRepository}, not {@code Repository}): repository types are recognized by package, and all of them,
     * store-specific or not, live in a {@code repository} package.
     */
    private static final String FIND_IMPLEMENTATIONS = MATCH_CLASSES_BY_NAME + """
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

    /**
     * One row per class matching the name, with its declared methods (bridges excluded) and its direct supertypes and
     * subtypes. Each list is a {@code COLLECT} subquery, evaluated on its own for every class: matching the three in
     * sequence would multiply the rows (supertypes × subtypes × methods). Direct relationships only, one step each:
     * the assistant walks further up or down by describing the next class.
     */
    private static final String DESCRIBE_CLASSES = MATCH_CLASSES_BY_NAME + """
            RETURN owner.fqcn AS fqcn,
                   owner.kind AS kind,
                   owner.isAbstract AS isAbstract,
                   COLLECT {
                       MATCH (owner)-[:DECLARES]->(method:Method)
                       WHERE NOT method.isBridge
                       RETURN method
                   } AS methods,
                   COLLECT {
                       MATCH (owner)-[r:EXTENDS|IMPLEMENTS]->(supertype:Class)
                       RETURN {fqcn: supertype.fqcn, relation: type(r), analyzed: supertype.kind IS NOT NULL}
                   } AS supertypes,
                   COLLECT {
                       // Only analyzed classes have outgoing EXTENDS/IMPLEMENTS: subtypes are always analyzed
                       MATCH (subtype:Class)-[r:EXTENDS|IMPLEMENTS]->(owner)
                       RETURN {fqcn: subtype.fqcn, relation: type(r), analyzed: true}
                   } AS subtypes
            ORDER BY fqcn
            """;

    /**
     * For every class matching the name, its methods with the given name (all overloads, bridges excluded) and the
     * calls that may run each one: the ADR-0008 resolution query read backwards. From the method, {@code OVERRIDES}
     * goes up zero or more steps to the declaration a call points at ({@code calledMethod}): zero steps is a direct
     * call, any instruction; more steps only count for {@code VIRTUAL} and {@code INTERFACE} calls, the only ones that
     * dispatch on the runtime type. Bridges are never callers: a bridge only forwards to the method it bridges, and
     * the call that matters is the one to the overridden declaration. As in {@link #FIND_IMPLEMENTATIONS}, a matching
     * class that declares no such method gives a row with a null method.
     */
    private static final String FIND_CALLERS = MATCH_CLASSES_BY_NAME + """
            OPTIONAL MATCH (owner)-[:DECLARES]->(method:Method {name: $methodName})
            WHERE NOT method.isBridge
            RETURN owner.fqcn AS fqcn,
                   method,
                   COLLECT {
                       MATCH (method)-[:OVERRIDES*0..]->(calledMethod:Method)<-[call:CALLS]-(caller:Method)
                       WHERE NOT caller.isBridge
                         AND (calledMethod = method OR call.invocation IN ['VIRTUAL', 'INTERFACE'])
                       RETURN DISTINCT {caller: caller, invocation: call.invocation, calledMethod: calledMethod}
                   } AS calls
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

    /**
     * @param className dotted class name, simple or qualified, e.g. {@code PaymentService} or {@code Outer.Inner}
     * @return one entry per class of the analysis matching the name; empty if none matches
     */
    public List<ClassInfo> describeClasses(String className) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(DESCRIBE_CLASSES, Map.of("className", className))
                    .list(GraphQueries::toClassInfo));
        }
    }

    /**
     * @param className  dotted class name, simple or qualified, e.g. {@code PaymentService} or {@code Outer.Inner}
     * @param methodName e.g. {@code pay}
     */
    public CallerSearch findCallers(String className, String methodName) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> toCallerSearch(
                    tx.run(FIND_CALLERS, Map.of("className", className, "methodName", methodName)).list()));
        }
    }

    private static CallerSearch toCallerSearch(List<Record> rows) {
        List<String> classes = rows.stream()
                .map(row -> row.get("fqcn").asString())
                .distinct()
                .toList();
        List<Callers> methods = rows.stream()
                .filter(row -> !row.get("method").isNull())
                .map(row -> new Callers(
                        toMethodInfo(row.get("method").asNode()),
                        row.get("calls").asList(GraphQueries::toCallInfo).stream()
                                .sorted(Comparator.comparing((CallInfo call) -> call.caller().signature())
                                        .thenComparing(call -> call.calledMethod().signature()))
                                .toList()))
                .toList();
        return new CallerSearch(classes, methods);
    }

    private static CallInfo toCallInfo(Value value) {
        return new CallInfo(
                toMethodInfo(value.get("caller").asNode()),
                value.get("invocation").asString(),
                toMethodInfo(value.get("calledMethod").asNode()));
    }

    private static ClassInfo toClassInfo(Record row) {
        return new ClassInfo(
                row.get("fqcn").asString(),
                row.get("kind").asString(),
                row.get("isAbstract").asBoolean(),
                row.get("supertypes").asList(GraphQueries::toTypeLink),
                row.get("subtypes").asList(GraphQueries::toTypeLink),
                row.get("methods").asList(value -> toMethodInfo(value.asNode())));
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

    private static TypeLink toTypeLink(Value value) {
        return new TypeLink(
                value.get("fqcn").asString(),
                value.get("relation").asString(),
                value.get("analyzed").asBoolean());
    }
}
