package com.jgraph.analyzer.repository;

import com.jgraph.analyzer.config.DatabaseConfig;
import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public class Neo4jGraphRepository implements GraphRepository {

    private static final int DEFAULT_CHUNK_SIZE = 10_000;

    private final Driver driver;
    private final int chunkSize;

    public Neo4jGraphRepository(DatabaseConfig config) {
        this(GraphDatabase.driver(
                config.uri(),
                AuthTokens.basic(config.username(), config.password())
        ), DEFAULT_CHUNK_SIZE);
    }

    public Neo4jGraphRepository(Driver driver) {
        this(driver, DEFAULT_CHUNK_SIZE);
    }

    Neo4jGraphRepository(Driver driver, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive, got: " + chunkSize);
        }
        this.driver = Objects.requireNonNull(driver, "driver");
        this.chunkSize = chunkSize;
    }

    @Override
    public void initSchema() {
        try (Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                tx.run("""
                            CREATE CONSTRAINT unique_class_fqcn IF NOT EXISTS
                            FOR (c:Class) REQUIRE c.fqcn IS UNIQUE;
                        """);

                tx.run("""
                            CREATE CONSTRAINT unique_method_signature IF NOT EXISTS
                            FOR (m:Method) REQUIRE m.signature IS UNIQUE;
                        """);
            });
        }
    }


    @Override
    public void deleteAll() {
        try (Session session = driver.session()) {
            session.run("""
                        MATCH (n)
                        CALL (n){
                            DETACH DELETE n
                        } IN TRANSACTIONS OF 10000 ROWS
                    """).consume();
        }
    }

    @Override
    public void save(AnalysisResult result) {
        // 1. Prepare Classes batch
        List<Map<String, Object>> classBatch = result.classes().stream()
                .map(c -> Map.<String, Object>of(
                        "fqcn", c.fqcn(),
                        "simpleName", c.simpleName() != null ? c.simpleName() : "",
                        "kind", c.kind().name(),
                        "isAbstract", c.isAbstract()
                ))
                .toList();

        // 2. Prepare [:EXTENDS] relationships batch: class -> superclass, interface -> superinterfaces
        List<Map<String, Object>> extendsBatch = result.classes().stream()
                .flatMap(c -> {
                    Stream<String> supertypes = c.isInterface()
                            ? interfacesOf(c).stream()
                            : Stream.ofNullable(c.superClass());
                    return supertypes.map(superFqcn -> Map.<String, Object>of(
                            "subFqcn", c.fqcn(),
                            "superFqcn", superFqcn
                    ));
                })
                .toList();

        // 3. Prepare [:IMPLEMENTS] relationships batch: class -> interfaces (never interface -> interface)
        List<Map<String, Object>> implementsBatch = result.classes().stream()
                .filter(c -> !c.isInterface())
                .flatMap(c -> interfacesOf(c).stream()
                        .map(ifaceFqcn -> Map.<String, Object>of(
                                "classFqcn", c.fqcn(),
                                "interfaceFqcn", ifaceFqcn
                        )))
                .toList();

        // 4. Prepare Methods batch
        List<Map<String, Object>> methodBatch = result.methods().stream()
                .map(m -> Map.<String, Object>of(
                        "signature", m.signature(),
                        "name", m.name(),
                        "descriptor", m.descriptor(),
                        "ownerClassFqcn", m.ownerClassFqcn(),
                        "returnType", m.returnType() != null ? m.returnType() : "",
                        "parameterTypes", m.parameterTypes() != null ? m.parameterTypes() : List.of(),
                        "visibility", m.visibility().name(),
                        "isStatic", m.isStatic(),
                        "isAbstract", m.isAbstract(),
                        "isTransactional", m.isTransactional()
                ))
                .toList();

        // 5. Prepare [:OVERRIDES] relationships batch
        List<Map<String, Object>> overrideBatch = result.overrides().stream()
                .map(o -> Map.<String, Object>of(
                        "overridingSignature", o.overridingSignature(),
                        "overriddenSignature", o.overriddenSignature()
                ))
                .toList();

        // 6. Prepare [:CALLS] relationships batch (one row per distinct caller/target pair, not per call site)
        List<Map<String, Object>> callBatch = result.calls().stream()
                .distinct()
                .map(call -> Map.<String, Object>of(
                        "callerSignature", call.callerSignature(),
                        "targetSignature", call.targetSignature()
                ))
                .toList();

        // Steps run sequentially: each one MATCHes nodes created by the previous ones
        try (Session session = driver.session()) {
            // Step 1: Ingest Class nodes
            writeInChunks(session, """
                        UNWIND $batch AS row
                        MERGE (c:Class {fqcn: row.fqcn})
                        SET c.simpleName = row.simpleName,
                            c.kind = row.kind,
                            c.isAbstract = row.isAbstract
                    """, classBatch);

            // Step 2: Ingest [:EXTENDS] relationships (creates supertype stub if external)
            writeInChunks(session, """
                        UNWIND $batch AS row
                        MATCH (c:Class {fqcn: row.subFqcn})
                        MERGE (s:Class {fqcn: row.superFqcn})
                        MERGE (c)-[:EXTENDS]->(s)
                    """, extendsBatch);

            // Step 3: Ingest [:IMPLEMENTS] relationships (creates interface stub if external)
            writeInChunks(session, """
                        UNWIND $batch AS row
                        MATCH (c:Class {fqcn: row.classFqcn})
                        MERGE (i:Class {fqcn: row.interfaceFqcn})
                        MERGE (c)-[:IMPLEMENTS]->(i)
                    """, implementsBatch);

            // Step 4: Ingest Method nodes and bind to owner Class
            writeInChunks(session, """
                        UNWIND $batch AS row
                        MERGE (m:Method {signature: row.signature})
                        SET m.name = row.name,
                            m.descriptor = row.descriptor,
                            m.returnType = row.returnType,
                            m.parameterTypes = row.parameterTypes,
                            m.visibility = row.visibility,
                            m.isStatic = row.isStatic,
                            m.isAbstract = row.isAbstract,
                            m.isTransactional = row.isTransactional
                        WITH m, row
                        MATCH (c:Class {fqcn: row.ownerClassFqcn})
                        MERGE (c)-[:DECLARES]->(m)
                    """, methodBatch);

            // Step 5: Ingest [:OVERRIDES] relationships (both methods belong to the analyzed artifact)
            writeInChunks(session, """
                        UNWIND $batch AS row
                        MATCH (overriding:Method {signature: row.overridingSignature})
                        MATCH (overridden:Method {signature: row.overriddenSignature})
                        MERGE (overriding)-[:OVERRIDES]->(overridden)
                    """, overrideBatch);

            // Step 6: Ingest [:CALLS] relationships (creates method stub if external/JDK)
            writeInChunks(session, """
                        UNWIND $batch AS row
                        MATCH (caller:Method {signature: row.callerSignature})
                        MERGE (target:Method {signature: row.targetSignature})
                        MERGE (caller)-[:CALLS]->(target)
                    """, callBatch);
        }
    }

    private static List<String> interfacesOf(JavaClass javaClass) {
        return javaClass.interfaces() != null ? javaClass.interfaces() : List.of();
    }

    /**
     * Writes rows in fixed-size chunks, one managed transaction per chunk.
     * Bounds server-side transaction memory and lets the driver retry a single chunk on transient errors.
     * Safe to retry because every statement is MERGE-based (idempotent).
     */
    private void writeInChunks(Session session, String cypher, List<Map<String, Object>> rows) {
        for (int from = 0; from < rows.size(); from += chunkSize) {
            List<Map<String, Object>> chunk = rows.subList(from, Math.min(from + chunkSize, rows.size()));
            session.executeWriteWithoutResult(tx -> tx.run(cypher, Map.of("batch", chunk)).consume());
        }
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }
}
