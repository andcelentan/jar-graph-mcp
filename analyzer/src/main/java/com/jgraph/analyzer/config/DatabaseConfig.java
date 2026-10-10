package com.jgraph.analyzer.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record DatabaseConfig(String uri, String username, String password) {

    private static final String DEFAULT_PROPERTIES = "application.properties";

    public static DatabaseConfig load() {
        Properties props = new Properties();

        try (InputStream is = DatabaseConfig.class.getClassLoader().getResourceAsStream(DEFAULT_PROPERTIES)) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            System.err.println("Cannot load " + DEFAULT_PROPERTIES + ", using fallback/env values.");
        }

        // Environment variables override properties
        String uri = getEnvOrProperty("NEO4J_URI", props.getProperty("neo4j.uri"));
        String username = getEnvOrProperty("NEO4J_USERNAME", props.getProperty("neo4j.username"));
        String password = getEnvOrProperty("NEO4J_PASSWORD", props.getProperty("neo4j.password"));

        return new DatabaseConfig(uri, username, password);
    }

    private static String getEnvOrProperty(String envKey, String fallbackValue) {
        String env = System.getenv(envKey);
        return (env != null && !env.isBlank()) ? env : fallbackValue;
    }

    @Override
    public String toString() {
        return "DatabaseConfig{" +
                "uri='" + uri + '\'' +
                '}';
    }
}