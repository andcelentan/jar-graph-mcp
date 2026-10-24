package com.jgraph.mcp.implementations;

import com.jgraph.mcp.fixture.FixtureGraph;
import com.jgraph.mcp.fixture.McpFixture;
import com.jgraph.mcp.shared.MethodInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FindImplementationsQuery} on the graph of {@link McpFixture}, written by the analyzer pipeline ({@link FixtureGraph}).
 */
class FindImplementationsQueryIT {

    private static final String STRING_TO_VOID = "(Ljava/lang/String;)V";

    private static FindImplementationsQuery query;

    @BeforeAll
    static void connect() {
        query = new FindImplementationsQuery(FixtureGraph.driver());
    }

    // --- Class names ---

    @Test
    void shouldFindClassesBySimpleOrQualifiedName() {
        String expected = internalName(McpFixture.PaymentService.class);

        assertThat(query.find("PaymentService", "pay").classes()).containsExactly(expected);
        assertThat(query.find("McpFixture.PaymentService", "pay").classes())
                .containsExactly(expected);
        assertThat(query.find("com.jgraph.mcp.fixture.McpFixture.PaymentService", "pay").classes())
                .containsExactly(expected);
    }

    @Test
    void shouldMatchWholeNameSegmentsOnly() {
        // CardPaymentService and AbstractPaymentService end with "PaymentService", but not with ".PaymentService"
        assertThat(query.find("PaymentService", "pay").classes()).hasSize(1);
        assertThat(query.find("Service", "pay").classes()).isEmpty();
    }

    @Test
    void shouldIgnoreStubNodes() {
        // Repository is referenced as a supertype but never analyzed
        assertThat(query.find("org.springframework.data.repository.Repository", "save").classes())
                .isEmpty();
    }

    @Test
    void shouldReportAMatchingClassThatDoesNotDeclareTheMethod() {
        ImplementationSearch search = query.find("CardPaymentService", "refund");

        // refund is inherited from AbstractPaymentService
        assertThat(search.classes()).containsExactly(internalName(McpFixture.CardPaymentService.class));
        assertThat(search.methods()).isEmpty();
    }

    // --- Methods and implementations ---

    @Test
    void shouldReturnEveryImplementationOfAnInterfaceMethod() {
        Implementations pay = single(query.find("PaymentService", "pay"));

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
        assertThat(query.find("Notifier", "send").methods())
                .extracting(found -> found.method().signature())
                .containsExactly(
                        signature(McpFixture.Notifier.class, "send" + STRING_TO_VOID),
                        signature(McpFixture.Notifier.class, "send(Ljava/lang/String;Ljava/util/List;)V"));
    }

    @Test
    void shouldIncludeTheMethodItselfWhenItHasABody() {
        Implementations refund = single(query.find("AbstractPaymentService", "refund"));

        assertThat(refund.implementations())
                .extracting(MethodInfo::signature)
                .containsExactly(
                        signature(McpFixture.AbstractPaymentService.class, "refund" + STRING_TO_VOID),
                        signature(McpFixture.PaypalPaymentService.class, "refund" + STRING_TO_VOID));
    }

    @Test
    void shouldReturnNoImplementationForAnAbstractMethodWithoutOverrides() {
        Implementations record = single(query.find("AuditLog", "record"));

        assertThat(record.method().isAbstract()).isTrue();
        assertThat(record.implementations()).isEmpty();
        assertThat(record.springDataRepository()).isFalse();
    }

    @Test
    void shouldFlagSpringDataRepositories() {
        Implementations finder = single(query.find("OrderRepository", "findByCustomerId"));

        // No implementation in the jar: Spring Data generates it at run time
        assertThat(finder.implementations()).isEmpty();
        assertThat(finder.springDataRepository()).isTrue();
    }

    @Test
    void shouldNotFlagSpringDataTypesThatAreNotRepositories() {
        Implementations tenant = single(query.find("OrderAuditor", "currentTenant"));

        // Spring Data generates implementations for repositories only: this method is simply unimplemented
        assertThat(tenant.implementations()).isEmpty();
        assertThat(tenant.springDataRepository()).isFalse();
    }

    @Test
    void shouldNeverListBridgeMethods() {
        String saveOrder = signature(McpFixture.OrderStore.class,
                "save(L" + internalName(McpFixture.Order.class) + ";)V");

        // OrderStore also declares a bridge save(Object): neither the method asked about nor an implementation
        assertThat(query.find("OrderStore", "save").methods())
                .extracting(found -> found.method().signature())
                .containsExactly(saveOrder);
        assertThat(single(query.find("Store", "save")).implementations())
                .extracting(MethodInfo::signature)
                .containsExactly(saveOrder);
    }

    @Test
    void shouldMapMethodDetailsInSourceTypes() {
        MethodInfo pay = single(query.find("CardPaymentService", "pay")).method();

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
