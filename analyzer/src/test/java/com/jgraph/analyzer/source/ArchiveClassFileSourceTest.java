package com.jgraph.analyzer.source;

import com.jgraph.analyzer.parser.TestJars;
import com.jgraph.analyzer.parser.TestJars.Archive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ArchiveClassFileSource} finds class files and hands them over: it never reads bytecode, so the class files of
 * these tests are fake bytes, named after where they are.
 */
class ArchiveClassFileSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldHandOverEveryClassFileOfAPlainJar() throws IOException {
        Path jar = TestJars.archive()
                .file("com/acme/Foo.class", bytes("Foo"))
                .file("com/acme/orders/Order.class", bytes("Order"))
                .writeTo(tempDir.resolve("plain.jar"));

        List<ClassFile> classFiles = read(jar);

        assertThat(classFiles).extracting(ClassFile::origin).containsExactly(
                "plain.jar!/com/acme/Foo.class",
                "plain.jar!/com/acme/orders/Order.class");
        assertThat(new String(classFiles.getFirst().bytes(), StandardCharsets.UTF_8)).isEqualTo("Foo");
    }

    @Test
    void shouldReadTheClassesAndTheNestedJarsOfASpringBootFatJar() throws IOException {
        Path fatJar = TestJars.archive()
                .file("META-INF/MANIFEST.MF", bytes("Main-Class: org.springframework.boot.loader.launch.JarLauncher"))
                .file("org/springframework/boot/loader/launch/JarLauncher.class", bytes("launcher"))
                .file("BOOT-INF/classes/com/acme/App.class", bytes("App"))
                .file("BOOT-INF/classes/application.yml", bytes("server: {}"))
                // Spring Boot stores nested jars uncompressed
                .nestedStored("BOOT-INF/lib/acme-core.jar", TestJars.archive()
                        .file("com/acme/core/Gateway.class", bytes("Gateway")))
                .nested("BOOT-INF/lib/library.jar", TestJars.archive()
                        .file("org/library/Client.class", bytes("Client")))
                .writeTo(tempDir.resolve("app.jar"));

        List<ClassFile> classFiles = read(fatJar);

        assertThat(classFiles).extracting(ClassFile::origin).containsExactly(
                "app.jar!/org/springframework/boot/loader/launch/JarLauncher.class",
                "app.jar!/BOOT-INF/classes/com/acme/App.class",
                "app.jar!/BOOT-INF/lib/acme-core.jar!/com/acme/core/Gateway.class",
                "app.jar!/BOOT-INF/lib/library.jar!/org/library/Client.class");
        // The bytes of the nested entry, read through both archives
        assertThat(new String(classFiles.get(2).bytes(), StandardCharsets.UTF_8)).isEqualTo("Gateway");
    }

    @Test
    void shouldReadAnEarHoldingAWarHoldingJars() throws IOException {
        Path ear = TestJars.archive()
                .nested("orders-web.war", TestJars.archive()
                        .file("WEB-INF/classes/com/acme/web/OrderController.class", bytes("controller"))
                        .nested("WEB-INF/lib/acme-core.jar", TestJars.archive()
                                .file("com/acme/core/Gateway.class", bytes("Gateway"))))
                .nested("lib/acme-common.jar", TestJars.archive()
                        .file("com/acme/common/Money.class", bytes("Money")))
                .writeTo(tempDir.resolve("app.ear"));

        assertThat(read(ear)).extracting(ClassFile::origin).containsExactly(
                "app.ear!/orders-web.war!/WEB-INF/classes/com/acme/web/OrderController.class",
                "app.ear!/orders-web.war!/WEB-INF/lib/acme-core.jar!/com/acme/core/Gateway.class",
                "app.ear!/lib/acme-common.jar!/com/acme/common/Money.class");
    }

    @Test
    void shouldSkipMultiReleaseCopiesAndModuleAndPackageDescriptors() throws IOException {
        Path jar = TestJars.archive()
                .file("module-info.class", bytes("module"))
                .file("com/acme/package-info.class", bytes("package"))
                .file("com/acme/Foo.class", bytes("Foo, base version"))
                .nested("lib/multi-release.jar", TestJars.archive()
                        .file("META-INF/versions/21/com/acme/Foo.class", bytes("Foo, Java 21 version"))
                        .file("META-INF/versions/9/module-info.class", bytes("module")))
                .writeTo(tempDir.resolve("app.jar"));

        assertThat(read(jar)).extracting(ClassFile::origin).containsExactly("app.jar!/com/acme/Foo.class");
    }

    @Test
    void shouldGiveTheSameOrderEveryTime() throws IOException {
        Path jar = TestJars.archive()
                .file("z/Last.class", bytes("z"))
                .nested("lib/middle.jar", TestJars.archive().file("m/Middle.class", bytes("m")))
                .file("a/First.class", bytes("a"))
                .writeTo(tempDir.resolve("app.jar"));

        List<String> once = read(jar).stream().map(ClassFile::origin).toList();
        List<String> twice = read(jar).stream().map(ClassFile::origin).toList();

        // The order of the archive, not alphabetical: "the first one wins" for duplicates relies on it
        assertThat(once).containsExactly(
                "app.jar!/z/Last.class", "app.jar!/lib/middle.jar!/m/Middle.class", "app.jar!/a/First.class");
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void shouldRefuseArchivesNestedTooDeep() throws IOException {
        Archive innermost = TestJars.archive().file("deep/Deep.class", bytes("deep"));
        Archive archive = innermost;
        for (int level = 0; level <= ArchiveClassFileSource.MAX_NESTING; level++) {
            archive = TestJars.archive().nested("level" + level + ".jar", archive);
        }
        Path jar = archive.writeTo(tempDir.resolve("deep.jar"));

        assertThatThrownBy(() -> read(jar))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nested more than " + ArchiveClassFileSource.MAX_NESTING + " levels deep");
    }

    @Test
    void shouldAcceptArchivesNestedUpToTheLimit() throws IOException {
        Archive archive = TestJars.archive().file("deep/Deep.class", bytes("deep"));
        for (int level = 1; level < ArchiveClassFileSource.MAX_NESTING; level++) {
            archive = TestJars.archive().nested("level" + level + ".jar", archive);
        }
        Path jar = TestJars.archive().nested("level0.jar", archive).writeTo(tempDir.resolve("deep.jar"));

        assertThat(read(jar)).hasSize(1);
    }

    @Test
    void shouldRefuseAFileThatIsNotAnArchive() throws IOException {
        Path notAJar = Files.writeString(tempDir.resolve("notes.jar"), "not a zip");

        assertThatThrownBy(() -> read(notAJar))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a jar, war or ear archive");
    }

    private static List<ClassFile> read(Path archive) throws IOException {
        List<ClassFile> classFiles = new ArrayList<>();
        new ArchiveClassFileSource(archive).forEach(classFiles::add);
        return classFiles;
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
