package com.jgraph.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MCP server exposing the code graph built by the analyzer to AI assistants (ADR-0009).
 *
 * <p>Started by the MCP client as a child process: requests arrive on standard input and responses leave on standard
 * output, so nothing else may be written there (see {@code application.yml} and {@code logback-spring.xml}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JarGraphMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(JarGraphMcpApplication.class, args);
    }
}
