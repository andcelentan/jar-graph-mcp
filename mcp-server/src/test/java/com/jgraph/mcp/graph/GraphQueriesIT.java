package com.jgraph.mcp.graph;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.parser.JarReader;
import com.jgraph.analyzer.parser.TestJars;
import com.jgraph.analyzer.repository.Neo4jGraphRepository;
import com.jgraph.analyzer.resolver.ModelResolver;
import com.jgraph.mcp.fixture.McpFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GraphQueries} against a real Neo4j (ADR-0005), on a graph written by the analyzer pipeline itself
 * (parse → resolve → save), so the queries are tested on the same graph the server reads in production (ADR-0009).
 */
@Testcontainers
class GraphQueriesIT {

    private static final String STRING_TO_VOID = "(Ljava/lang/String;)V";

    @Container
    private static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5.26-community").withoutAuthentication();

    @TempDir
    private static Path tempDir;

    private static Driver driver;
    private static GraphQueries queries;

    @BeforeAll
    static void loadFixtureGraph() throws IOException {
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.none());

        Path jarPath = TestJars.createJar(tempDir.resolve("fixture.jar"), McpFixture.CLASSES);
        AnalysisResult parsed;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            parsed = new JarReader(jarFile).readAndBuildModel();
        }
        // Not closed: close() would also close the shared driver
        Neo4jGraphRepository repository = new Neo4jGraphRepository(driver);
        repository.initSchema();
        repository.deleteAll();
        repository.save(new ModelResolver().resolve(parsed));

        queries = new GraphQueries(driver);
    }

    @AfterAll
    static void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void shouldReturnEveryImplementationOfAnInterfaceMethod() {
        Implementations result = queries.findImplementations(
                signature(McpFixture.PaymentService.class, "pay" + STRING_TO_VOID)).orElseThrow();

        assertThat(result.method().isAbstract()).isTrue();
        assertThat(result.implementations())
                .extracting(MethodInfo::signature)
                .containsExactly(
                        signature(McpFixture.CardPaymentService.class, "pay" + STRING_TO_VOID),
                        signature(McpFixture.PaypalPaymentService.class, "pay" + STRING_TO_VOID));
        assertThat(result.springDataRepository()).isFalse();
    }

    @Test
    void shouldIncludeTheMethodItselfWhenItHasABody() {
        Implementations result = queries.findImplementations(
                signature(McpFixture.AbstractPaymentService.class, "refund" + STRING_TO_VOID)).orElseThrow();

        assertThat(result.implementations())
                .extracting(MethodInfo::signature)
                .containsExactly(
                        signature(McpFixture.AbstractPaymentService.class, "refund" + STRING_TO_VOID),
                        signature(McpFixture.PaypalPaymentService.class, "refund" + STRING_TO_VOID));
    }

    @Test
    void shouldReturnNoImplementationForAnAbstractMethodWithoutOverrides() {
        Implementations result = queries.findImplementations(
                signature(McpFixture.AuditLog.class, "record" + STRING_TO_VOID)).orElseThrow();

        assertThat(result.method().isAbstract()).isTrue();
        assertThat(result.implementations()).isEmpty();
        assertThat(result.springDataRepository()).isFalse();
    }

    @Test
    void shouldFlagSpringDataRepositories() {
        Implementations result = queries.findImplementations(
                signature(McpFixture.OrderRepository.class, "findByCustomerId(Ljava/lang/String;)Ljava/util/List;"))
                .orElseThrow();

        // No implementation in the jar: Spring Data generates it at run time
        assertThat(result.implementations()).isEmpty();
        assertThat(result.springDataRepository()).isTrue();
    }

    @Test
    void shouldNotFlagSpringDataTypesThatAreNotRepositories() {
        Implementations result = queries.findImplementations(
                signature(McpFixture.OrderAuditor.class, "currentTenant()Ljava/lang/String;")).orElseThrow();

        // Spring Data generates implementations for repositories only: this method is simply unimplemented
        assertThat(result.implementations()).isEmpty();
        assertThat(result.springDataRepository()).isFalse();
    }

    @Test
    void shouldNeverListBridgeMethods() {
        Implementations result = queries.findImplementations(
                signature(McpFixture.Store.class, "save(Ljava/lang/Object;)V")).orElseThrow();

        // OrderStore also declares a bridge save(Object): only the method the developer wrote is listed
        assertThat(result.implementations())
                .extracting(MethodInfo::signature)
                .containsExactly(signature(McpFixture.OrderStore.class,
                        "save(L" + internalName(McpFixture.Order.class) + ";)V"));
    }

    @Test
    void shouldReturnNothingForMethodsOutsideTheAnalysis() {
        assertThat(queries.findImplementations("java/util/List#add(Ljava/lang/Object;)Z")).isEmpty();
        assertThat(queries.findImplementations(signature(McpFixture.PaymentService.class, "missing()V"))).isEmpty();
    }

    @Test
    void shouldMapMethodDetailsInSourceTypes() {
        MethodInfo pay = queries.findImplementations(
                signature(McpFixture.CardPaymentService.class, "pay" + STRING_TO_VOID)).orElseThrow().method();

        assertThat(pay.ownerFqcn()).isEqualTo(internalName(McpFixture.CardPaymentService.class));
        assertThat(pay.name()).isEqualTo("pay");
        assertThat(pay.returnType()).isEqualTo("void");
        assertThat(pay.parameterTypes()).containsExactly("java.lang.String");
        assertThat(pay.visibility()).isEqualTo("PUBLIC");
        assertThat(pay.isStatic()).isFalse();
    }

    private static String signature(Class<?> owner, String nameAndDescriptor) {
        return internalName(owner) + "#" + nameAndDescriptor;
    }

    private static String internalName(Class<?> clazz) {
        return clazz.getName().replace('.', '/');
    }
}
