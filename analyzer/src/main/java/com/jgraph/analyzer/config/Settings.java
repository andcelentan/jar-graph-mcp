package com.jgraph.analyzer.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The settings of the analyzer: {@code application.properties} on the classpath, each value overridden by an
 * environment variable when that is set and not blank (ADR-0004). The file is packaged with the analyzer, so
 * environment variables are what CI and ECS use to change a value without rebuilding.
 */
final class Settings {

    private static final String PROPERTIES = "application.properties";

    private final Properties properties;

    private Settings(Properties properties) {
        this.properties = properties;
    }

    static Settings load() {
        Properties properties = new Properties();
        try (InputStream is = Settings.class.getClassLoader().getResourceAsStream(PROPERTIES)) {
            if (is != null) {
                properties.load(is);
            }
        } catch (IOException e) {
            System.err.println("Cannot load " + PROPERTIES + ", using environment variables only.");
        }
        return new Settings(properties);
    }

    /** @return the environment variable if set and not blank, otherwise the property, possibly {@code null} */
    String get(String environmentVariable, String property) {
        String fromEnvironment = System.getenv(environmentVariable);
        return fromEnvironment != null && !fromEnvironment.isBlank()
                ? fromEnvironment
                : properties.getProperty(property);
    }
}
