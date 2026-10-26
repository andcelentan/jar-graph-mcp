package com.jgraph.analyzer;

import com.jgraph.analyzer.source.PackageFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The arguments of {@link AppAnalyzer}: {@code <archive> [--include <packages>]... [--include-all]}.
 */
final class CommandLine {

    static final String USAGE = """
            Usage: AppAnalyzer <app.jar|app.war|app.ear> (--include <packages>... | --include-all)
              --include com.acme.*   analyze only these packages (repeatable or comma separated); wins over
                                     ANALYZER_INCLUDE and analyzer.include
              --include-all          analyze every class, libraries included""";

    private static final List<String> ARCHIVE_EXTENSIONS = List.of(".jar", ".war", ".ear");

    private final Path archive;
    private final PackageFilter include;

    private CommandLine(Path archive, PackageFilter include) {
        this.archive = archive;
        this.include = include;
    }

    /**
     * @throws IllegalArgumentException with a message for the user, if the arguments are not valid
     */
    static CommandLine parse(String... args) {
        Path archive = null;
        List<String> patterns = new ArrayList<>();
        boolean includeAll = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--include" -> {
                    if (i + 1 == args.length) {
                        throw new IllegalArgumentException("--include needs packages, e.g. --include com.acme.*");
                    }
                    patterns.addAll(Arrays.asList(args[++i].split(",")));
                }
                case "--include-all" -> includeAll = true;
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    if (archive != null) {
                        throw new IllegalArgumentException("One archive per analysis (ADR-0002), got also: " + arg);
                    }
                    archive = Path.of(arg);
                }
            }
        }
        if (archive == null) {
            throw new IllegalArgumentException("No archive to analyze");
        }
        String fileName = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (ARCHIVE_EXTENSIONS.stream().noneMatch(fileName::endsWith)) {
            throw new IllegalArgumentException("Not a .jar, .war or .ear archive: " + archive);
        }
        if (includeAll && !patterns.isEmpty()) {
            throw new IllegalArgumentException("--include-all and --include exclude each other");
        }
        PackageFilter include = includeAll ? PackageFilter.everything()
                : patterns.isEmpty() ? null
                : PackageFilter.of(patterns);
        return new CommandLine(archive, include);
    }

    Path archive() {
        return archive;
    }

    /** The packages given on the command line, empty if none was: the configuration may give them. */
    Optional<PackageFilter> include() {
        return Optional.ofNullable(include);
    }
}
