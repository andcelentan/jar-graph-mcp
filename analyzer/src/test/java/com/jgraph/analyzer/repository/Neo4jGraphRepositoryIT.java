package com.jgraph.analyzer.repository;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import com.jgraph.analyzer.parser.JarReader;
import com.jgraph.analyzer.parser.JarReaderTestFixture;
import com.jgraph.analyzer.parser.TestJars;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests against a real Neo4j instance (same image as docker-compose.yml).
 * Requires a running Docker daemon; executed by maven-failsafe during {@code mvn verify}.
 */
@Testcontainers
class Neo4jGraphRepositoryIT {

    // Small chunk size forces multi-chunk writes, with a remainder chunk, on tiny fixtures
    private static final int TEST_CHUNK_SIZE = 2;

    @Container
    private static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5.26-community")
            .withoutAuthentication();

    private static Driver driver;

    // Not closed per test: close() would also close the shared driver
    private Neo4jGraphRepository repository;

    @BeforeAll
    static void openDriver() {
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.none());
    }

    @AfterAll
    static void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    @BeforeEach
    void resetGraph() {
        repository = new Neo4jGraphRepository(driver, TEST_CHUNK_SIZE);
        repository.initSchema();
        repository.deleteAll();
    }

    @Test
    void shouldPersistEveryRowAcrossChunkBoundaries() {
        // 1 interface, 5 classes, 5 methods, 5 calls: none is a multiple of the chunk size
        JavaClass api = iface("com/acme/Api");
        List<JavaClass> services = IntStream.range(0, 5)
                .mapToObj(i -> clazz("com/acme/Service" + i, api.fqcn()))
                .toList();
        List<JavaMethod> methods = services.stream()
                .map(c -> method(c.fqcn(), "run"))
                .toList();
        // Ring of calls: the last caller lives in the last (remainder) methods chunk
        List<MethodCall> calls = IntStream.range(0, methods.size())
                .mapToObj(i -> call(methods.get(i), methods.get((i + 1) % methods.size())))
                .toList();

        repository.save(new AnalysisResult(concat(List.of(api), services), methods, calls));

        assertThat(count("MATCH (c:Class) RETURN count(c)")).isEqualTo(6);
        assertThat(count("MATCH (:Class)-[r:IMPLEMENTS]->(:Class {fqcn: 'com/acme/Api'}) RETURN count(r)")).isEqualTo(5);
        assertThat(count("MATCH (m:Method) RETURN count(m)")).isEqualTo(5);
        assertThat(count("MATCH (m:Method) WHERE NOT ()-[:DECLARES]->(m) RETURN count(m)")).isZero();
        assertThat(count("MATCH ()-[r:CALLS]->() RETURN count(r)")).isEqualTo(5);
        assertThat(count("""
                MATCH (:Method {signature: $caller})-[r:CALLS]->(:Method {signature: $target})
                RETURN count(r)
                """, Map.of("caller", methods.get(4).signature(), "target", methods.get(0).signature())))
                .isEqualTo(1);
    }

    @Test
    void saveShouldBeIdempotent() {
        AnalysisResult result = sampleResult();

        repository.save(result);
        GraphSize afterFirstSave = graphSize();
        repository.save(result);

        assertThat(graphSize()).isEqualTo(afterFirstSave);
        // 2 classes + 2 methods; 1 IMPLEMENTS + 2 DECLARES + 1 CALLS
        assertThat(afterFirstSave).isEqualTo(new GraphSize(4, 4));
    }

    @Test
    void shouldCreateSingleCallsRelationshipPerCallerTargetPair() {
        JavaClass service = clazz("com/acme/Service");
        JavaMethod caller = method(service.fqcn(), "caller");
        JavaMethod target = method(service.fqcn(), "target");
        // Three call sites of the same target, spread over more than one chunk
        List<MethodCall> calls = List.of(call(caller, target), call(caller, target), call(caller, target));

        repository.save(new AnalysisResult(List.of(service), List.of(caller, target), calls));

        assertThat(count("MATCH ()-[r:CALLS]->() RETURN count(r)")).isEqualTo(1);
    }

    @Test
    void shouldCreateStubNodesForExternalTypesAndMethods() {
        JavaClass service = clazz("com/acme/Service", "java/io/Serializable");
        JavaMethod log = method(service.fqcn(), "log");
        String println = "java/io/PrintStream#println(Ljava/lang/String;)V";

        repository.save(new AnalysisResult(
                List.of(service),
                List.of(log),
                List.of(new MethodCall(log.signature(), println))));

        assertThat(count("""
                MATCH (:Class {fqcn: 'com/acme/Service'})-[r:IMPLEMENTS]->(:Class {fqcn: 'java/io/Serializable'})
                RETURN count(r)
                """)).isEqualTo(1);
        assertThat(count("""
                MATCH (log:Method {signature: $caller})-[:CALLS]->(external:Method {signature: $target})
                WHERE NOT ()-[:DECLARES]->(external)
                RETURN count(external)
                """, Map.of("caller", log.signature(), "target", println)))
                .isEqualTo(1);
    }

    @Test
    void initSchemaShouldBeIdempotentAndCreateUniqueConstraints() {
        // Already invoked once in @BeforeEach
        assertThatCode(repository::initSchema).doesNotThrowAnyException();

        try (Session session = driver.session()) {
            List<String> constraints = session.run("SHOW CONSTRAINTS YIELD name RETURN name")
                    .list(r -> r.get("name").asString());
            assertThat(constraints).contains("unique_class_fqcn", "unique_method_signature");
        }
    }

    @Test
    void deleteAllShouldRemoveEveryNodeAndRelationship() {
        repository.save(sampleResult());
        assertThat(graphSize().nodes()).isPositive();

        repository.deleteAll();

        assertThat(graphSize()).isEqualTo(new GraphSize(0, 0));
    }

    @Test
    void shouldPersistGraphParsedFromJar(@TempDir Path tempDir) throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("fixture.jar"),
                JarReaderTestFixture.class,
                JarReaderTestFixture.TestInterface.class,
                JarReaderTestFixture.TestImplementation.class);

        AnalysisResult result;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            result = new JarReader(jarFile).readAndBuildModel();
        }
        repository.save(result);

        String impl = internalName(JarReaderTestFixture.TestImplementation.class);
        String iface = internalName(JarReaderTestFixture.TestInterface.class);

        assertThat(count("""
                MATCH (:Class {fqcn: $impl})-[r:IMPLEMENTS]->(:Class {fqcn: $iface})
                RETURN count(r)
                """, Map.of("impl", impl, "iface", iface)))
                .isEqualTo(1);
        // Internal call resolved to a method declared by the same class
        assertThat(count("""
                MATCH (c:Class {fqcn: $impl})-[:DECLARES]->(caller:Method {name: 'transactionalMethod'})
                      -[:CALLS]->(target:Method {name: 'privateMethod'})<-[:DECLARES]-(c)
                WHERE caller.isTransactional
                RETURN count(target)
                """, Map.of("impl", impl)))
                .isEqualTo(1);
    }

    // --- Fixtures ---

    private static AnalysisResult sampleResult() {
        JavaClass api = iface("com/acme/Api");
        JavaClass service = clazz("com/acme/Service", api.fqcn());
        JavaMethod caller = method(service.fqcn(), "caller");
        JavaMethod target = method(service.fqcn(), "target");
        return new AnalysisResult(List.of(api, service), List.of(caller, target), List.of(call(caller, target)));
    }

    private static JavaClass clazz(String fqcn, String... interfaces) {
        return new JavaClass(fqcn, simpleName(fqcn), false, List.of(interfaces));
    }

    private static JavaClass iface(String fqcn) {
        return new JavaClass(fqcn, simpleName(fqcn), true, List.of());
    }

    private static JavaMethod method(String ownerFqcn, String name) {
        return new JavaMethod(ownerFqcn, name, "()V", "void", List.of(), false);
    }

    private static MethodCall call(JavaMethod caller, JavaMethod target) {
        return new MethodCall(caller.signature(), target.signature());
    }

    private static String simpleName(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('/') + 1);
    }

    private static String internalName(Class<?> clazz) {
        return clazz.getName().replace('.', '/');
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    // --- Graph assertions ---

    private record GraphSize(long nodes, long relationships) {
    }

    private GraphSize graphSize() {
        return new GraphSize(
                count("MATCH (n) RETURN count(n)"),
                count("MATCH ()-[r]->() RETURN count(r)"));
    }

    private long count(String cypher) {
        return count(cypher, Map.of());
    }

    private long count(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            return session.run(cypher, params).single().get(0).asLong();
        }
    }
}
