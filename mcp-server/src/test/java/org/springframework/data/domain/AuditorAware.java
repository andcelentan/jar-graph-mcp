package org.springframework.data.domain;

/**
 * Stand-in for a Spring Data type that is not a repository, so fixtures can check that only repository types are
 * flagged. Like {@link org.springframework.data.repository.Repository}, it is never packaged in fixture jars.
 */
public interface AuditorAware<T> {
}
