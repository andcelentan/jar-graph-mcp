package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.ClassKind;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.Visibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

class JarReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCorrectlyParseJarFile() throws IOException {
        Path jarPath = TestJars.createJar(tempDir.resolve("test.jar"),
                JarReaderTestFixture.class,
                JarReaderTestFixture.TestInterface.class,
                JarReaderTestFixture.TestImplementation.class);

        AnalysisResult result;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarReader reader = new JarReader(jarFile);
            result = reader.readAndBuildModel();
        }

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

        AnalysisResult result;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            result = new JarReader(jarFile).readAndBuildModel();
        }

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

        AnalysisResult result;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            result = new JarReader(jarFile).readAndBuildModel();
        }

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

        AnalysisResult result;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            result = new JarReader(jarFile).readAndBuildModel();
        }

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

    private static JavaMethod methodOf(AnalysisResult result, Class<?> owner, String name) {
        return result.methods().stream()
                .filter(m -> m.ownerClassFqcn().equals(internalName(owner)) && m.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static String internalName(Class<?> clazz) {
        return clazz.getName().replace('.', '/');
    }
}
