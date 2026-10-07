package com.jgraph.analyzer;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.parser.JarReader;

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
            System.err.println("File JAR not found: " + path.toAbsolutePath());
            return;
        }

        System.out.println("Start analysis: " + path.toAbsolutePath());
        long start = System.currentTimeMillis();

        try (JarFile jarFile = new JarFile(path.toFile())) {
            JarReader reader = new JarReader(jarFile);
            AnalysisResult result = reader.readAndBuildModel();

            long elapsed = System.currentTimeMillis() - start;
            printSummary(result, elapsed);

        } catch (IOException e) {
            System.err.println("I/O error during JAR analysis: "+ e.getMessage());
            e.printStackTrace();
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