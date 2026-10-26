package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.ClassKind;
import com.jgraph.analyzer.model.Invocation;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import com.jgraph.analyzer.model.Visibility;
import com.jgraph.analyzer.source.ArchiveClassFileSource;
import com.jgraph.analyzer.source.PackageFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.thirdparty.library.ThirdPartyClient;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BytecodeReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCorrectlyParseJarFile() throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("test.jar"),
                JarReaderTestFixture.class,
                JarReaderTestFixture.TestInterface.class,
                JarReaderTestFixture.TestImplementation.class);

        AnalysisResult result = TestJars.parse(jarPath);

        // Verify Classes
        assertThat(result.classes())
                .extracting(JavaClass::fqcn)
                .contains(
                        "com/jgraph/analyzer/parser/JarReaderTestFixture$TestInterface",
                        "com/jgraph/analyzer/parser/JarReaderTestFixture$TestImplementation"
                );

        JavaClass testInterface = result.classes().stream()
                .filter(c -> c.fqcn().endsWith("TestInterface"))
                .findFirst().orElseThrow();
        assertThat(testInterface.isInterface()).isTrue();

        JavaClass testImpl = result.classes().stream()
                .filter(c -> c.fqcn().endsWith("TestImplementation"))
                .findFirst().orElseThrow();
        assertThat(testImpl.isInterface()).isFalse();

        // Verify Methods and Transactional
        JavaMethod transactionalMethod = result.methods().stream()
                .filter(m -> m.name().equals("transactionalMethod"))
                .findFirst().orElseThrow();
        assertThat(transactionalMethod.isTransactional()).isTrue();

        // Verify Overload and Signature
        assertThat(result.methods())
                .filteredOn(m -> m.name().equals("overloadedMethod"))
                .hasSize(2)
                .extracting(JavaMethod::descriptor)
                .containsExactlyInAnyOrder("(I)V", "(Ljava/lang/String;)V");

        // Verify MethodCall [:CALLS]
        // transactionalMethod -> privateMethod
        assertThat(result.calls())
                .anyMatch(call -> 
                    call.callerSignature().contains("transactionalMethod") && 
                    call.targetSignature().contains("privateMethod")
                );

        // interfaceMethod -> System.out.println
        assertThat(result.calls())
                .anyMatch(call -> 
                    call.callerSignature().contains("interfaceMethod") && 
                    call.targetSignature().contains("java/io/PrintStream#println")
                );
    }

    @Test
    void shouldCaptureClassHierarchyAndMethodModifiers() throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("hierarchy.jar"), HierarchyFixture.CLASSES);

        AnalysisResult result = TestJars.parse(jarPath);

        // Class -> superclass
        JavaClass card = classOf(result, HierarchyFixture.CardPaymentService.class);
        assertThat(card.superClass()).isEqualTo(internalName(HierarchyFixture.AbstractPaymentService.class));
        assertThat(card.isAbstract()).isFalse();

        // Abstract class extending java/lang/Object (not modelled) and implementing an interface
        JavaClass base = classOf(result, HierarchyFixture.AbstractPaymentService.class);
        assertThat(base.isAbstract()).isTrue();
        assertThat(base.superClass()).isNull();
        assertThat(base.interfaces()).containsExactly(internalName(HierarchyFixture.AuditedPaymentService.class));

        // Interface -> superinterface
        JavaClass audited = classOf(result, HierarchyFixture.AuditedPaymentService.class);
        assertThat(audited.isInterface()).isTrue();
        assertThat(audited.superClass()).isNull();
        assertThat(audited.interfaces()).containsExactly(internalName(HierarchyFixture.PaymentService.class));

        // Method modifiers: visibility, static, abstract
        assertThat(methodOf(result, HierarchyFixture.PaymentService.class, "pay"))
                .extracting(JavaMethod::visibility, JavaMethod::isStatic, JavaMethod::isAbstract)
                .containsExactly(Visibility.PUBLIC, false, true);
        assertThat(methodOf(result, HierarchyFixture.CardPaymentService.class, "pay"))
                .extracting(JavaMethod::visibility, JavaMethod::isStatic, JavaMethod::isAbstract)
                .containsExactly(Visibility.PUBLIC, false, false);
        assertThat(methodOf(result, HierarchyFixture.AbstractPaymentService.class, "log"))
                .extracting(JavaMethod::visibility, JavaMethod::isStatic, JavaMethod::isAbstract)
                .containsExactly(Visibility.PROTECTED, false, false);
        assertThat(methodOf(result, HierarchyFixture.AbstractPaymentService.class, "secret"))
                .extracting(JavaMethod::visibility)
                .isEqualTo(Visibility.PRIVATE);
        assertThat(methodOf(result, HierarchyFixture.AbstractPaymentService.class, "utility"))
                .extracting(JavaMethod::visibility, JavaMethod::isStatic)
                .containsExactly(Visibility.PACKAGE_PRIVATE, true);
    }

    @Test
    void shouldClassifyKindsAndDropImplicitSupertypes() throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("kinds.jar"), TypeKindFixture.CLASSES);

        AnalysisResult result = TestJars.parse(jarPath);

        assertThat(classOf(result, TypeKindFixture.PlainClass.class).kind()).isEqualTo(ClassKind.CLASS);
        assertThat(classOf(result, TypeKindFixture.Marker.class).kind()).isEqualTo(ClassKind.INTERFACE);
        assertThat(classOf(result, TypeKindFixture.Status.class).kind()).isEqualTo(ClassKind.ENUM);
        assertThat(classOf(result, TypeKindFixture.Point.class).kind()).isEqualTo(ClassKind.RECORD);
        assertThat(classOf(result, TypeKindFixture.Audited.class).kind()).isEqualTo(ClassKind.ANNOTATION);
        assertThat(classOf(result, TypeKindFixture.Audited.class).isInterface()).isTrue();

        // java/lang/Object, Enum, Record and annotation/Annotation are never modelled as supertypes
        assertThat(result.classes())
                .allSatisfy(c -> {
                    assertThat(c.superClass()).isNull();
                    assertThat(c.interfaces()).isEmpty();
                });
    }

    @Test
    void shouldFlagBridgeMethods() throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("bridges.jar"), BridgeFixture.CLASSES);

        AnalysisResult result = TestJars.parse(jarPath);

        String order = "L" + internalName(BridgeFixture.Order.class) + ";";
        String invoice = "L" + internalName(BridgeFixture.Invoice.class) + ";";
        String entity = "L" + internalName(BridgeFixture.Entity.class) + ";";
        String animal = "L" + internalName(BridgeFixture.Animal.class) + ";";
        String dog = "L" + internalName(BridgeFixture.Dog.class) + ";";

        // The method written by the developer and the bridge javac generated next to it
        assertThat(methodOf(result, BridgeFixture.OrderRepository.class, "save", "(" + order + ")V").isBridge())
                .isFalse();
        assertThat(methodOf(result, BridgeFixture.OrderRepository.class, "save", "(Ljava/lang/Object;)V").isBridge())
                .isTrue();
        assertThat(methodOf(result, BridgeFixture.InvoiceService.class, "persist", "(" + invoice + ")V").isBridge())
                .isFalse();
        assertThat(methodOf(result, BridgeFixture.InvoiceService.class, "persist", "(" + entity + ")V").isBridge())
                .isTrue();
        assertThat(methodOf(result, BridgeFixture.Dog.class, "copy", "()" + dog).isBridge()).isFalse();
        assertThat(methodOf(result, BridgeFixture.Dog.class, "copy", "()" + animal).isBridge()).isTrue();
        assertThat(methodOf(result, BridgeFixture.PublicFacade.class, "greet", "()V").isBridge()).isTrue();

        assertThat(result.methods()).filteredOn(JavaMethod::isBridge).hasSize(4);
    }

    @Test
    void shouldRecordInvocationAndReceiverAsWrittenInBytecode() throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("calls.jar"), CallFixture.CLASSES);

        AnalysisResult result = TestJars.parse(jarPath);

        String api = internalName(CallFixture.PaymentService.class);
        String base = internalName(CallFixture.AbstractPaymentService.class);
        String card = internalName(CallFixture.CardPaymentService.class);
        String paypal = internalName(CallFixture.PaypalPaymentService.class);
        String checkout = internalName(CallFixture.Checkout.class) + "#checkout(L" + card + ";L" + base + ";L" + api
                + ";Ljava/lang/String;)V";
        String id = "(Ljava/lang/String;)V";

        // Parsed, not yet resolved: targets name the receiver's static type, even when it does not declare the method
        assertThat(result.calls()).contains(
                new MethodCall(checkout, card + "#refund" + id, Invocation.VIRTUAL, card),
                new MethodCall(checkout, base + "#pay" + id, Invocation.VIRTUAL, base),
                new MethodCall(checkout, api + "#pay" + id, Invocation.INTERFACE, api),
                new MethodCall(checkout, card + "#currency()Ljava/lang/String;", Invocation.STATIC, card),
                new MethodCall(paypal + "#refund" + id, base + "#refund" + id, Invocation.SPECIAL, base));
    }

    private static JavaMethod methodOf(AnalysisResult result, Class<?> owner, String name, String descriptor) {
        return result.methods().stream()
                .filter(m -> m.ownerClassFqcn().equals(internalName(owner))
                        && m.name().equals(name)
                        && m.descriptor().equals(descriptor))
                .findFirst().orElseThrow();
    }

    private static JavaClass classOf(AnalysisResult result, Class<?> clazz) {
        return result.classes().stream()
                .filter(c -> c.fqcn().equals(internalName(clazz)))
                .findFirst().orElseThrow();
    }

    // --- Archives, package filter and duplicates (ADR-0012) ---

    @Test
    void shouldAnalyzeTheClassesOfAFatJarAndOfItsNestedJars() throws IOException {
        Path fatJar = fatJar("app.jar");

        ReadResult read = new BytecodeReader(PackageFilter.everything()).read(new ArchiveClassFileSource(fatJar));

        assertThat(read.model().classes()).extracting(JavaClass::fqcn).containsExactly(
                internalName(ArchiveFixture.OrderService.class),
                internalName(ArchiveFixture.PaymentGateway.class),
                internalName(ThirdPartyClient.class));
        assertThat(read.classFilesRead()).isEqualTo(3);
        assertThat(read.excludedByFilter()).isZero();
        assertThat(read.duplicatesSkipped()).isZero();
    }

    @Test
    void shouldLeaveClassesOutsideThePackageFilterOutOfTheAnalysis() throws IOException {
        Path fatJar = fatJar("app.jar");

        ReadResult read = new BytecodeReader(PackageFilter.parse("com.jgraph.*"))
                .read(new ArchiveClassFileSource(fatJar));

        // The internal library in a nested jar is in, the third-party one is out
        assertThat(read.model().classes()).extracting(JavaClass::fqcn).containsExactly(
                internalName(ArchiveFixture.OrderService.class),
                internalName(ArchiveFixture.PaymentGateway.class));
        assertThat(read.model().methods()).extracting(JavaMethod::ownerClassFqcn)
                .doesNotContain(internalName(ThirdPartyClient.class));
        assertThat(read.excludedByFilter()).isEqualTo(1);
        // The call to the library is still there: its target becomes a stub node, like a JDK method
        assertThat(read.model().calls()).extracting(MethodCall::targetSignature)
                .contains(internalName(ThirdPartyClient.class) + "#fetch(Ljava/lang/String;)Ljava/lang/String;");
    }

    @Test
    void shouldKeepTheFirstOfTwoClassesWithTheSameName() throws IOException {
        String gateway = internalName(ArchiveFixture.PaymentGateway.class);
        Path fatJar = TestJars.archive()
                .classesIn("BOOT-INF/classes/", ArchiveFixture.PaymentGateway.class)
                // Same name, other content: a version of the interface with refund instead of charge
                .nestedStored("BOOT-INF/lib/acme-core.jar", TestJars.archive()
                        .file(gateway + ".class", interfaceWithOneMethod(gateway, "refund")))
                .writeTo(tempDir.resolve("app.jar"));

        ReadResult read = new BytecodeReader(PackageFilter.everything()).read(new ArchiveClassFileSource(fatJar));

        assertThat(read.model().classes()).extracting(JavaClass::fqcn).containsExactly(gateway);
        // The copy analyzed is the first in the order of the archive: BOOT-INF/classes, before BOOT-INF/lib
        assertThat(read.model().methods()).extracting(JavaMethod::name).containsExactly("charge");
        assertThat(read.duplicatesSkipped()).isEqualTo(1);
    }

    @Test
    void shouldNameClassesFromTheirBytecodeNotFromTheirEntry() throws IOException {
        Path jar = TestJars.archive()
                .file("misplaced/Whatever.class", TestJars.bytecodeOf(ArchiveFixture.PaymentGateway.class))
                .writeTo(tempDir.resolve("app.jar"));

        AnalysisResult result = TestJars.parse(jar);

        assertThat(result.classes()).extracting(JavaClass::fqcn)
                .containsExactly(internalName(ArchiveFixture.PaymentGateway.class));
    }

    @Test
    void shouldReportAMalformedClassFileWithItsOrigin() throws IOException {
        Path jar = TestJars.archive()
                .nested("lib/broken.jar", TestJars.archive().file("com/acme/Broken.class", new byte[]{1, 2, 3}))
                .writeTo(tempDir.resolve("app.jar"));

        assertThatThrownBy(() -> TestJars.parse(jar))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("app.jar!/lib/broken.jar!/com/acme/Broken.class");
    }

    /** A class file written with ASM: a public interface with one abstract {@code void m(String)} method. */
    private static byte[] interfaceWithOneMethod(String internalName, String method) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, internalName, null,
                "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method, "(Ljava/lang/String;)V", null, null)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A Spring Boot fat jar: the application, an internal library and a third-party one. */
    private Path fatJar(String name) throws IOException {
        return TestJars.archive()
                .classesIn("BOOT-INF/classes/", ArchiveFixture.OrderService.class)
                .nestedStored("BOOT-INF/lib/acme-payments-core.jar", TestJars.archive()
                        .classes(ArchiveFixture.PaymentGateway.class))
                .nestedStored("BOOT-INF/lib/third-party.jar", TestJars.archive()
                        .classes(ThirdPartyClient.class))
                .writeTo(tempDir.resolve(name));
    }

    private static JavaMethod methodOf(AnalysisResult result, Class<?> owner, String name) {
        return result.methods().stream()
                .filter(m -> m.ownerClassFqcn().equals(internalName(owner)) && m.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static String internalName(Class<?> clazz) {
        return clazz.getName().replace('.', '/');
    }
}
