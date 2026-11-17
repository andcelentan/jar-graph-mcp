package com.jgraph.analyzer.config;

public record DatabaseConfig(String uri, String username, String password) {

    /**
     * @throws IllegalArgumentException if a setting is missing or blank, naming the environment variable to set
     */
    public static DatabaseConfig load() {
        Settings settings = Settings.load();
        return new DatabaseConfig(
                required(settings, "NEO4J_URI", "neo4j.uri"),
                required(settings, "NEO4J_USERNAME", "neo4j.username"),
                required(settings, "NEO4J_PASSWORD", "neo4j.password"));
    }

    private static String required(Settings settings, String environmentVariable, String property) {
        String value = settings.get(environmentVariable, property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing Neo4j setting: set " + environmentVariable + " or " + property + ".");
        }
        return value;
    }

    @Override
    public String toString() {
        return "DatabaseConfig{" +
                "uri='" + uri + '\'' +
                '}';
    }
}
