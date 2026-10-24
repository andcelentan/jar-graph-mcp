package com.jgraph.mcp.cypher;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.summary.Plan;
import org.neo4j.driver.summary.QueryType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs Cypher written by an assistant (ADR-0009), behind four barriers:
 * <ol>
 *   <li>{@code EXPLAIN} first, which plans the query without running it: Neo4j must classify it as
 *       {@link QueryType#READ_ONLY};</li>
 *   <li>no forbidden operator in that plan: Neo4j classifies {@code LOAD CSV} (the database reads files or URLs),
 *       procedure calls and administration commands such as {@code TERMINATE TRANSACTIONS} as read-only too;</li>
 *   <li>a read transaction, so the database itself refuses writes even if a check above missed one;</li>
 *   <li>a timeout, and a limit on the rows read.</li>
 * </ol>
 * A read-only database user would add a last barrier; Neo4j Community has no roles, so it waits for AWS.
 */
@Component
class ReadOnlyCypherRunner {

    /** Plan operators that read outside the graph, run code, or administer the database. */
    private static final Set<String> FORBIDDEN_OPERATORS = Set.of("LoadCSV", "ProcedureCall");
    /** {@code SHOW ...} and {@code TERMINATE ...} commands: settings, transactions, users of the database. */
    private static final List<String> FORBIDDEN_OPERATOR_PREFIXES = List.of("Show", "Terminate");

    private final Driver driver;
    private final QueryGraphProperties properties;

    ReadOnlyCypherRunner(Driver driver, QueryGraphProperties properties) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * @throws QueryRejectedException if the query is not read-only, uses a forbidden operation, is not valid Cypher,
     *                                or runs longer than the timeout
     */
    QueryRows run(String cypher) {
        TransactionConfig config = TransactionConfig.builder().withTimeout(properties.timeout()).build();
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                requireReadOnly(tx, cypher);
                return readRows(tx.run(cypher));
            }, config);
        } catch (QueryRejectedException e) {
            throw e;
        } catch (ClientException e) {
            if (isTimeout(e)) {
                throw new QueryRejectedException(("The query was stopped after %s. Make it cheaper: name the "
                        + "relationship types, bound variable-length patterns, filter early, aggregate or add LIMIT.")
                        .formatted(properties.timeout()), e);
            }
            throw new QueryRejectedException("Neo4j rejected the query: " + e.getMessage(), e);
        }
    }

    private static void requireReadOnly(TransactionContext tx, String cypher) {
        var summary = tx.run("EXPLAIN " + cypher).consume();
        if (summary.queryType() != QueryType.READ_ONLY) {
            throw new QueryRejectedException(("Only read-only queries are allowed, but this one is %s. Use MATCH ... "
                    + "RETURN: no CREATE, MERGE, SET, REMOVE, DELETE or schema changes.")
                    .formatted(summary.queryType()));
        }
        List<String> forbidden = new ArrayList<>();
        collectForbidden(summary.plan(), forbidden);
        if (!forbidden.isEmpty()) {
            throw new QueryRejectedException(("The query uses forbidden operations: %s. LOAD CSV, procedure calls "
                    + "(CALL db.xxx()) and SHOW/TERMINATE commands are not allowed; CALL { ... } subqueries are. "
                    + "The graph model is in the resource graph://model.").formatted(String.join(", ", forbidden)));
        }
    }

    private static void collectForbidden(Plan plan, List<String> forbidden) {
        // Operator types come as "Name@database", e.g. "ProcedureCall@neo4j"
        String operator = plan.operatorType().split("@", 2)[0];
        if (FORBIDDEN_OPERATORS.contains(operator)
                || FORBIDDEN_OPERATOR_PREFIXES.stream().anyMatch(operator::startsWith)) {
            forbidden.add(operator);
        }
        plan.children().forEach(child -> collectForbidden(child, forbidden));
    }

    /** Reads one row past the limit only to know whether the result was truncated. */
    private QueryRows readRows(Result result) {
        List<String> columns = result.keys();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rows.size() < properties.maxRows() && result.hasNext()) {
            rows.add(CypherValues.toRow(result.next()));
        }
        boolean truncated = result.hasNext();
        // Discards the remaining rows on the server instead of streaming them
        result.consume();
        return new QueryRows(columns, rows, truncated);
    }

    private static boolean isTimeout(ClientException e) {
        String code = e.code() == null ? "" : e.code();
        return code.contains("TransactionTimedOut");
    }
}
