package com.jgraph.mcp.graph;

import com.jgraph.mcp.fixture.FixtureGraph;
import com.jgraph.mcp.fixture.McpFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GraphQueries} on the graph of {@link McpFixture}, written by the analyzer pipeline ({@link FixtureGraph}).
 */
class GraphQueriesIT {

    private static final String STRING_TO_VOID = "(Ljava/lang/String;)V";

    private static GraphQueries queries;

    @BeforeAll
    static void connect() {
        queries = new GraphQueries(FixtureGraph.driver());
    }

    // --- Class names ---

    @Test
    void shouldFindClassesBySimpleOrQualifiedName() {
        String expected = internalName(McpFixture.PaymentService.class);

        assertThat(queries.findImplementations("PaymentService", "pay").classes()).containsExactly(expected);
        assertThat(queries.findImplementations("McpFixture.PaymentService", "pay").classes())
                .containsExactly(expected);
        assertThat(queries.findImplementations("com.jgraph.mcp.fixture.McpFixture.PaymentService", "pay").classes())
                .containsExactly(expected);
    }

    @Test
    void shouldMatchWholeNameSegmentsOnly() {
        // CardPaymentService and AbstractPaymentService end with "PaymentService", but not with ".PaymentService"
        assertThat(queries.findImplementations("PaymentService", "pay").classes()).hasSize(1);
        assertThat(queries.findImplementations("Service", "pay").classes()).isEmpty();
    }

    @Test
    void shouldIgnoreStubNodes() {
        // Repository is referenced as a supertype but never analyzed
        assertThat(queries.findImplementations("org.springframework.data.repository.Repository", "save").classes())
                .isEmpty();
    }

    @Test
    void shouldReportAMatchingClassThatDoesNotDeclareTheMethod() {
        ImplementationSearch search = queries.findImplementations("CardPaymentService", "refund");

        // refund is inherited from AbstractPaymentService
        assertThat(search.classes()).containsExactly(internalName(McpFixture.CardPaymentService.class));
        assertThat(search.methods()).isEmpty();
    }

    // --- Methods and implementations ---

    @Test
    void shouldReturnEveryImplementationOfAnInterfaceMethod() {
        Implementations pay = single(queries.findImplementations("PaymentService", "pay"));

        assertThat(pay.method().isAbstract()).isTrue();
        assertThat(pay.implementations())
                .extracting(MethodInfo::signature)
                .containsExactly(
                        signature(McpFixture.CardPaymentService.class, "pay" + STRING_TO_VOID),
                        signature(McpFixture.PaypalPaymentService.class, "pay" + STRING_TO_VOID));
        assertThat(pay.springDataRepository()).isFalse();
    }

    @Test
    void shouldReturnEveryOverload() {
        assertThat(queries.findImplementations("Notifier", "send").methods())
                .extracting(found -> found.method().signature())
                .containsExactly(
                        signature(McpFixture.Notifier.class, "send" + STRING_TO_VOID),
                        signature(McpFixture.Notifier.class, "send(Ljava/lang/String;Ljava/util/List;)V"));
    }

    @Test
    void shouldIncludeTheMethodItselfWhenItHasABody() {
        Implementations refund = single(queries.findImplementations("AbstractPaymentService", "refund"));

        assertThat(refund.implementations())
                .extracting(MethodInfo::signature)
                .containsExactly(
                        signature(McpFixture.AbstractPaymentService.class, "refund" + STRING_TO_VOID),
                        signature(McpFixture.PaypalPaymentService.class, "refund" + STRING_TO_VOID));
    }

    @Test
    void shouldReturnNoImplementationForAnAbstractMethodWithoutOverrides() {
        Implementations record = single(queries.findImplementations("AuditLog", "record"));

        assertThat(record.method().isAbstract()).isTrue();
        assertThat(record.implementations()).isEmpty();
        assertThat(record.springDataRepository()).isFalse();
    }

    @Test
    void shouldFlagSpringDataRepositories() {
        Implementations finder = single(queries.findImplementations("OrderRepository", "findByCustomerId"));

        // No implementation in the jar: Spring Data generates it at run time
        assertThat(finder.implementations()).isEmpty();
        assertThat(finder.springDataRepository()).isTrue();
    }

    @Test
    void shouldNotFlagSpringDataTypesThatAreNotRepositories() {
        Implementations tenant = single(queries.findImplementations("OrderAuditor", "currentTenant"));

        // Spring Data generates implementations for repositories only: this method is simply unimplemented
        assertThat(tenant.implementations()).isEmpty();
        assertThat(tenant.springDataRepository()).isFalse();
    }

    @Test
    void shouldNeverListBridgeMethods() {
        String saveOrder = signature(McpFixture.OrderStore.class,
                "save(L" + internalName(McpFixture.Order.class) + ";)V");

        // OrderStore also declares a bridge save(Object): neither the method asked about nor an implementation
        assertThat(queries.findImplementations("OrderStore", "save").methods())
                .extracting(found -> found.method().signature())
                .containsExactly(saveOrder);
        assertThat(single(queries.findImplementations("Store", "save")).implementations())
                .extracting(MethodInfo::signature)
                .containsExactly(saveOrder);
    }

    @Test
    void shouldMapMethodDetailsInSourceTypes() {
        MethodInfo pay = single(queries.findImplementations("CardPaymentService", "pay")).method();

        assertThat(pay.ownerFqcn()).isEqualTo(internalName(McpFixture.CardPaymentService.class));
        assertThat(pay.name()).isEqualTo("pay");
        assertThat(pay.returnType()).isEqualTo("void");
        assertThat(pay.parameterTypes()).containsExactly("java.lang.String");
        assertThat(pay.visibility()).isEqualTo("PUBLIC");
        assertThat(pay.isStatic()).isFalse();
    }

    private static Implementations single(ImplementationSearch search) {
        assertThat(search.methods()).hasSize(1);
        return search.methods().getFirst();
    }

    private static String signature(Class<?> owner, String nameAndDescriptor) {
        return internalName(owner) + "#" + nameAndDescriptor;
    }

    private static String internalName(Class<?> clazz) {
        return clazz.getName().replace('.', '/');
    }
}
