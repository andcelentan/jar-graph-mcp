package com.jgraph.analyzer;

import com.jgraph.analyzer.source.PackageFilter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CommandLineTest {

    @Test
    void shouldReadTheArchiveAlone() {
        CommandLine commandLine = CommandLine.parse("target/app.jar");

        assertThat(commandLine.archive()).isEqualTo(Path.of("target/app.jar"));
        assertThat(commandLine.include()).isEmpty();
    }

    @Test
    void shouldCollectRepeatedAndCommaSeparatedIncludes() {
        CommandLine commandLine = CommandLine.parse("--include", "com.acme.*,com.partner", "app.war",
                "--include", "org.shared");

        assertThat(commandLine.include()).get().extracting(PackageFilter::patterns).asList()
                .containsExactly("com.acme.*", "com.partner", "org.shared");
    }

    @Test
    void shouldReadIncludeAll() {
        assertThat(CommandLine.parse("app.ear", "--include-all").include())
                .get().matches(PackageFilter::includesEverything);
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThatIllegalArgumentException().isThrownBy(CommandLine::parse).withMessageContaining("No archive");
        assertThatIllegalArgumentException().isThrownBy(() -> CommandLine.parse("app.zip"))
                .withMessageContaining("Not a .jar, .war or .ear");
        assertThatIllegalArgumentException().isThrownBy(() -> CommandLine.parse("a.jar", "b.jar"))
                .withMessageContaining("One archive per analysis");
        assertThatIllegalArgumentException().isThrownBy(() -> CommandLine.parse("app.jar", "--verbose"))
                .withMessageContaining("Unknown option: --verbose");
        assertThatIllegalArgumentException().isThrownBy(() -> CommandLine.parse("app.jar", "--include"))
                .withMessageContaining("--include needs packages");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CommandLine.parse("app.jar", "--include-all", "--include", "com.acme"))
                .withMessageContaining("exclude each other");
        assertThatIllegalArgumentException().isThrownBy(() -> CommandLine.parse("app.jar", "--include", "com.*.x"))
                .withMessageContaining("com.*.x");
    }
}
