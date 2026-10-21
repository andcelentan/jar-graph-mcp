package com.jgraph.mcp.tool;

import com.jgraph.mcp.fixture.FixtureGraph;
import com.jgraph.mcp.fixture.McpFixture;
import com.jgraph.mcp.graph.GraphQueries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Specification of the MCP tool {@code describe_class}, on the graph of {@link McpFixture}.
 * <p>
 * Contract, as the assistant sees it (ADR-0009):
 * <ul>
 *   <li>{@code DescribeClassTool(GraphQueries)}, with {@code DescribeClassResult describeClass(String className)};
 *       class names match like in {@code find_implementations} (simple or qualified, whole segments), one query.</li>
 *   <li>{@code DescribeClassResult(classes, note)}: one {@code ClassDescription} per matching class; the note is
 *       present only when no class matches.</li>
 *   <li>{@code ClassDescription(className, kind, isAbstract, supertypes, subtypes, methods)}: binary class name,
 *       {@code kind} as stored in the graph ({@code "CLASS"}, {@code "INTERFACE"}...), direct supertypes and direct
 *       subtypes as {@code TypeSummary}, declared methods as {@code MethodSummary} (bridges excluded).</li>
 *   <li>{@code TypeSummary(className, relation, inAnalysis)}: {@code relation} is the relationship as a string,
 *       {@code "EXTENDS"} or {@code "IMPLEMENTS"}; {@code inAnalysis} is false for stub types (JDK, libraries).</li>
 *   <li>{@code MethodSummary} gains {@code isLambdaBody}: true for the synthetic methods javac creates for lambda
 *       bodies ({@code lambda$<enclosing method>$<n>}).</li>
 * </ul>
 * Lists may come in any order.
 */
class DescribeClassToolIT {

    private static final String FIXTURE = McpFixture.class.getName();

    private static DescribeClassTool tool;

    @BeforeAll
    static void connect() {
        tool = new DescribeClassTool(new GraphQueries(FixtureGraph.driver()));
    }

    @Test
    void shouldDescribeAnInterfaceWithItsDirectSubtypesOnly() {
        DescribeClassResult result = tool.describeClass("PaymentService");

        assertThat(result.note()).isNull();
        ClassDescription paymentService = single(result);
        assertThat(paymentService.className()).isEqualTo(FIXTURE + "$PaymentService");
        assertThat(paymentService.kind()).isEqualTo("INTERFACE");
        assertThat(paymentService.supertypes()).isEmpty();
        // CardPaymentService and PaypalPaymentService implement it through AbstractPaymentService: not direct
        assertThat(paymentService.subtypes())
                .extracting(TypeSummary::className, TypeSummary::relation, TypeSummary::inAnalysis)
                .containsExactly(tuple(FIXTURE + "$AbstractPaymentService", "IMPLEMENTS", true));
        assertThat(paymentService.methods()).extracting(MethodSummary::name).containsExactly("pay");
    }

    @Test
    void shouldDescribeAnAbstractClassWithItsHierarchyAndMethods() {
        ClassDescription abstractService = single(tool.describeClass("AbstractPaymentService"));

        assertThat(abstractService.kind()).isEqualTo("CLASS");
        assertThat(abstractService.isAbstract()).isTrue();
        assertThat(abstractService.supertypes())
                .extracting(TypeSummary::className, TypeSummary::relation, TypeSummary::inAnalysis)
                .containsExactly(tuple(FIXTURE + "$PaymentService", "IMPLEMENTS", true));
        assertThat(abstractService.subtypes())
                .extracting(TypeSummary::className, TypeSummary::relation)
                .containsExactlyInAnyOrder(
                        tuple(FIXTURE + "$CardPaymentService", "EXTENDS"),
                        tuple(FIXTURE + "$PaypalPaymentService", "EXTENDS"));
        // Constructors are methods in bytecode, named <init>
        assertThat(abstractService.methods())
                .extracting(MethodSummary::name)
                .containsExactlyInAnyOrder("<init>", "refund");
    }

    @Test
    void shouldShowTheSuperclassAnInheritedMethodComesFrom() {
        // find_implementations("CardPaymentService", "refund") finds nothing: the hierarchy shows where to look
        ClassDescription cardPaymentService = single(tool.describeClass("CardPaymentService"));

        assertThat(cardPaymentService.isAbstract()).isFalse();
        assertThat(cardPaymentService.supertypes())
                .extracting(TypeSummary::className, TypeSummary::relation, TypeSummary::inAnalysis)
                .containsExactly(tuple(FIXTURE + "$AbstractPaymentService", "EXTENDS", true));
        assertThat(cardPaymentService.subtypes()).isEmpty();
        assertThat(cardPaymentService.methods())
                .extracting(MethodSummary::name)
                .containsExactlyInAnyOrder("<init>", "pay");
    }

    @Test
    void shouldFlagSupertypesOutsideTheAnalysis() {
        ClassDescription orderRepository = single(tool.describeClass("OrderRepository"));

        assertThat(orderRepository.supertypes())
                .extracting(TypeSummary::className, TypeSummary::relation, TypeSummary::inAnalysis)
                .containsExactly(tuple("org.springframework.data.repository.Repository", "EXTENDS", false));
    }

    @Test
    void shouldNeverListBridgeMethods() {
        ClassDescription orderStore = single(tool.describeClass("OrderStore"));

        // OrderStore also declares a bridge save(Object)
        assertThat(orderStore.methods())
                .extracting(MethodSummary::name, MethodSummary::parameterTypes)
                .containsExactlyInAnyOrder(
                        tuple("<init>", List.of()),
                        tuple("save", List.of(FIXTURE + "$Order")));
    }

    @Test
    void shouldKeepLambdaBodiesFlagged() {
        ClassDescription checkout = single(tool.describeClass("Checkout"));

        assertThat(checkout.methods())
                .extracting(MethodSummary::name, MethodSummary::isLambdaBody)
                .containsExactlyInAnyOrder(
                        tuple("<init>", false),
                        tuple("checkoutAll", false),
                        tuple("lambda$checkoutAll$0", true));
    }

    @Test
    void shouldDescribeEveryClassMatchingASimpleName() {
        assertThat(tool.describeClass("Order").classes())
                .extracting(ClassDescription::className)
                .containsExactlyInAnyOrder(FIXTURE + "$Order", FIXTURE + "$Legacy$Order");
        assertThat(tool.describeClass("Legacy.Order").classes())
                .extracting(ClassDescription::className)
                .containsExactly(FIXTURE + "$Legacy$Order");
        assertThat(tool.describeClass(FIXTURE + "$Order").classes())
                .extracting(ClassDescription::className)
                .containsExactly(FIXTURE + "$Order");
    }

    @ParameterizedTest
    // Not in the graph at all / a stub node, referenced as a supertype but never analyzed
    @ValueSource(strings = {"java.util.List", "org.springframework.data.repository.Repository"})
    void shouldExplainAClassOutsideTheAnalysis(String className) {
        DescribeClassResult result = tool.describeClass(className);

        assertThat(result.classes()).isEmpty();
        assertThat(result.note()).contains("No class of the analysis is named '" + className + "'");
    }

    @Test
    void shouldRejectBlankNames() {
        assertThatIllegalArgumentException().isThrownBy(() -> tool.describeClass(" "));
    }

    private static ClassDescription single(DescribeClassResult result) {
        assertThat(result.classes()).hasSize(1);
        return result.classes().getFirst();
    }
}
