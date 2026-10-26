package com.jgraph.analyzer.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PackageFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"com.acme", "com.acme.*", "com.acme.**", " com.acme "})
    void shouldIncludeThePackageAndEverythingBelowIt(String pattern) {
        PackageFilter filter = PackageFilter.of(List.of(pattern));

        assertThat(filter.includes("com/acme/Foo")).isTrue();
        assertThat(filter.includes("com/acme/orders/Order")).isTrue();
        assertThat(filter.includes("com/acme/Outer$Inner")).isTrue();
    }

    @Test
    void shouldMatchWholeSegmentsOnly() {
        PackageFilter filter = PackageFilter.of(List.of("com.acme"));

        assertThat(filter.includes("com/acmeinc/Foo")).isFalse();
        assertThat(filter.includes("com/Foo")).isFalse();
        assertThat(filter.includes("org/acme/Foo")).isFalse();
    }

    @Test
    void shouldIncludeAClassMatchingAnyPattern() {
        PackageFilter filter = PackageFilter.parse("com.acme.*, org.partner , ");

        assertThat(filter.includes("com/acme/Foo")).isTrue();
        assertThat(filter.includes("org/partner/Bar")).isTrue();
        assertThat(filter.includes("org/springframework/Baz")).isFalse();
        assertThat(filter.patterns()).containsExactly("com.acme.*", "org.partner");
    }

    @Test
    void shouldIncludeEveryClassWithAStar() {
        PackageFilter filter = PackageFilter.parse("com.acme.*,*");

        assertThat(filter.includesEverything()).isTrue();
        assertThat(filter.includes("org/springframework/Baz")).isTrue();
        assertThat(filter.includes("Main")).isTrue();
        assertThat(PackageFilter.everything().includes("anything/At/All")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.*.acme", "com/acme", "com..acme", ".com.acme", "com.acme.", "*.acme", "com.acme.*.*"})
    void shouldRejectOtherWildcardsSlashesAndEmptySegments(String pattern) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PackageFilter.of(List.of(pattern)))
                .withMessageContaining(pattern);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " , ,"})
    void shouldRejectAnEmptyFilter(String patterns) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PackageFilter.parse(patterns))
                .withMessageContaining("No package to include");
    }
}
