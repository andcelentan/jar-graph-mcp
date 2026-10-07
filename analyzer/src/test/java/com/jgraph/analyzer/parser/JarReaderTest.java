package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class JarReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCorrectlyParseJarFile() throws IOException {
        Path jarPath = tempDir.resolve("test.jar");
        createTestJar(jarPath);

        AnalysisResult result;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarReader reader = new JarReader(jarFile);
            result = reader.readAndBuildModel();
        }

        // Verifica Classi
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

        // Verifica Metodi e Transactional
        JavaMethod transactionalMethod = result.methods().stream()
                .filter(m -> m.name().equals("transactionalMethod"))
                .findFirst().orElseThrow();
        assertThat(transactionalMethod.isTransactional()).isTrue();

        // Verifica Overload e Signature
        assertThat(result.methods())
                .filteredOn(m -> m.name().equals("overloadedMethod"))
                .hasSize(2)
                .extracting(JavaMethod::descriptor)
                .containsExactlyInAnyOrder("(I)V", "(Ljava/lang/String;)V");

        // Verifica MethodCall [:CALLS]
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

    private void createTestJar(Path jarPath) throws IOException {
        Class<?>[] classesToInclude = {
                JarReaderTestFixture.class,
                JarReaderTestFixture.TestInterface.class,
                JarReaderTestFixture.TestImplementation.class
        };

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            for (Class<?> clazz : classesToInclude) {
                String classFilePath = clazz.getName().replace('.', '/') + ".class";
                try (InputStream is = clazz.getClassLoader().getResourceAsStream(classFilePath)) {
                    if (is == null) {
                        throw new IOException("Could not find class file for " + clazz.getName());
                    }
                    jos.putNextEntry(new JarEntry(classFilePath));
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        jos.write(buffer, 0, bytesRead);
                    }
                    jos.closeEntry();
                }
            }
        }
    }
}
