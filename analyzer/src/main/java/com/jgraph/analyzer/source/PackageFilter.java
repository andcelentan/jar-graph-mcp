package com.jgraph.analyzer.source;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Which classes belong to the analysis (ADR-0012): a class outside it is not analyzed, and becomes a stub node if the
 * analyzed code refers to it, like a class of the JDK.
 * <p>
 * A pattern names a package and everything below it: {@code com.acme}, {@code com.acme.*} and {@code com.acme.**} all
 * include {@code com.acme.Foo} and {@code com.acme.orders.Order}, matched on whole segments, so not
 * {@code com.acmeinc.Foo}. {@code *} alone includes every class. Nothing else is a wildcard.
 */
public final class PackageFilter {

    private static final String EVERYTHING = "*";

    private final List<String> patterns;
    /** Internal-name prefixes, e.g. {@code com/acme/}; empty with {@code everything}. */
    private final List<String> prefixes;
    private final boolean everything;

    private PackageFilter(List<String> patterns, List<String> prefixes, boolean everything) {
        this.patterns = patterns;
        this.prefixes = prefixes;
        this.everything = everything;
    }

    /** Every class. */
    public static PackageFilter everything() {
        return new PackageFilter(List.of(EVERYTHING), List.of(), true);
    }

    /**
     * @param patterns e.g. {@code com.acme.*}; blank ones are ignored
     * @throws IllegalArgumentException if no pattern is left, or one has a wildcard other than a trailing {@code .*}
     *                                  or {@code .**}, an empty segment or a slash
     */
    public static PackageFilter of(Collection<String> patterns) {
        List<String> given = patterns.stream()
                .map(Objects::requireNonNull)
                .map(String::strip)
                .filter(pattern -> !pattern.isEmpty())
                .distinct()
                .toList();
        if (given.isEmpty()) {
            throw new IllegalArgumentException("No package to include: give one, e.g. com.acme.*, or * for everything");
        }
        if (given.contains(EVERYTHING)) {
            return everything();
        }
        return new PackageFilter(given, given.stream().map(PackageFilter::toPrefix).toList(), false);
    }

    /** @param commaSeparated e.g. {@code com.acme.*,com.partner}, as in a property or an environment variable */
    public static PackageFilter parse(String commaSeparated) {
        return of(Arrays.asList(commaSeparated.split(",")));
    }

    /** @param internalName e.g. {@code com/acme/orders/Order}, as in bytecode */
    public boolean includes(String internalName) {
        return everything || prefixes.stream().anyMatch(internalName::startsWith);
    }

    public boolean includesEverything() {
        return everything;
    }

    /** The patterns as given, for messages. */
    public List<String> patterns() {
        return patterns;
    }

    @Override
    public String toString() {
        return String.join(",", patterns);
    }

    private static String toPrefix(String pattern) {
        String packageName = pattern.endsWith(".**") ? pattern.substring(0, pattern.length() - 3)
                : pattern.endsWith(".*") ? pattern.substring(0, pattern.length() - 2)
                : pattern;
        boolean emptySegment = Arrays.stream(packageName.split("\\.", -1)).anyMatch(String::isEmpty);
        if (packageName.contains("*") || packageName.contains("/") || emptySegment) {
            throw new IllegalArgumentException("Not a package to include: '" + pattern
                    + "'. Write a package name, optionally followed by .* (e.g. com.acme.*): no other wildcard");
        }
        // The trailing slash makes the match end on a segment: com/acme/ never matches com/acmeinc/Foo
        return packageName.replace('.', '/') + "/";
    }
}
