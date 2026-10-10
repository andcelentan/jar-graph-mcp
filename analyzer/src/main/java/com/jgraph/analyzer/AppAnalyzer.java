package com.jgraph.analyzer;

import com.jgraph.analyzer.config.DatabaseConfig;
import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.parser.JarReader;
import com.jgraph.analyzer.repository.GraphRepository;
import com.jgraph.analyzer.repository.Neo4jGraphRepository;
import org.neo4j.driver.exceptions.Neo4jException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

public class AppAnalyzer {

    public static void main(String[] args) {
        if (args.length == 0 || args[0].isBlank() || !args[0].endsWith(".jar")) {
            System.err.println("Invalid jar file");
            return;
        }

        Path path = Path.of(args[0]);
        if (!Files.exists(path)) {
            System.err.println("JAR file not found: " + path.toAbsolutePath());
            return;
        }

        System.out.println("Start analysis: " + path.toAbsolutePath());
        long start = System.currentTimeMillis();

        try (JarFile jarFile = new JarFile(path.toFile())) {
            JarReader reader = new JarReader(jarFile);
            AnalysisResult result = reader.readAndBuildModel();
            System.out.printf("Bytecode parsed successfully: %d classes found.%n", result.classes().size());

            System.out.println("Connecting to Neo4j and persisting graph...");
            try(GraphRepository repository = new Neo4jGraphRepository(DatabaseConfig.load())) {
                repository.initSchema();
                repository.deleteAll();
                repository.save(result);
            }
            System.out.println("Persistence complete.");

            long elapsed = System.currentTimeMillis() - start;
            printSummary(result, elapsed);

        } catch (IOException e) {
            System.err.println("I/O error during JAR analysis: "+ e.getMessage());
        } catch (Neo4jException e) {
            System.err.println("Database error: Could not interact with Neo4j. Is the Docker container running?");
            System.err.println("Details: " + e.getMessage());
        }
    }

    private static void printSummary(AnalysisResult result, long elapsedMs) {
        long interfaceCount = result.classes().stream().filter(JavaClass::isInterface).count();
        long transactionalCount = result.methods().stream().filter(JavaMethod::isTransactional).count();

        System.out.println("========================================");
        System.out.printf("Execution time:        %d ms%n", elapsedMs);
        System.out.printf("Total classes:         %d (%d interfaces)%n", result.classes().size(), interfaceCount);
        System.out.printf("Total methods:         %d (%d @Transactional)%n", result.methods().size(), transactionalCount);
        System.out.printf("Invocations [:CALLS]:  %d%n", result.calls().size());
        System.out.println("========================================");
    }
}