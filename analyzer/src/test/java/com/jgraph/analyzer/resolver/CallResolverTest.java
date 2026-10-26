package com.jgraph.analyzer.resolver;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.ClassKind;
import com.jgraph.analyzer.model.Invocation;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import com.jgraph.analyzer.model.Visibility;
import com.jgraph.analyzer.parser.CallFixture;
import com.jgraph.analyzer.parser.TestJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specification of {@link CallResolver} (ADR-0008): a call naming a type that does not declare the method is pointed
 * at the declaration the JVM would resolve, keeping invocation and receiver; anything uncertain is left as written.
 */
class CallResolverTest {

    private static final String CALLER = "com/acme/Client#run()V";

    private final CallResolver resolver = new CallResolver();

    // --- Superclass chain ---

    @Test
    void shouldPointCallToMethodDeclaredInSuperclass() {
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod baseFoo = method(base.fqcn(), "foo");

        // sub.foo(): invokevirtual Sub.foo, declared in Base
        MethodCall call = call("com/acme/Sub#foo()V", Invocation.VIRTUAL);

        assertThat(resolveCalls(List.of(base, sub), List.of(baseFoo), call))
                .containsExactly(new MethodCall(CALLER, baseFoo.signature(), Invocation.VIRTUAL, sub.fqcn()));
    }

    @Test
    void shouldPointCallToNearestSuperclassDeclaration() {
        JavaClass a = clazz("com/acme/A", null);
        JavaClass b = clazz("com/acme/B", a.fqcn());
        JavaClass c = clazz("com/acme/C", b.fqcn());
        JavaMethod aFoo = method(a.fqcn(), "foo");
        JavaMethod bFoo = method(b.fqcn(), "foo");

        assertThat(resolveCalls(List.of(a, b, c), List.of(aFoo, bFoo), call("com/acme/C#foo()V", Invocation.VIRTUAL)))
                .extracting(MethodCall::targetSignature)
                .containsExactly(bFoo.signature());
    }

    @Test
    void shouldResolveInheritedStaticMethod() {
        JavaClass helper = clazz("com/acme/Helper", null);
        JavaClass subHelper = clazz("com/acme/SubHelper", helper.fqcn());
        JavaMethod util = new JavaMethod(helper.fqcn(), "util", "()V", "void", List.of(),
                Visibility.PUBLIC, true, false, false, false);

        // SubHelper.util(): invokestatic SubHelper.util, declared in Helper
        assertThat(resolveCalls(List.of(helper, subHelper), List.of(util), call("com/acme/SubHelper#util()V", Invocation.STATIC)))
                .containsExactly(new MethodCall(CALLER, util.signature(), Invocation.STATIC, subHelper.fqcn()));
    }

    @Test
    void shouldPreferSuperclassDeclarationOverInterfaceDeclaration() {
        // JVM order: superclasses first, then superinterfaces
        JavaClass api = iface("com/acme/Api");
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn(), api.fqcn());
        JavaMethod apiFoo = method(api.fqcn(), "foo");
        JavaMethod baseFoo = method(base.fqcn(), "foo");

        assertThat(resolveCalls(List.of(api, base, sub), List.of(apiFoo, baseFoo), call("com/acme/Sub#foo()V", Invocation.VIRTUAL)))
                .extracting(MethodCall::targetSignature)
                .containsExactly(baseFoo.signature());
    }

    // --- Superinterfaces ---

    @Test
    void shouldResolveInterfaceMethodCalledThroughAbstractClass() {
        // abstractService.pay(): invokevirtual AbstractPaymentService.pay, declared only in PaymentService
        JavaClass paymentService = iface("com/acme/PaymentService");
        JavaClass base = abstractClazz("com/acme/AbstractPaymentService", null, paymentService.fqcn());
        JavaMethod pay = abstractMethod(paymentService.fqcn(), "pay");

        assertThat(resolveCalls(List.of(paymentService, base), List.of(pay),
                call("com/acme/AbstractPaymentService#pay()V", Invocation.VIRTUAL)))
                .containsExactly(new MethodCall(CALLER, pay.signature(), Invocation.VIRTUAL, base.fqcn()));
    }

    @Test
    void shouldResolveThroughSuperinterfacesOfInterfaceReceiver() {
        JavaClass api = iface("com/acme/Api");
        JavaClass subApi = iface("com/acme/SubApi", api.fqcn());
        JavaMethod apiFoo = abstractMethod(api.fqcn(), "foo");

        assertThat(resolveCalls(List.of(api, subApi), List.of(apiFoo), call("com/acme/SubApi#foo()V", Invocation.INTERFACE)))
                .containsExactly(new MethodCall(CALLER, apiFoo.signature(), Invocation.INTERFACE, subApi.fqcn()));
    }

    @Test
    void shouldResolveInterfaceDeclarationReachedThroughSuperclass() {
        // Sub extends Base (no foo), Base implements Api (declares foo)
        JavaClass api = iface("com/acme/Api");
        JavaClass base = abstractClazz("com/acme/Base", null, api.fqcn());
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod apiFoo = abstractMethod(api.fqcn(), "foo");

        assertThat(resolveCalls(List.of(api, base, sub), List.of(apiFoo), call("com/acme/Sub#foo()V", Invocation.VIRTUAL)))
                .extracting(MethodCall::targetSignature)
                .containsExactly(apiFoo.signature());
    }

    // --- Left as written: nothing is invented ---

    @Test
    void shouldLeaveCallUnresolvedWhenSuperclassChainLeavesTheAnalysis() {
        // Worker extends an external class, which may declare start(): the analysis cannot know
        JavaClass api = iface("com/acme/Api");
        JavaClass worker = clazz("com/acme/Worker", "java/lang/Thread", api.fqcn());
        JavaMethod apiStart = abstractMethod(api.fqcn(), "start");
        MethodCall call = call("com/acme/Worker#start()V", Invocation.VIRTUAL);

        assertThat(resolveCalls(List.of(api, worker), List.of(apiStart), call)).containsExactly(call);
    }

    @Test
    void shouldLeaveCallUnresolvedWhenDeclaredByImplicitSupertypeOnly() {
        // status.name(): declared in java/lang/Enum, never modelled
        JavaClass status = new JavaClass("com/acme/Status", "Status", ClassKind.ENUM, false, null, List.of());
        MethodCall call = call("com/acme/Status#name()Ljava/lang/String;", Invocation.VIRTUAL);

        assertThat(resolveCalls(List.of(status), List.of(), call)).containsExactly(call);
    }

    @Test
    void shouldLeaveAmbiguousInterfaceCallUnresolved() {
        // Two unrelated interfaces declare foo(): which one the JVM picks is unspecified
        JavaClass first = iface("com/acme/First");
        JavaClass second = iface("com/acme/Second");
        JavaClass impl = abstractClazz("com/acme/Impl", null, first.fqcn(), second.fqcn());
        MethodCall call = call("com/acme/Impl#foo()V", Invocation.VIRTUAL);

        assertThat(resolveCalls(List.of(first, second, impl),
                List.of(abstractMethod(first.fqcn(), "foo"), abstractMethod(second.fqcn(), "foo")), call))
                .containsExactly(call);
    }

    @Test
    void shouldResolveDiamondToSingleDeclaration() {
        // Root reached through Left and Right: one declaration, not an ambiguity
        JavaClass root = iface("com/acme/Root");
        JavaClass left = iface("com/acme/Left", root.fqcn());
        JavaClass right = iface("com/acme/Right", root.fqcn());
        JavaClass impl = abstractClazz("com/acme/Impl", null, left.fqcn(), right.fqcn());
        JavaMethod rootFoo = abstractMethod(root.fqcn(), "foo");

        assertThat(resolveCalls(List.of(root, left, right, impl), List.of(rootFoo), call("com/acme/Impl#foo()V", Invocation.VIRTUAL)))
                .extracting(MethodCall::targetSignature)
                .containsExactly(rootFoo.signature());
    }

    @Test
    void shouldNotTouchCallsToDeclaredMethods() {
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod baseFoo = method(base.fqcn(), "foo");
        JavaMethod subFoo = method(sub.fqcn(), "foo");
        MethodCall call = call(subFoo.signature(), Invocation.VIRTUAL);

        assertThat(resolveCalls(List.of(base, sub), List.of(baseFoo, subFoo), call)).containsExactly(call);
    }

    @Test
    void shouldNotTouchCallsToTypesOutsideTheAnalysis() {
        MethodCall call = call("java/util/List#add(Ljava/lang/Object;)Z", Invocation.INTERFACE);

        assertThat(resolveCalls(List.of(), List.of(), call)).containsExactly(call);
    }

    @Test
    void shouldMatchOnDescriptorNotJustName() {
        // Base declares foo(int); the call is to foo(String): an overload Base does not declare
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod fooInt = new JavaMethod(base.fqcn(), "foo", "(I)V", "void", List.of("int"),
                Visibility.PUBLIC, false, false, false, false);
        MethodCall call = call("com/acme/Sub#foo(Ljava/lang/String;)V", Invocation.VIRTUAL);

        assertThat(resolveCalls(List.of(base, sub), List.of(fooInt), call)).containsExactly(call);
    }

    @Test
    void shouldKeepClassesMethodsAndOverridesUnchanged() {
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod baseFoo = method(base.fqcn(), "foo");
        AnalysisResult parsed = new AnalysisResult(List.of(base, sub), List.of(baseFoo),
                List.of(call("com/acme/Sub#foo()V", Invocation.VIRTUAL)));

        AnalysisResult resolved = resolver.resolve(parsed);

        assertThat(resolved.classes()).isEqualTo(parsed.classes());
        assertThat(resolved.methods()).isEqualTo(parsed.methods());
        assertThat(resolved.overrides()).isEqualTo(parsed.overrides());
    }

    // --- Real bytecode ---

    @Test
    void shouldResolveCallsFromRealBytecode(@TempDir Path tempDir) throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("calls.jar"), CallFixture.CLASSES);
        AnalysisResult parsed = TestJars.parse(jarPath);

        String api = internalName(CallFixture.PaymentService.class);
        String base = internalName(CallFixture.AbstractPaymentService.class);
        String card = internalName(CallFixture.CardPaymentService.class);
        String paypal = internalName(CallFixture.PaypalPaymentService.class);
        String status = internalName(CallFixture.Status.class);
        String checkout = internalName(CallFixture.Checkout.class) + "#checkout(L" + card + ";L" + base + ";L" + api
                + ";Ljava/lang/String;)V";
        String describe = internalName(CallFixture.Checkout.class) + "#describe(L" + status + ";)Ljava/lang/String;";
        String id = "(Ljava/lang/String;)V";

        List<MethodCall> calls = resolver.resolve(parsed).calls();

        assertThat(calls).contains(
                // card.refund(id): inherited from the abstract class
                new MethodCall(checkout, base + "#refund" + id, Invocation.VIRTUAL, card),
                // any.pay(id): declared only in the interface
                new MethodCall(checkout, api + "#pay" + id, Invocation.VIRTUAL, base),
                // api.pay(id): already declared where the bytecode says
                new MethodCall(checkout, api + "#pay" + id, Invocation.INTERFACE, api),
                // CardPaymentService.currency(): inherited static
                new MethodCall(checkout, base + "#currency()Ljava/lang/String;", Invocation.STATIC, card),
                // log(id) from CardPaymentService.pay: inherited helper
                new MethodCall(card + "#pay" + id, base + "#log" + id, Invocation.VIRTUAL, card),
                // super.refund(id): declared, no dispatch
                new MethodCall(paypal + "#refund" + id, base + "#refund" + id, Invocation.SPECIAL, base),
                // status.name(): declared in java/lang/Enum, left as written
                new MethodCall(describe, status + "#name()Ljava/lang/String;", Invocation.VIRTUAL, status));

        // No call is left on a method that a type of the analysis names but does not declare, except status.name()
        assertThat(calls)
                .filteredOn(c -> !c.targetSignature().startsWith("java/"))
                .filteredOn(c -> parsed.classes().stream().anyMatch(k -> c.targetSignature().startsWith(k.fqcn() + "#")))
                .filteredOn(c -> parsed.methods().stream().noneMatch(m -> m.signature().equals(c.targetSignature())))
                .extracting(MethodCall::targetSignature)
                .containsOnly(status + "#name()Ljava/lang/String;");
    }

    // --- Fixtures ---

    private List<MethodCall> resolveCalls(List<JavaClass> classes, List<JavaMethod> methods, MethodCall call) {
        return resolver.resolve(new AnalysisResult(classes, methods, List.of(call))).calls();
    }

    /**
     * A call from {@link #CALLER} as written in bytecode: the receiver is the owner named in the target signature.
     */
    private static MethodCall call(String targetSignature, Invocation invocation) {
        return new MethodCall(CALLER, targetSignature, invocation, TypeHierarchy.ownerOf(targetSignature));
    }

    private static JavaClass clazz(String fqcn, String superClass, String... interfaces) {
        return new JavaClass(fqcn, simpleName(fqcn), ClassKind.CLASS, false, superClass, List.of(interfaces));
    }

    private static JavaClass abstractClazz(String fqcn, String superClass, String... interfaces) {
        return new JavaClass(fqcn, simpleName(fqcn), ClassKind.CLASS, true, superClass, List.of(interfaces));
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

    private static String simpleName(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('/') + 1);
    }

    private static String internalName(Class<?> clazz) {
        return clazz.getName().replace('.', '/');
    }
}
