package com.jgraph.mcp.shared;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SourceNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"com.acme.Outer.Inner", "com.acme.Outer$Inner", "com/acme/Outer$Inner",
            " com.acme.Outer.Inner<java.util.Map<String, Order>> "})
    void shouldWriteEveryFormOfAClassNameDotted(String className) {
        assertThat(SourceNames.dottedClassName(className)).isEqualTo("com.acme.Outer.Inner");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void shouldRejectBlankClassNames(String className) {
        assertThatIllegalArgumentException().isThrownBy(() -> SourceNames.dottedClassName(className));
    }

    @ParameterizedTest
    @ValueSource(strings = "com/acme/Outer$Inner")
    void shouldConvertInternalNamesToBinaryNames(String internalName) {
        assertThat(SourceNames.binaryName(internalName)).isEqualTo("com.acme.Outer$Inner");
    }
}
