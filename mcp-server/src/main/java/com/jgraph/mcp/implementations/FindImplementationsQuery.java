package com.jgraph.mcp.implementations;

import com.jgraph.mcp.shared.MethodInfo;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jgraph.mcp.shared.GraphSupport.MATCH_CLASSES_BY_NAME;
import static com.jgraph.mcp.shared.GraphSupport.toMethodInfo;

/** The read query behind {@code find_implementations}. */
@Component
class FindImplementationsQuery {

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
    private static final String QUERY = MATCH_CLASSES_BY_NAME + """
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

    FindImplementationsQuery(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    /**
     * @param className  dotted class name, simple or qualified, e.g. {@code PaymentService} or {@code Outer.Inner}
     * @param methodName e.g. {@code pay}
     */
    ImplementationSearch find(String className, String methodName) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> toSearch(
                    tx.run(QUERY, Map.of("className", className, "methodName", methodName)).list()));
        }
    }

    private static ImplementationSearch toSearch(List<Record> rows) {
        List<String> classes = rows.stream()
                .map(row -> row.get("fqcn").asString())
                .distinct()
                .toList();
        List<Implementations> methods = rows.stream()
                .filter(row -> !row.get("method").isNull())
                .map(FindImplementationsQuery::toImplementations)
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
}
