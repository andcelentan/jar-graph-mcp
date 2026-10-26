package com.jgraph.analyzer.resolver;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.ClassKind;
import com.jgraph.analyzer.model.Invocation;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import com.jgraph.analyzer.model.MethodOverride;
import com.jgraph.analyzer.model.Visibility;
import com.jgraph.analyzer.parser.BridgeFixture;
import com.jgraph.analyzer.parser.HierarchyFixture;
import com.jgraph.analyzer.parser.TestJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specification of {@link OverrideResolver} (ADR-0006). Each test builds a small hierarchy by hand and states
 * exactly which overrides must be found: an unexpected extra override fails the test as much as a missing one.
 */
class OverrideResolverTest {

    private final OverrideResolver resolver = new OverrideResolver();

    // --- Basic linking ---

    @Test
    void shouldLinkClassMethodToImplementedInterfaceMethod() {
        JavaClass api = iface("com/acme/Api");
        JavaClass impl = clazz("com/acme/Impl", null, api.fqcn());
        JavaMethod apiRun = method(api.fqcn(), "run");
        JavaMethod implRun = method(impl.fqcn(), "run");

        assertThat(resolveOverrides(List.of(api, impl), List.of(apiRun, implRun)))
                .containsExactly(override(implRun, apiRun));
    }

    @Test
    void shouldLinkSubclassMethodToSuperclassMethod() {
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod baseRun = method(base.fqcn(), "run");
        JavaMethod subRun = method(sub.fqcn(), "run");

        assertThat(resolveOverrides(List.of(base, sub), List.of(baseRun, subRun)))
                .containsExactly(override(subRun, baseRun));
    }

    @Test
    void shouldLinkInterfaceMethodsAlongInterfaceInheritance() {
        JavaClass api = iface("com/acme/Api");
        JavaClass subApi = iface("com/acme/SubApi", api.fqcn());
        JavaMethod apiRun = method(api.fqcn(), "run");
        JavaMethod subApiRun = method(subApi.fqcn(), "run");

        assertThat(resolveOverrides(List.of(api, subApi), List.of(apiRun, subApiRun)))
                .containsExactly(override(subApiRun, apiRun));
    }

    // --- Nearest declaration ---

    @Test
    void shouldLinkOnlyToNearestDeclaration() {
        JavaClass a = clazz("com/acme/A", null);
        JavaClass b = clazz("com/acme/B", a.fqcn());
        JavaClass c = clazz("com/acme/C", b.fqcn());
        JavaMethod aRun = method(a.fqcn(), "run");
        JavaMethod bRun = method(b.fqcn(), "run");
        JavaMethod cRun = method(c.fqcn(), "run");

        // C -> A is reachable transitively through B: it must not be stored
        assertThat(resolveOverrides(List.of(a, b, c), List.of(aRun, bRun, cRun)))
                .containsExactlyInAnyOrder(override(cRun, bRun), override(bRun, aRun));
    }

    @Test
    void shouldSkipSupertypesThatDoNotDeclareTheMethod() {
        JavaClass a = clazz("com/acme/A", null);
        JavaClass b = clazz("com/acme/B", a.fqcn());
        JavaClass c = clazz("com/acme/C", b.fqcn());
        JavaMethod aRun = method(a.fqcn(), "run");
        JavaMethod cRun = method(c.fqcn(), "run");

        assertThat(resolveOverrides(List.of(a, b, c), List.of(aRun, cRun)))
                .containsExactly(override(cRun, aRun));
    }

    @Test
    void shouldResolveInterfaceMethodImplementedInSubclassOfAbstractClass() {
        // The motivating case: the interface is implemented by the abstract base, the method by its subclass
        JavaClass paymentService = iface("com/acme/PaymentService");
        JavaClass base = abstractClazz("com/acme/AbstractPaymentService", null, paymentService.fqcn());
        JavaClass card = clazz("com/acme/CardPaymentService", base.fqcn());
        JavaMethod pay = method(paymentService.fqcn(), "pay");
        JavaMethod cardPay = method(card.fqcn(), "pay");

        assertThat(resolveOverrides(List.of(paymentService, base, card), List.of(pay, cardPay)))
                .containsExactly(override(cardPay, pay));
    }

    @Test
    void shouldResolveThroughSuperinterfaces() {
        JavaClass api = iface("com/acme/Api");
        JavaClass subApi = iface("com/acme/SubApi", api.fqcn());
        JavaClass impl = clazz("com/acme/Impl", null, subApi.fqcn());
        JavaMethod apiRun = method(api.fqcn(), "run");
        JavaMethod implRun = method(impl.fqcn(), "run");

        assertThat(resolveOverrides(List.of(api, subApi, impl), List.of(apiRun, implRun)))
                .containsExactly(override(implRun, apiRun));
    }

    // --- Multiple paths ---

    @Test
    void shouldLinkToEveryPathThatDeclaresTheMethod() {
        JavaClass first = iface("com/acme/First");
        JavaClass second = iface("com/acme/Second");
        JavaClass impl = clazz("com/acme/Impl", null, first.fqcn(), second.fqcn());
        JavaMethod firstRun = method(first.fqcn(), "run");
        JavaMethod secondRun = method(second.fqcn(), "run");
        JavaMethod implRun = method(impl.fqcn(), "run");

        assertThat(resolveOverrides(List.of(first, second, impl), List.of(firstRun, secondRun, implRun)))
                .containsExactlyInAnyOrder(override(implRun, firstRun), override(implRun, secondRun));
    }

    @Test
    void shouldNotDuplicateOverridesReachedThroughDiamond() {
        //       Root (declares run)
        //      /    \
        //   Left    Right
        //      \    /
        //       Impl (declares run)
        JavaClass root = iface("com/acme/Root");
        JavaClass left = iface("com/acme/Left", root.fqcn());
        JavaClass right = iface("com/acme/Right", root.fqcn());
        JavaClass impl = clazz("com/acme/Impl", null, left.fqcn(), right.fqcn());
        JavaMethod rootRun = method(root.fqcn(), "run");
        JavaMethod implRun = method(impl.fqcn(), "run");

        assertThat(resolveOverrides(List.of(root, left, right, impl), List.of(rootRun, implRun)))
                .containsExactly(override(implRun, rootRun));
    }

    // --- Matching rules ---

    @Test
    void shouldMatchOnDescriptorNotJustName() {
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod baseRun = method(base.fqcn(), "run", "(I)V", Visibility.PUBLIC, false);
        JavaMethod subRun = method(sub.fqcn(), "run", "(Ljava/lang/String;)V", Visibility.PUBLIC, false);

        // An overload, not an override
        assertThat(resolveOverrides(List.of(base, sub), List.of(baseRun, subRun))).isEmpty();
    }

    @Test
    void shouldMatchNamesCaseSensitively() {
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod baseRun = method(base.fqcn(), "run");
        JavaMethod subRun = method(sub.fqcn(), "Run");

        // Java identifiers are case-sensitive: run() and Run() are different methods
        assertThat(resolveOverrides(List.of(base, sub), List.of(baseRun, subRun))).isEmpty();
    }

    @Test
    void shouldNeverOverridePrivateMethods() {
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod baseRun = method(base.fqcn(), "run", "()V", Visibility.PRIVATE, false);
        JavaMethod subRun = method(sub.fqcn(), "run");

        assertThat(resolveOverrides(List.of(base, sub), List.of(baseRun, subRun))).isEmpty();
    }

    @Test
    void shouldNotLinkStaticMethods() {
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod baseUtility = method(base.fqcn(), "utility", "()V", Visibility.PUBLIC, true);
        JavaMethod subUtility = method(sub.fqcn(), "utility", "()V", Visibility.PUBLIC, true);

        // Hiding, not overriding
        assertThat(resolveOverrides(List.of(base, sub), List.of(baseUtility, subUtility))).isEmpty();
    }

    @Test
    void shouldNotLinkConstructorsOrStaticInitializers() {
        JavaClass base = clazz("com/acme/Base", null);
        JavaClass sub = clazz("com/acme/Sub", base.fqcn());
        JavaMethod baseInit = method(base.fqcn(), "<init>");
        JavaMethod subInit = method(sub.fqcn(), "<init>");
        JavaMethod baseClinit = method(base.fqcn(), "<clinit>", "()V", Visibility.PACKAGE_PRIVATE, true);
        JavaMethod subClinit = method(sub.fqcn(), "<clinit>", "()V", Visibility.PACKAGE_PRIVATE, true);

        assertThat(resolveOverrides(List.of(base, sub), List.of(baseInit, subInit, baseClinit, subClinit))).isEmpty();
    }

    @Test
    void shouldOverridePackagePrivateMethodsOnlyFromSamePackage() {
        JavaClass base = clazz("com/acme/core/Base", null);
        JavaClass samePackage = clazz("com/acme/core/SamePackage", base.fqcn());
        JavaClass otherPackage = clazz("com/acme/other/OtherPackage", base.fqcn());
        JavaMethod baseRun = method(base.fqcn(), "run", "()V", Visibility.PACKAGE_PRIVATE, false);
        JavaMethod samePackageRun = method(samePackage.fqcn(), "run", "()V", Visibility.PACKAGE_PRIVATE, false);
        JavaMethod otherPackageRun = method(otherPackage.fqcn(), "run", "()V", Visibility.PACKAGE_PRIVATE, false);

        assertThat(resolveOverrides(
                List.of(base, samePackage, otherPackage),
                List.of(baseRun, samePackageRun, otherPackageRun)))
                .containsExactly(override(samePackageRun, baseRun));
    }

    @Test
    void shouldSkipIntermediatePackagePrivateDeclarationFromAnotherPackage() {
        // p1.A.run (package-private) <- p2.B.run (package-private) <- p1.C.run
        // C cannot override B.run (other package) but does override A.run (same package): the walk must go past B
        JavaClass a = clazz("com/acme/p1/A", null);
        JavaClass b = clazz("com/acme/p2/B", a.fqcn());
        JavaClass c = clazz("com/acme/p1/C", b.fqcn());
        JavaMethod aRun = method(a.fqcn(), "run", "()V", Visibility.PACKAGE_PRIVATE, false);
        JavaMethod bRun = method(b.fqcn(), "run", "()V", Visibility.PACKAGE_PRIVATE, false);
        JavaMethod cRun = method(c.fqcn(), "run", "()V", Visibility.PACKAGE_PRIVATE, false);

        assertThat(resolveOverrides(List.of(a, b, c), List.of(aRun, bRun, cRun)))
                .containsExactly(override(cRun, aRun));
    }

    @Test
    void shouldReachPackagePrivateDeclarationTransitivelyThroughPublicRedeclaration() {
        // JVMS §5.4.5: p1.A.run (package-private) <- p1.B.run (public) <- p2.C.run
        // C overrides A.run only through B: stored as C -> B and B -> A
        JavaClass a = clazz("com/acme/p1/A", null);
        JavaClass b = clazz("com/acme/p1/B", a.fqcn());
        JavaClass c = clazz("com/acme/p2/C", b.fqcn());
        JavaMethod aRun = method(a.fqcn(), "run", "()V", Visibility.PACKAGE_PRIVATE, false);
        JavaMethod bRun = method(b.fqcn(), "run");
        JavaMethod cRun = method(c.fqcn(), "run");

        assertThat(resolveOverrides(List.of(a, b, c), List.of(aRun, bRun, cRun)))
                .containsExactlyInAnyOrder(override(cRun, bRun), override(bRun, aRun));
    }

    @Test
    void shouldOverrideProtectedMethodsFromAnyPackage() {
        JavaClass base = clazz("com/acme/core/Base", null);
        JavaClass sub = clazz("com/acme/other/Sub", base.fqcn());
        JavaMethod baseRun = method(base.fqcn(), "run", "()V", Visibility.PROTECTED, false);
        JavaMethod subRun = method(sub.fqcn(), "run", "()V", Visibility.PROTECTED, false);

        assertThat(resolveOverrides(List.of(base, sub), List.of(baseRun, subRun)))
                .containsExactly(override(subRun, baseRun));
    }

    // --- Scope ---

    @Test
    void shouldIgnoreSupertypesOutsideTheArtifact() {
        // Neither the superclass nor the interface belongs to the analyzed artifact: their methods are unknown
        JavaClass task = clazz("com/acme/Task", "com/lib/ExternalBase", "java/lang/Runnable");
        JavaMethod taskRun = method(task.fqcn(), "run");

        assertThat(resolveOverrides(List.of(task), List.of(taskRun))).isEmpty();
    }

    @Test
    void shouldKeepParsedModelUnchanged() {
        JavaClass api = iface("com/acme/Api");
        JavaClass impl = clazz("com/acme/Impl", null, api.fqcn());
        JavaMethod apiRun = method(api.fqcn(), "run");
        JavaMethod implRun = method(impl.fqcn(), "run");
        AnalysisResult parsed = new AnalysisResult(
                List.of(api, impl),
                List.of(apiRun, implRun),
                List.of(new MethodCall(implRun.signature(), apiRun.signature(), Invocation.INTERFACE, api.fqcn())));

        AnalysisResult resolved = resolver.resolve(parsed);

        assertThat(resolved.classes()).isEqualTo(parsed.classes());
        assertThat(resolved.methods()).isEqualTo(parsed.methods());
        assertThat(resolved.calls()).isEqualTo(parsed.calls());
    }

    // --- Bridge methods (ADR-0007) ---

    @Test
    void shouldAttributeGenericBridgeOverrideToBridgedMethod() {
        // Repository<T>.save(T) erased to save(Object); OrderRepository.save(Order) gets a bridge save(Object)
        JavaClass repository = iface("com/acme/Repository");
        JavaClass orderRepository = clazz("com/acme/OrderRepository", null, repository.fqcn());
        JavaMethod save = method(repository.fqcn(), "save", "(Ljava/lang/Object;)V", Visibility.PUBLIC, false);
        JavaMethod saveOrder = method(orderRepository.fqcn(), "save", "(Lcom/acme/Order;)V", Visibility.PUBLIC, false);
        JavaMethod bridge = bridge(orderRepository.fqcn(), "save", "(Ljava/lang/Object;)V");

        assertThat(resolveOverrides(
                List.of(repository, orderRepository),
                List.of(save, saveOrder, bridge),
                List.of(call(bridge, saveOrder))))
                .containsExactly(override(saveOrder, save));
    }

    @Test
    void shouldAttributeCovariantReturnBridgeOverrideToBridgedMethod() {
        // Dog.copy() returns Dog while Animal.copy() returns Animal: the bridge keeps Animal's descriptor
        JavaClass animal = clazz("com/acme/Animal", null);
        JavaClass dog = clazz("com/acme/Dog", animal.fqcn());
        JavaMethod animalCopy = method(animal.fqcn(), "copy", "()Lcom/acme/Animal;", Visibility.PUBLIC, false);
        JavaMethod dogCopy = method(dog.fqcn(), "copy", "()Lcom/acme/Dog;", Visibility.PUBLIC, false);
        JavaMethod bridge = bridge(dog.fqcn(), "copy", "()Lcom/acme/Animal;");

        assertThat(resolveOverrides(
                List.of(animal, dog),
                List.of(animalCopy, dogCopy, bridge),
                List.of(call(bridge, dogCopy))))
                .containsExactly(override(dogCopy, animalCopy));
    }

    @Test
    void shouldKeepDirectOverridesOfBridgedMethod() {
        // save(Order) overrides OrderStore.save(Order) on its own, and Repository.save(Object) through the bridge
        JavaClass repository = iface("com/acme/Repository");
        JavaClass orderStore = iface("com/acme/OrderStore");
        JavaClass orderRepository = clazz("com/acme/OrderRepository", null, repository.fqcn(), orderStore.fqcn());
        JavaMethod save = method(repository.fqcn(), "save", "(Ljava/lang/Object;)V", Visibility.PUBLIC, false);
        JavaMethod storeSave = method(orderStore.fqcn(), "save", "(Lcom/acme/Order;)V", Visibility.PUBLIC, false);
        JavaMethod saveOrder = method(orderRepository.fqcn(), "save", "(Lcom/acme/Order;)V", Visibility.PUBLIC, false);
        JavaMethod bridge = bridge(orderRepository.fqcn(), "save", "(Ljava/lang/Object;)V");

        assertThat(resolveOverrides(
                List.of(repository, orderStore, orderRepository),
                List.of(save, storeSave, saveOrder, bridge),
                List.of(call(bridge, saveOrder))))
                .containsExactlyInAnyOrder(override(saveOrder, save), override(saveOrder, storeSave));
    }

    @Test
    void shouldDropSelfOverrideOfVisibilityBridge() {
        // PublicFacade (public) inherits greet() from HiddenBase (package-private): the bridge forwards to the very
        // method it overrides, so attributing the override would give HiddenBase.greet -> HiddenBase.greet
        JavaClass hiddenBase = clazz("com/acme/HiddenBase", null);
        JavaClass facade = clazz("com/acme/PublicFacade", hiddenBase.fqcn());
        JavaMethod greet = method(hiddenBase.fqcn(), "greet");
        JavaMethod bridge = bridge(facade.fqcn(), "greet", "()V");

        assertThat(resolveOverrides(
                List.of(hiddenBase, facade),
                List.of(greet, bridge),
                List.of(call(bridge, greet))))
                .isEmpty();
    }

    @Test
    void shouldTreatBridgeWithoutSameNameCallAsRegularMethod() {
        // Unexpected body (e.g. another compiler): no bridged method can be identified, so nothing is invented
        JavaClass repository = iface("com/acme/Repository");
        JavaClass orderRepository = clazz("com/acme/OrderRepository", null, repository.fqcn());
        JavaMethod save = method(repository.fqcn(), "save", "(Ljava/lang/Object;)V", Visibility.PUBLIC, false);
        JavaMethod log = method(orderRepository.fqcn(), "log");
        JavaMethod bridge = bridge(orderRepository.fqcn(), "save", "(Ljava/lang/Object;)V");

        assertThat(resolveOverrides(
                List.of(repository, orderRepository),
                List.of(save, log, bridge),
                List.of(call(bridge, log))))
                .containsExactly(override(bridge, save));
    }

    @Test
    void shouldTreatBridgeWithSeveralSameNameCallsAsRegularMethod() {
        // Two candidate targets: the bridged method is ambiguous, so nothing is invented
        JavaClass repository = iface("com/acme/Repository");
        JavaClass orderRepository = clazz("com/acme/OrderRepository", null, repository.fqcn());
        JavaMethod save = method(repository.fqcn(), "save", "(Ljava/lang/Object;)V", Visibility.PUBLIC, false);
        JavaMethod saveOrder = method(orderRepository.fqcn(), "save", "(Lcom/acme/Order;)V", Visibility.PUBLIC, false);
        JavaMethod saveDraft = method(orderRepository.fqcn(), "save", "(Lcom/acme/Draft;)V", Visibility.PUBLIC, false);
        JavaMethod bridge = bridge(orderRepository.fqcn(), "save", "(Ljava/lang/Object;)V");

        assertThat(resolveOverrides(
                List.of(repository, orderRepository),
                List.of(save, saveOrder, saveDraft, bridge),
                List.of(call(bridge, saveOrder), call(bridge, saveDraft))))
                .containsExactly(override(bridge, save));
    }

    // --- Real bytecode ---

    @Test
    void shouldAttributeBridgeOverridesFromRealBytecode(@TempDir Path tempDir) throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("bridges.jar"), BridgeFixture.CLASSES);
        AnalysisResult parsed = TestJars.parse(jarPath);

        String repository = internalName(BridgeFixture.Repository.class);
        String orderRepository = internalName(BridgeFixture.OrderRepository.class);
        String crudService = internalName(BridgeFixture.AbstractCrudService.class);
        String invoiceService = internalName(BridgeFixture.InvoiceService.class);
        String animal = internalName(BridgeFixture.Animal.class);
        String dog = internalName(BridgeFixture.Dog.class);
        String order = "L" + internalName(BridgeFixture.Order.class) + ";";
        String invoice = "L" + internalName(BridgeFixture.Invoice.class) + ";";
        String entity = "L" + internalName(BridgeFixture.Entity.class) + ";";

        // Every override starts from the method the developer wrote, never from a bridge;
        // the visibility bridge PublicFacade.greet() produces no override at all
        assertThat(resolver.resolve(parsed).overrides()).containsExactlyInAnyOrder(
                new MethodOverride(orderRepository + "#save(" + order + ")V", repository + "#save(Ljava/lang/Object;)V"),
                new MethodOverride(invoiceService + "#persist(" + invoice + ")V", crudService + "#persist(" + entity + ")V"),
                new MethodOverride(dog + "#copy()L" + dog + ";", animal + "#copy()L" + animal + ";"));
    }

    @Test
    void shouldResolveOverridesFromRealBytecode(@TempDir Path tempDir) throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("hierarchy.jar"), HierarchyFixture.CLASSES);
        AnalysisResult parsed = TestJars.parse(jarPath);

        String paymentService = internalName(HierarchyFixture.PaymentService.class);
        String audited = internalName(HierarchyFixture.AuditedPaymentService.class);
        String base = internalName(HierarchyFixture.AbstractPaymentService.class);
        String card = internalName(HierarchyFixture.CardPaymentService.class);

        // Not expected: private secret(), static utility(), constructors
        assertThat(resolver.resolve(parsed).overrides()).containsExactlyInAnyOrder(
                new MethodOverride(card + "#pay(Ljava/lang/String;)V", paymentService + "#pay(Ljava/lang/String;)V"),
                new MethodOverride(card + "#log(Ljava/lang/String;)V", base + "#log(Ljava/lang/String;)V"),
                new MethodOverride(base + "#refund(Ljava/lang/String;)V", paymentService + "#refund(Ljava/lang/String;)V"),
                new MethodOverride(base + "#audit(Ljava/lang/String;)V", audited + "#audit(Ljava/lang/String;)V"));
    }

    // --- Fixtures ---

    private List<MethodOverride> resolveOverrides(List<JavaClass> classes, List<JavaMethod> methods) {
        return resolveOverrides(classes, methods, List.of());
    }

    private List<MethodOverride> resolveOverrides(List<JavaClass> classes, List<JavaMethod> methods,
                                                  List<MethodCall> calls) {
        return resolver.resolve(new AnalysisResult(classes, methods, calls)).overrides();
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

    /**
     * Public, non-static, no-arg method.
     */
    private static JavaMethod method(String ownerFqcn, String name) {
        return method(ownerFqcn, name, "()V", Visibility.PUBLIC, false);
    }

    private static JavaMethod method(String ownerFqcn, String name, String descriptor,
                                     Visibility visibility, boolean isStatic) {
        return new JavaMethod(ownerFqcn, name, descriptor, "void", List.of(), visibility, isStatic, false, false, false);
    }

    /**
     * Public bridge method, as generated by javac.
     */
    private static JavaMethod bridge(String ownerFqcn, String name, String descriptor) {
        return new JavaMethod(ownerFqcn, name, descriptor, "void", List.of(), Visibility.PUBLIC, false, false, true, false);
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
}
