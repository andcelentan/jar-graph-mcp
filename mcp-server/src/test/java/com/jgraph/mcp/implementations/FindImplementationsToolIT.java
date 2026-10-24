package com.jgraph.mcp.implementations;

import com.jgraph.mcp.fixture.FixtureGraph;
import com.jgraph.mcp.fixture.McpFixture;
import com.jgraph.mcp.implementations.FindImplementationsResult.MethodImplementations;
import com.jgraph.mcp.shared.MethodSummary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link FindImplementationsTool} on the graph of {@link McpFixture}: one test per kind of answer, called with names
 * as an assistant writes them.
 */
class FindImplementationsToolIT {

    private static final String FIXTURE = McpFixture.class.getName();

    private static FindImplementationsTool tool;

    @BeforeAll
    static void connect() {
        tool = new FindImplementationsTool(new FindImplementationsQuery(FixtureGraph.driver()));
    }

    @Test
    void shouldListTheImplementationsOfAnInterfaceMethod() {
        FindImplementationsResult result = tool.findImplementations("PaymentService", "pay");

        assertThat(result.note()).isNull();
        MethodImplementations pay = single(result);
        assertThat(pay.method().className()).isEqualTo(FIXTURE + "$PaymentService");
        assertThat(pay.method().isAbstract()).isTrue();
        assertThat(pay.implementations())
                .extracting(MethodSummary::className, MethodSummary::parameterTypes)
                .containsExactly(
                        tuple(FIXTURE + "$CardPaymentService", List.of("java.lang.String")),
                        tuple(FIXTURE + "$PaypalPaymentService", List.of("java.lang.String")));
        assertThat(pay.note()).isNull();
    }

    @Test
    void shouldAcceptQualifiedAndBinaryNames() {
        assertThat(single(tool.findImplementations(FIXTURE + ".PaymentService", "pay")).implementations()).hasSize(2);
        assertThat(single(tool.findImplementations(FIXTURE + "$PaymentService", "pay")).implementations()).hasSize(2);
    }

    @Test
    void shouldReturnEveryOverloadInsteadOfGuessing() {
        assertThat(tool.findImplementations("Notifier", "send").methods())
                .extracting(found -> found.method().parameterTypes())
                .containsExactly(List.of("java.lang.String"), List.of("java.lang.String", "java.util.List"));
    }

    @Test
    void shouldExplainAnAbstractMethodWithoutImplementations() {
        MethodImplementations record = single(tool.findImplementations("AuditLog", "record"));

        assertThat(record.implementations()).isEmpty();
        assertThat(record.note()).contains("no implementation in the analysis");
    }

    @Test
    void shouldExplainThatSpringDataImplementsRepositoryMethods() {
        MethodImplementations finder = single(tool.findImplementations("OrderRepository", "findByCustomerId"));

        assertThat(finder.implementations()).isEmpty();
        assertThat(finder.note()).contains("Spring Data implements it at run time");
    }

    @Test
    void shouldExplainAMethodTheClassDoesNotDeclare() {
        FindImplementationsResult result = tool.findImplementations("CardPaymentService", "refund");

        assertThat(result.methods()).isEmpty();
        assertThat(result.note())
                .contains(FIXTURE + "$CardPaymentService")
                .contains("no method named 'refund'")
                .contains("inherited");
    }

    @Test
    void shouldExplainAClassOutsideTheAnalysis() {
        FindImplementationsResult result = tool.findImplementations("java.util.List", "add");

        assertThat(result.methods()).isEmpty();
        assertThat(result.note()).contains("No class of the analysis is named 'java.util.List'");
    }

    @Test
    void shouldRejectBlankNames() {
        assertThatIllegalArgumentException().isThrownBy(() -> tool.findImplementations(" ", "pay"));
        assertThatIllegalArgumentException().isThrownBy(() -> tool.findImplementations("PaymentService", ""));
    }

    private static MethodImplementations single(FindImplementationsResult result) {
        assertThat(result.methods()).hasSize(1);
        return result.methods().getFirst();
    }
}
