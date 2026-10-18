package org.springframework.data.repository;

/**
 * Stand-in for Spring Data's marker interface, so fixtures can extend it without Spring Data on the test classpath.
 * It is compiled but never packaged in fixture jars: like the real one, it stays outside the analysis.
 */
public interface Repository<T, ID> {
}
