package com.jgraph.mcp.fixture;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.parser.JarReader;
import com.jgraph.analyzer.parser.TestJars;
import com.jgraph.analyzer.repository.Neo4jGraphRepository;
import com.jgraph.analyzer.resolver.ModelResolver;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.neo4j.Neo4jContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * The graph of {@link McpFixture}, written by the analyzer pipeline itself (parse → resolve → save) on a real Neo4j
 * (ADR-0005), so tests read the same graph the server reads in production (ADR-0009).
 * <p>
 * Singleton container: started once per test JVM and shared by every test class, since tests only read the graph.
 * Testcontainers removes it when the JVM exits.
 */
public final class FixtureGraph {

    private static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5.26-community").withoutAuthentication();

    private static Driver driver;

    private FixtureGraph() {
    }

    public static synchronized Driver driver() {
        if (driver == null) {
            NEO4J.start();
            driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.none());
            load(driver);
        }
        return driver;
    }

    private static void load(Driver driver) {
        try {
            Path jarPath = Files.createTempFile("mcp-fixture", ".jar");
            jarPath.toFile().deleteOnExit();
            TestJars.createJar(jarPath, McpFixture.CLASSES);
            AnalysisResult parsed;
            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                parsed = new JarReader(jarFile).readAndBuildModel();
            }
            // Not closed: close() would also close the shared driver
            Neo4jGraphRepository repository = new Neo4jGraphRepository(driver);
            repository.initSchema();
            repository.deleteAll();
            repository.save(new ModelResolver().resolve(parsed));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build the fixture graph", e);
        }
    }
}
