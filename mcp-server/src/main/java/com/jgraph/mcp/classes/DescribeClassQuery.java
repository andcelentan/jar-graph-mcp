package com.jgraph.mcp.classes;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jgraph.mcp.shared.GraphSupport.MATCH_CLASSES_BY_NAME;
import static com.jgraph.mcp.shared.GraphSupport.toMethodInfo;

/** The read query behind {@code describe_class}. */
@Component
class DescribeClassQuery {

    /**
     * One row per class matching the name, with its declared methods (bridges excluded) and its direct supertypes and
     * subtypes. Each list is a {@code COLLECT} subquery, evaluated on its own for every class: matching the three in
     * sequence would multiply the rows (supertypes × subtypes × methods). Direct relationships only, one step each:
     * the assistant walks further up or down by describing the next class.
     */
    private static final String QUERY = MATCH_CLASSES_BY_NAME + """
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

    private final Driver driver;

    DescribeClassQuery(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    /**
     * @param className dotted class name, simple or qualified, e.g. {@code PaymentService} or {@code Outer.Inner}
     * @return one entry per class of the analysis matching the name; empty if none matches
     */
    List<ClassInfo> find(String className) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run(QUERY, Map.of("className", className))
                    .list(DescribeClassQuery::toClassInfo));
        }
    }

    private static ClassInfo toClassInfo(Record row) {
        return new ClassInfo(
                row.get("fqcn").asString(),
                row.get("kind").asString(),
                row.get("isAbstract").asBoolean(),
                row.get("supertypes").asList(DescribeClassQuery::toTypeLink),
                row.get("subtypes").asList(DescribeClassQuery::toTypeLink),
                row.get("methods").asList(value -> toMethodInfo(value.asNode())));
    }

    private static TypeLink toTypeLink(Value value) {
        return new TypeLink(
                value.get("fqcn").asString(),
                value.get("relation").asString(),
                value.get("analyzed").asBoolean());
    }
}
