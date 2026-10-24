package com.jgraph.mcp.callers;

import com.jgraph.mcp.callers.FindCallersResult.MethodCallers;
import com.jgraph.mcp.fixture.FixtureGraph;
import com.jgraph.mcp.fixture.McpFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Specification of the MCP tool {@code find_callers}, on the graph of {@link McpFixture}: the ADR-0008 resolution
 * query read backwards. A method is called by every direct call to it, and by every {@code VIRTUAL} or
 * {@code INTERFACE} call to a method it overrides, which may dispatch to it at run time; {@code super} and static
 * calls never dispatch.
 * <p>
 * Contract, as the assistant sees it (ADR-0009):
 * <ul>
 *   <li>{@code FindCallersTool(FindCallersQuery)}, with {@code FindCallersResult findCallers(String className,
 *       String methodName)}; names match like in {@code find_implementations}, every class and overload, one query.</li>
 *   <li>{@code FindCallersResult(methods, note)}, one {@code MethodCallers(method, callers, note)} per matching
 *       method; the top-level note only when no method matches, the method note only when it has no caller.</li>
 *   <li>{@code CallerSummary(caller, invocation, through)}: the calling method, the instruction ({@code "VIRTUAL"},
 *       {@code "INTERFACE"}, {@code "SPECIAL"}, {@code "STATIC"}), and the overridden method the call names when it
 *       reaches the method by dispatch, {@code null} for a direct call.</li>
 * </ul>
 * Lists may come in any order.
 */
class FindCallersToolIT {

    private static final String FIXTURE = McpFixture.class.getName();

    private static FindCallersTool tool;

    @BeforeAll
    static void connect() {
        tool = new FindCallersTool(new FindCallersQuery(FixtureGraph.driver()));
    }

    @Test
    void shouldListTheDirectCallersOfAnInterfaceMethod() {
        FindCallersResult result = tool.findCallers("PaymentService", "pay");

        assertThat(result.note()).isNull();
        MethodCallers pay = single(result);
        assertThat(pay.method().className()).isEqualTo(FIXTURE + "$PaymentService");
        // The lambda in Checkout.checkoutAll calls payments.pay(orderId)
        assertThat(pay.callers())
                .extracting(c -> c.caller().className(), c -> c.caller().name(), c -> c.caller().isLambdaBody(),
                        CallerSummary::invocation, CallerSummary::through)
                .containsExactly(tuple(FIXTURE + "$Checkout", "lambda$checkoutAll$0", true, "INTERFACE", null));
        assertThat(pay.note()).isNull();
    }

    @Test
    void shouldListCallsThatMayDispatchToAnImplementation() {
        MethodCallers pay = single(tool.findCallers("CardPaymentService", "pay"));

        // The call names PaymentService#pay: at run time it may run CardPaymentService#pay, or another implementation
        assertThat(pay.callers())
                .extracting(c -> c.caller().name(), CallerSummary::invocation, c -> c.through().className())
                .containsExactly(tuple("lambda$checkoutAll$0", "INTERFACE", FIXTURE + "$PaymentService"));
    }

    @Test
    void shouldCountSuperCallsAndCallsOnInheritingTypesAsDirect() {
        MethodCallers refund = single(tool.findCallers("AbstractPaymentService", "refund"));

        assertThat(refund.callers())
                .extracting(c -> c.caller().className(), CallerSummary::invocation, CallerSummary::through)
                .containsExactlyInAnyOrder(
                        // super.refund(orderId)
                        tuple(FIXTURE + "$PaypalPaymentService", "SPECIAL", null),
                        // card.refund(orderId): bytecode names CardPaymentService, which inherits refund
                        tuple(FIXTURE + "$CardRefunds", "VIRTUAL", null));
    }

    @Test
    void shouldNeverCountSuperCallsAsDispatch() {
        MethodCallers refund = single(tool.findCallers("PaypalPaymentService", "refund"));

        // PaypalPaymentService#refund calls super.refund (SPECIAL): it always runs AbstractPaymentService#refund, so
        // it is no caller of itself. CardRefunds stays, through the overridden method: a known superset (ADR-0008),
        // since its receiver, CardPaymentService, can never be a PaypalPaymentService
        assertThat(refund.callers())
                .extracting(c -> c.caller().className(), CallerSummary::invocation, c -> c.through().className())
                .containsExactly(tuple(FIXTURE + "$CardRefunds", "VIRTUAL", FIXTURE + "$AbstractPaymentService"));
    }

    @Test
    void shouldListStaticCallers() {
        MethodCallers format = single(tool.findCallers("Receipts", "format"));

        assertThat(format.callers())
                .extracting(c -> c.caller().name(), CallerSummary::invocation, CallerSummary::through)
                .containsExactly(tuple("print", "STATIC", null));
    }

    @Test
    void shouldNeverListBridgesAsCallers() {
        MethodCallers save = single(tool.findCallers("OrderStore", "save"));

        // The bridge save(Object) of OrderStore calls save(Order): the real caller is the one calling Store#save
        assertThat(save.method().parameterTypes()).containsExactly(FIXTURE + "$Order");
        assertThat(save.callers())
                .extracting(c -> c.caller().className(), CallerSummary::invocation, c -> c.through().parameterTypes())
                .containsExactly(tuple(FIXTURE + "$Orders", "INTERFACE", List.of("java.lang.Object")));
    }

    @Test
    void shouldExplainThatAMethodWithoutCallersIsNotNecessarilyUnused() {
        FindCallersResult result = tool.findCallers("AuditLog", "record");

        assertThat(result.note()).isNull();
        MethodCallers record = single(result);
        assertThat(record.callers()).isEmpty();
        assertThat(record.note()).contains("No caller in the analysis").contains("framework");
    }

    @Test
    void shouldReturnEveryOverload() {
        assertThat(tool.findCallers("Notifier", "send").methods())
                .extracting(found -> found.method().parameterTypes())
                .containsExactlyInAnyOrder(List.of("java.lang.String"), List.of("java.lang.String", "java.util.List"));
    }

    @Test
    void shouldExplainAMethodTheClassDoesNotDeclare() {
        FindCallersResult result = tool.findCallers("CardPaymentService", "refund");

        assertThat(result.methods()).isEmpty();
        assertThat(result.note())
                .contains(FIXTURE + "$CardPaymentService")
                .contains("no method named 'refund'")
                .contains("inherited");
    }

    @Test
    void shouldExplainAClassOutsideTheAnalysis() {
        FindCallersResult result = tool.findCallers("java.util.List", "add");

        assertThat(result.methods()).isEmpty();
        assertThat(result.note()).contains("No class of the analysis is named 'java.util.List'");
    }

    @Test
    void shouldRejectBlankNames() {
        assertThatIllegalArgumentException().isThrownBy(() -> tool.findCallers(" ", "pay"));
        assertThatIllegalArgumentException().isThrownBy(() -> tool.findCallers("PaymentService", ""));
    }

    private static MethodCallers single(FindCallersResult result) {
        assertThat(result.methods()).hasSize(1);
        return result.methods().getFirst();
    }
}
