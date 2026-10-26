package com.jgraph.analyzer.config;

public record DatabaseConfig(String uri, String username, String password) {

    public static DatabaseConfig load() {
        Settings settings = Settings.load();
        return new DatabaseConfig(
                settings.get("NEO4J_URI", "neo4j.uri"),
                settings.get("NEO4J_USERNAME", "neo4j.username"),
                settings.get("NEO4J_PASSWORD", "neo4j.password"));
    }

    @Override
    public String toString() {
        return "DatabaseConfig{" +
                "uri='" + uri + '\'' +
                '}';
    }
}
