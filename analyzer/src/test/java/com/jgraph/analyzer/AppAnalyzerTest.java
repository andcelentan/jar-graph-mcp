package com.jgraph.analyzer;

import com.jgraph.analyzer.parser.ArchiveFixture;
import com.jgraph.analyzer.parser.TestJars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thirdparty.library.ThirdPartyClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command line, on the paths that never reach Neo4j: they must fail with a non-zero exit code before touching the
 * graph, so a CI job notices. Assumes {@code ANALYZER_INCLUDE} is not set where the tests run.
 */
class AppAnalyzerTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureOutput() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void shouldRequireTheChoiceOfThePackagesEvenForAPlainJar() throws IOException {
        Path jar = TestJars.createJar(tempDir.resolve("plain.jar"), ArchiveFixture.PaymentGateway.class);

        assertThat(AppAnalyzer.run(jar.toString())).isEqualTo(AppAnalyzer.INVALID_INPUT);
        assertThat(stderr()).contains("Choose the packages to analyze").contains("--include-all");
        assertThat(stdout()).doesNotContain("Start analysis");
    }

    @Test
    void shouldStopWhenTheFilterMatchesNoClass() throws IOException {
        int status = AppAnalyzer.run(fatJar().toString(), "--include", "com.nonexistent");

        // Persisting nothing would wipe the graph: it is left as it is
        assertThat(status).isEqualTo(AppAnalyzer.INVALID_INPUT);
        assertThat(stdout()).contains("0 classes from 3 class files (3 outside com.nonexistent");
        assertThat(stderr()).contains("No class matches com.nonexistent");
        assertThat(stdout()).doesNotContain("Connecting to Neo4j");
    }

    @Test
    void shouldFailWithUsageOnInvalidArguments() {
        assertThat(AppAnalyzer.run("app.zip", "--include-all")).isEqualTo(AppAnalyzer.INVALID_INPUT);
        assertThat(stderr()).contains("Not a .jar, .war or .ear").contains("Usage: AppAnalyzer");
    }

    @Test
    void shouldFailWhenTheArchiveDoesNotExist() {
        String missing = tempDir.resolve("missing.jar").toString();

        assertThat(AppAnalyzer.run(missing, "--include-all")).isEqualTo(AppAnalyzer.INVALID_INPUT);
        assertThat(stderr()).contains("Archive not found");
    }

    private Path fatJar() throws IOException {
        return TestJars.archive()
                .classesIn("BOOT-INF/classes/", ArchiveFixture.OrderService.class)
                .nestedStored("BOOT-INF/lib/acme-payments-core.jar", TestJars.archive()
                        .classes(ArchiveFixture.PaymentGateway.class))
                .nestedStored("BOOT-INF/lib/third-party.jar", TestJars.archive().classes(ThirdPartyClient.class))
                .writeTo(tempDir.resolve("app.jar"));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
