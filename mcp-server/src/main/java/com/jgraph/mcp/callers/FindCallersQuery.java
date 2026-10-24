package com.jgraph.mcp.callers;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jgraph.mcp.shared.GraphSupport.MATCH_CLASSES_BY_NAME;
import static com.jgraph.mcp.shared.GraphSupport.toMethodInfo;

/** The read query behind {@code find_callers}. */
@Component
class FindCallersQuery {

    /**
     * For every class matching the name, its methods with the given name (all overloads, bridges excluded) and the
     * calls that may run each one: the ADR-0008 resolution query read backwards. From the method, {@code OVERRIDES}
     * goes up zero or more steps to the declaration a call points at ({@code calledMethod}): zero steps is a direct
     * call, any instruction; more steps only count for {@code VIRTUAL} and {@code INTERFACE} calls, the only ones that
     * dispatch on the runtime type. Bridges are never callers: a bridge only forwards to the method it bridges, and
     * the call that matters is the one to the overridden declaration. A matching class that declares no such method
     * gives a row with a null method, so the tool can tell "no such class" from "no such method".
     */
    private static final String QUERY = MATCH_CLASSES_BY_NAME + """
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

    FindCallersQuery(Driver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    /**
     * @param className  dotted class name, simple or qualified, e.g. {@code PaymentService} or {@code Outer.Inner}
     * @param methodName e.g. {@code pay}
     */
    CallerSearch find(String className, String methodName) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> toCallerSearch(
                    tx.run(QUERY, Map.of("className", className, "methodName", methodName)).list()));
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
                        row.get("calls").asList(FindCallersQuery::toCallInfo).stream()
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
}
