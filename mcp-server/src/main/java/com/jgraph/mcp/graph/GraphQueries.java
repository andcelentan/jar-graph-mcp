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
import java.util.Optional;

/**
 * Read-only queries over the code graph written by the analyzer (ADR-0009). Every query runs in a read transaction.
 * Inputs and outputs use graph identifiers (JVM internal names, signatures): converting the names an assistant uses
 * is the job of the tools.
 */
@Component
public class GraphQueries {

    /**
     * The code that may run when the method is called: following {@code OVERRIDES} down from it, zero or more steps,
     * keeping concrete methods only (ADR-0006, ADR-0008). Bridges never own {@code OVERRIDES}, so they can only appear
     * as the method itself, and are filtered out (ADR-0007).
     * <p>
     * Spring Data types are stubs without supertypes of their own, so the path stops at the first one
     * ({@code JpaRepository}, not {@code Repository}): repository types are recognized by package instead, and every
     * one of them, store-specific or not, lives in a {@code repository} package.
     */
    private static final String FIND_IMPLEMENTATIONS = """
            MATCH (owner:Class)-[:DECLARES]->(method:Method {signature: $signature})
            OPTIONAL MATCH (method)<-[:OVERRIDES*0..]-(impl:Method)
            WHERE NOT impl.isAbstract AND NOT impl.isBridge
            WITH owner, method, collect(DISTINCT impl) AS implementations
            RETURN method,
                   implementations,
                   EXISTS {
                       (owner)-[:EXTENDS|IMPLEMENTS*1..]->(supertype:Class)
                       WHERE supertype.fqcn =~ 'org/springframework/data/(.+/)?repository/.+'
                   } AS springDataRepository
            """;

    private final Driver driver;

    public GraphQueries(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    /**
     * @param signature {@code owner#name(descriptor)}, e.g. {@code com/acme/OrderService#save(Lcom/acme/Order;)V}
     * @return empty if no class of the analysis declares this method
     */
    public Optional<Implementations> findImplementations(String signature) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(FIND_IMPLEMENTATIONS, Map.of("signature", signature))
                    .list()
                    .stream()
                    .findFirst()
                    .map(GraphQueries::toImplementations));
        }
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
