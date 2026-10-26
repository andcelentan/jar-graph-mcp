package com.jgraph.analyzer.repository;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.ClassKind;
import com.jgraph.analyzer.model.Invocation;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import com.jgraph.analyzer.model.MethodOverride;
import com.jgraph.analyzer.model.Visibility;
import com.jgraph.analyzer.parser.BridgeFixture;
import com.jgraph.analyzer.parser.CallFixture;
import com.jgraph.analyzer.parser.HierarchyFixture;
import com.jgraph.analyzer.parser.JarReaderTestFixture;
import com.jgraph.analyzer.parser.TestJars;
import com.jgraph.analyzer.parser.TypeKindFixture;
import com.jgraph.analyzer.resolver.ModelResolver;
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
                List.of(new MethodCall(log.signature(), println, Invocation.VIRTUAL, "java/io/PrintStream"))));

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

        AnalysisResult result = TestJars.parse(jarPath);
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

    @Test
    void shouldPersistClassAndInterfaceHierarchy() {
        JavaClass api = iface("com/acme/Api");
        JavaClass auditedApi = iface("com/acme/AuditedApi", api.fqcn());
        JavaClass base = abstractClazz("com/acme/AbstractService", auditedApi.fqcn());
        JavaClass service = subclass("com/acme/Service", base.fqcn());

        repository.save(new AnalysisResult(List.of(api, auditedApi, base, service), List.of(), List.of()));

        assertThat(count("""
                MATCH (:Class {fqcn: 'com/acme/Service'})-[r:EXTENDS]->(:Class {fqcn: 'com/acme/AbstractService'})
                RETURN count(r)
                """)).isEqualTo(1);
        assertThat(count("""
                MATCH (:Class {fqcn: 'com/acme/AbstractService'})-[r:IMPLEMENTS]->(:Class {fqcn: 'com/acme/AuditedApi'})
                RETURN count(r)
                """)).isEqualTo(1);
        // Interface inheritance is EXTENDS, never IMPLEMENTS
        assertThat(count("""
                MATCH (:Class {fqcn: 'com/acme/AuditedApi'})-[r:EXTENDS]->(:Class {fqcn: 'com/acme/Api'})
                RETURN count(r)
                """)).isEqualTo(1);
        assertThat(count("MATCH (:Class {kind: 'INTERFACE'})-[r:IMPLEMENTS]->() RETURN count(r)")).isZero();
        assertThat(count("MATCH (c:Class {fqcn: 'com/acme/AbstractService'}) WHERE c.isAbstract RETURN count(c)"))
                .isEqualTo(1);
    }

    @Test
    void shouldPersistKindsWithoutImplicitSupertypeNodes(@TempDir Path tempDir) throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("kinds.jar"), TypeKindFixture.CLASSES);
        AnalysisResult parsed = TestJars.parse(jarPath);

        repository.save(parsed);

        try (Session session = driver.session()) {
            List<String> kinds = session.run("MATCH (c:Class) WHERE c.kind IS NOT NULL RETURN c.kind AS kind")
                    .list(r -> r.get("kind").asString());
            assertThat(kinds).containsExactlyInAnyOrder("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION");
        }
        // No supernodes: implicit supertypes never become nodes through EXTENDS
        assertThat(count("""
                MATCH (c:Class)
                WHERE c.fqcn IN ['java/lang/Object', 'java/lang/Enum', 'java/lang/Record', 'java/lang/annotation/Annotation']
                RETURN count(c)
                """)).isZero();
    }

    @Test
    void shouldResolveCallsToImplementationsThroughOverrides() {
        // Checkout calls the interface; the code that runs is in a subclass (pay) and in an abstract base (refund)
        JavaClass api = iface("com/acme/PaymentService");
        JavaClass base = abstractClazz("com/acme/AbstractPaymentService", api.fqcn());
        JavaClass card = subclass("com/acme/CardPaymentService", base.fqcn());
        JavaClass checkout = clazz("com/acme/Checkout");

        JavaMethod pay = abstractMethod(api.fqcn(), "pay");
        JavaMethod refund = abstractMethod(api.fqcn(), "refund");
        JavaMethod baseRefund = method(base.fqcn(), "refund");
        JavaMethod cardPay = method(card.fqcn(), "pay");
        JavaMethod checkoutMethod = method(checkout.fqcn(), "checkout");

        repository.save(new AnalysisResult(
                List.of(api, base, card, checkout),
                List.of(pay, refund, baseRefund, cardPay, checkoutMethod),
                List.of(call(checkoutMethod, pay), call(checkoutMethod, refund)),
                List.of(override(cardPay, pay), override(baseRefund, refund))));

        assertThat(count("MATCH ()-[r:OVERRIDES]->() RETURN count(r)")).isEqualTo(2);
        assertThat(count("""
                MATCH (m:Method {signature: $signature})
                WHERE m.visibility = 'PUBLIC' AND NOT m.isStatic AND m.isAbstract
                RETURN count(m)
                """, Map.of("signature", pay.signature())))
                .isEqualTo(1);

        assertThat(resolveCallsFrom(checkoutMethod.signature()))
                .containsExactlyInAnyOrder(cardPay.signature(), baseRefund.signature());
    }

    @Test
    void shouldResolveCallsInHierarchyParsedFromJar(@TempDir Path tempDir) throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("hierarchy.jar"), HierarchyFixture.CLASSES);
        AnalysisResult parsed = TestJars.parse(jarPath);

        // Same pipeline as AppAnalyzer: parse -> resolve -> persist
        repository.save(new ModelResolver().resolve(parsed));

        String checkout = internalName(HierarchyFixture.Checkout.class);
        String card = internalName(HierarchyFixture.CardPaymentService.class);
        String base = internalName(HierarchyFixture.AbstractPaymentService.class);

        assertThat(count("""
                MATCH (:Class {fqcn: $card})-[r:EXTENDS]->(:Class {fqcn: $base})
                RETURN count(r)
                """, Map.of("card", card, "base", base)))
                .isEqualTo(1);

        // Checkout calls PaymentService#pay (interface): the code that runs is CardPaymentService#pay
        assertThat(resolveCallsFrom(checkout + "#checkout(Ljava/lang/String;)V"))
                .containsExactly(card + "#pay(Ljava/lang/String;)V");
    }

    @Test
    void shouldResolveGenericCallsToBridgedMethodNotToBridge(@TempDir Path tempDir) throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("bridges.jar"), BridgeFixture.CLASSES);
        AnalysisResult parsed = TestJars.parse(jarPath);

        repository.save(new ModelResolver().resolve(parsed));

        // Bridges stay in the graph, flagged, and never own an OVERRIDES relationship (ADR-0007)
        assertThat(count("MATCH (m:Method) WHERE m.isBridge RETURN count(m)")).isEqualTo(4);
        assertThat(count("MATCH (m:Method)-[r:OVERRIDES]->() WHERE m.isBridge RETURN count(r)")).isZero();

        // OrderController calls Repository#save(Object): the resolution query returns save(Order), not the bridge
        String controller = internalName(BridgeFixture.OrderController.class);
        String orderRepository = internalName(BridgeFixture.OrderRepository.class);
        String order = "L" + internalName(BridgeFixture.Order.class) + ";";
        assertThat(resolveCallsFrom(controller + "#submit(" + order + ")V"))
                .containsExactly(orderRepository + "#save(" + order + ")V");
    }

    @Test
    void shouldResolveInheritedAndNonDispatchingCalls(@TempDir Path tempDir) throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("calls.jar"), CallFixture.CLASSES);
        AnalysisResult parsed = TestJars.parse(jarPath);

        repository.save(new ModelResolver().resolve(parsed));

        String api = internalName(CallFixture.PaymentService.class);
        String base = internalName(CallFixture.AbstractPaymentService.class);
        String card = internalName(CallFixture.CardPaymentService.class);
        String paypal = internalName(CallFixture.PaypalPaymentService.class);
        String id = "(Ljava/lang/String;)V";

        // Calls carry how they are invoked and the type named in bytecode (ADR-0008)
        assertThat(count("""
                MATCH (:Method {signature: $caller})-[c:CALLS {invocation: 'SPECIAL', receiver: $base}]->
                      (:Method {signature: $target})
                RETURN count(c)
                """, Map.of("caller", paypal + "#refund" + id, "base", base, "target", base + "#refund" + id)))
                .isEqualTo(1);
        // No phantom node for a method that its owner, part of the analysis, does not declare
        assertThat(count("MATCH (m:Method {signature: $phantom}) RETURN count(m)",
                Map.of("phantom", card + "#refund" + id)))
                .isZero();

        // super.refund(): no dispatch, so PaypalPaymentService.refund does not appear to call itself
        assertThat(resolveCallsFrom(paypal + "#refund" + id)).containsExactly(base + "#refund" + id);

        // checkout(): card.refund() resolved upward also returns PaypalPaymentService.refund, a sibling override.
        // Accepted superset (ADR-0008): the right answer is always included
        String checkout = internalName(CallFixture.Checkout.class) + "#checkout(L" + card + ";L" + base + ";L" + api
                + ";Ljava/lang/String;)V";
        assertThat(resolveCallsFrom(checkout)).containsExactlyInAnyOrder(
                base + "#refund" + id,
                paypal + "#refund" + id,
                card + "#pay" + id,
                paypal + "#pay" + id,
                base + "#currency()Ljava/lang/String;");
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
        return new JavaClass(fqcn, simpleName(fqcn), ClassKind.CLASS, false, null, List.of(interfaces));
    }

    private static JavaClass abstractClazz(String fqcn, String... interfaces) {
        return new JavaClass(fqcn, simpleName(fqcn), ClassKind.CLASS, true, null, List.of(interfaces));
    }

    private static JavaClass subclass(String fqcn, String superClass) {
        return new JavaClass(fqcn, simpleName(fqcn), ClassKind.CLASS, false, superClass, List.of());
    }

    private static JavaClass iface(String fqcn, String... superInterfaces) {
        return new JavaClass(fqcn, simpleName(fqcn), ClassKind.INTERFACE, true, null, List.of(superInterfaces));
    }

    private static JavaMethod method(String ownerFqcn, String name) {
        return new JavaMethod(ownerFqcn, name, "()V", "void", List.of(), Visibility.PUBLIC, false, false, false, false);
    }

    private static JavaMethod abstractMethod(String ownerFqcn, String name) {
        return new JavaMethod(ownerFqcn, name, "()V", "void", List.of(), Visibility.PUBLIC, false, true, false, false);
    }

    private static MethodCall call(JavaMethod caller, JavaMethod target) {
        return new MethodCall(caller.signature(), target.signature(), Invocation.VIRTUAL, target.ownerClassFqcn());
    }

    private static MethodOverride override(JavaMethod overriding, JavaMethod overridden) {
        return new MethodOverride(overriding.signature(), overridden.signature());
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

    /**
     * Call resolution query documented in ADR-0008: the code that may run for every call made by {@code caller}.
     */
    private List<String> resolveCallsFrom(String caller) {
        try (Session session = driver.session()) {
            return session.run("""
                            MATCH (:Method {signature: $caller})-[call:CALLS]->(declared:Method)
                                  <-[:OVERRIDES*0..]-(impl:Method)
                            WHERE NOT impl.isAbstract
                              AND (impl = declared OR call.invocation IN ['VIRTUAL', 'INTERFACE'])
                            RETURN DISTINCT impl.signature AS impl
                            """, Map.of("caller", caller))
                    .list(r -> r.get("impl").asString());
        }
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
