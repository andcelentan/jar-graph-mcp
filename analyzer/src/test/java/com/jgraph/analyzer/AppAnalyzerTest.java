package com.jgraph.analyzer;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.parser.ArchiveFixture;
import com.jgraph.analyzer.parser.TestJars;
import com.jgraph.analyzer.repository.GraphRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;
import org.thirdparty.library.ThirdPartyClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command line against a recording repository: every failure must end with a non-zero exit code before touching the
 * graph, so a CI job notices, and the database must be checked before the archive is read. Assumes
 * {@code ANALYZER_INCLUDE} is not set where the tests run.
 */
class AppAnalyzerTest {

    private final RecordingRepository repository = new RecordingRepository();

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

        assertThat(AppAnalyzer.run(repository, jar.toString())).isEqualTo(AppAnalyzer.INVALID_INPUT);
        assertThat(stderr()).contains("Choose the packages to analyze").contains("--include-all");
        assertThat(stdout()).doesNotContain("Start analysis");
        assertThat(repository.opened).isFalse();
    }

    @Test
    void shouldStopWhenTheFilterMatchesNoClass() throws IOException {
        int status = AppAnalyzer.run(repository, fatJar().toString(), "--include", "com.nonexistent");

        // Persisting nothing would wipe the graph: it is left as it is
        assertThat(status).isEqualTo(AppAnalyzer.INVALID_INPUT);
        assertThat(stdout()).contains("0 classes from 3 class files (3 outside com.nonexistent");
        assertThat(stderr()).contains("No class matches com.nonexistent");
        assertThat(repository.calls).containsExactly("verifyConnectivity", "close");
    }

    @Test
    void shouldFailWithUsageOnInvalidArguments() {
        assertThat(AppAnalyzer.run(repository, "app.zip", "--include-all")).isEqualTo(AppAnalyzer.INVALID_INPUT);
        assertThat(stderr()).contains("Not a .jar, .war or .ear").contains("Usage: AppAnalyzer");
        assertThat(repository.opened).isFalse();
    }

    @Test
    void shouldFailWhenTheArchiveDoesNotExist() {
        String missing = tempDir.resolve("missing.jar").toString();

        assertThat(AppAnalyzer.run(repository, missing, "--include-all")).isEqualTo(AppAnalyzer.INVALID_INPUT);
        assertThat(stderr()).contains("Archive not found");
        assertThat(repository.opened).isFalse();
    }

    @Test
    void shouldCheckTheConnectionBeforeReadingTheArchive() throws IOException {
        int status = AppAnalyzer.run(repository, fatJar().toString(), "--include-all");

        assertThat(status).isEqualTo(AppAnalyzer.OK);
        assertThat(repository.calls)
                .containsExactly("verifyConnectivity", "initSchema", "deleteAll", "save", "close");
        assertThat(stdout().indexOf("Checking the connection to Neo4j"))
                .isNotNegative()
                .isLessThan(stdout().indexOf("Bytecode parsed"));
        assertThat(repository.saved.classes()).hasSize(3);
    }

    @Test
    void shouldStopBeforeReadingTheArchiveWhenNeo4jCannotBeReached() throws IOException {
        repository.unreachable = true;

        int status = AppAnalyzer.run(repository, fatJar().toString(), "--include-all");

        assertThat(status).isEqualTo(AppAnalyzer.FAILED);
        assertThat(stderr()).contains("Cannot connect to Neo4j").contains("NEO4J_URI").contains("Connection refused");
        assertThat(stdout()).doesNotContain("Start analysis").doesNotContain("Bytecode parsed");
        assertThat(repository.calls).containsExactly("verifyConnectivity", "close");
    }

    @Test
    void shouldRejectAnInvalidDatabaseConfigurationAsInvalidInput() throws IOException {
        Supplier<GraphRepository> invalid = () -> {
            throw new IllegalArgumentException("Missing Neo4j setting: set NEO4J_URI or neo4j.uri.");
        };

        int status = AppAnalyzer.run(invalid, fatJar().toString(), "--include-all");

        assertThat(status).isEqualTo(AppAnalyzer.INVALID_INPUT);
        assertThat(stderr()).contains("Invalid Neo4j configuration").contains("NEO4J_URI");
        assertThat(stdout()).doesNotContain("Start analysis");
    }

    @Test
    void shouldReportAFailureWhileWritingTheGraph() throws IOException {
        repository.failOnSave = true;

        int status = AppAnalyzer.run(repository, fatJar().toString(), "--include-all");

        assertThat(status).isEqualTo(AppAnalyzer.FAILED);
        assertThat(stderr()).contains("Database error while writing the graph").contains("Transaction failed");
        assertThat(repository.calls).endsWith("save", "close");
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

    /** Records the order of the calls, fails like the Neo4j adapter does, and counts as opened once supplied. */
    private static final class RecordingRepository implements GraphRepository, Supplier<GraphRepository> {

        final List<String> calls = new ArrayList<>();
        boolean opened;
        boolean unreachable;
        boolean failOnSave;
        AnalysisResult saved;

        @Override
        public GraphRepository get() {
            opened = true;
            return this;
        }

        @Override
        public void verifyConnectivity() {
            calls.add("verifyConnectivity");
            if (unreachable) {
                throw new ServiceUnavailableException("Connection refused: localhost:7687");
            }
        }

        @Override
        public void initSchema() {
            calls.add("initSchema");
        }

        @Override
        public void deleteAll() {
            calls.add("deleteAll");
        }

        @Override
        public void save(AnalysisResult result) {
            calls.add("save");
            if (failOnSave) {
                throw new TransientException("Neo.TransientError.Transaction.Terminated", "Transaction failed");
            }
            saved = result;
        }

        @Override
        public void close() {
            calls.add("close");
        }
    }
}
