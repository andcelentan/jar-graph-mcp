package com.jgraph.analyzer;

import com.jgraph.analyzer.config.AnalysisConfig;
import com.jgraph.analyzer.config.DatabaseConfig;
import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.parser.BytecodeReader;
import com.jgraph.analyzer.parser.ReadResult;
import com.jgraph.analyzer.repository.GraphRepository;
import com.jgraph.analyzer.repository.Neo4jGraphRepository;
import com.jgraph.analyzer.resolver.ModelResolver;
import com.jgraph.analyzer.source.ArchiveClassFileSource;
import com.jgraph.analyzer.source.PackageFilter;
import org.neo4j.driver.exceptions.Neo4jException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class AppAnalyzer {

    /** Exit codes: a CI job or an ECS task must fail when the analysis does. */
    static final int OK = 0;
    static final int FAILED = 1;
    static final int INVALID_INPUT = 2;

    public static void main(String[] args) {
        int status = run(args);
        if (status != OK) {
            System.exit(status);
        }
    }

    static int run(String... args) {
        CommandLine commandLine;
        Optional<PackageFilter> filter;
        try {
            commandLine = CommandLine.parse(args);
            // The command line wins over the configuration (ADR-0012)
            filter = commandLine.include().or(() -> AnalysisConfig.load().include().map(PackageFilter::parse));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(CommandLine.USAGE);
            return INVALID_INPUT;
        }
        if (filter.isEmpty()) {
            System.err.println("Choose the packages to analyze: --include com.acme.* (or ANALYZER_INCLUDE, or "
                    + "analyzer.include), or --include-all for every class, libraries included.");
            System.err.println(CommandLine.USAGE);
            return INVALID_INPUT;
        }
        Path archive = commandLine.archive();
        if (!Files.isRegularFile(archive)) {
            System.err.println("Archive not found: " + archive.toAbsolutePath());
            return INVALID_INPUT;
        }

        try {
            return analyze(archive, filter.get());
        } catch (IOException e) {
            System.err.println("I/O error during the analysis: " + e.getMessage());
            return FAILED;
        } catch (Neo4jException e) {
            System.err.println("Database error: Could not interact with Neo4j. Is the Docker container running?");
            System.err.println("Details: " + e.getMessage());
            return FAILED;
        }
    }

    private static int analyze(Path archive, PackageFilter filter) throws IOException {
        System.out.println("Start analysis: " + archive.toAbsolutePath() + " (packages: " + filter + ")");
        long start = System.currentTimeMillis();

        ReadResult read = new BytecodeReader(filter).read(new ArchiveClassFileSource(archive));
        System.out.printf("Bytecode parsed: %d classes from %d class files (%d outside %s, %d duplicates skipped).%n",
                read.model().classes().size(), read.classFilesRead(), read.excludedByFilter(), filter,
                read.duplicatesSkipped());
        if (read.model().classes().isEmpty()) {
            // Persisting would wipe the graph and write nothing
            System.err.println("No class matches " + filter + ": nothing analyzed, the graph is left as it is.");
            return INVALID_INPUT;
        }

        AnalysisResult result = new ModelResolver().resolve(read.model());

        System.out.println("Connecting to Neo4j and persisting graph...");
        try (GraphRepository repository = new Neo4jGraphRepository(DatabaseConfig.load())) {
            repository.initSchema();
            repository.deleteAll();
            repository.save(result);
        }
        System.out.println("Persistence complete.");

        printSummary(result, System.currentTimeMillis() - start);
        return OK;
    }

    private static void printSummary(AnalysisResult result, long elapsedMs) {
        long interfaceCount = result.classes().stream().filter(JavaClass::isInterface).count();
        long transactionalCount = result.methods().stream().filter(JavaMethod::isTransactional).count();

        System.out.println("========================================");
        System.out.printf("Execution time:        %d ms%n", elapsedMs);
        System.out.printf("Total classes:         %d (%d interfaces)%n", result.classes().size(), interfaceCount);
        System.out.printf("Total methods:         %d (%d @Transactional)%n", result.methods().size(), transactionalCount);
        System.out.printf("Invocations [:CALLS]:  %d%n", result.calls().size());
        System.out.printf("Overrides:             %d%n", result.overrides().size());
        System.out.println("========================================");
    }
}
