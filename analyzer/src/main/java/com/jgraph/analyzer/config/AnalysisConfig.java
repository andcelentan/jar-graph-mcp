package com.jgraph.analyzer.config;

import java.util.Optional;

/**
 * Which packages to analyze (ADR-0012): {@code ANALYZER_INCLUDE}, or {@code analyzer.include} in
 * {@code application.properties}, as comma-separated patterns, e.g. {@code com.acme.*,com.partner.*}, or {@code *}
 * for everything. A {@code --include} on the command line wins over both.
 */
public final class AnalysisConfig {

    private final String include;

    private AnalysisConfig(String include) {
        this.include = include;
    }

    public static AnalysisConfig load() {
        String include = Settings.load().get("ANALYZER_INCLUDE", "analyzer.include");
        return new AnalysisConfig(include == null || include.isBlank() ? null : include.strip());
    }

    /** The patterns as written, empty when none is configured. */
    public Optional<String> include() {
        return Optional.ofNullable(include);
    }
}
