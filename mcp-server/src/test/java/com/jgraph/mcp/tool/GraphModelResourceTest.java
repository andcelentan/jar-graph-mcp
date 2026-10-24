package com.jgraph.mcp.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphModelResourceTest {

    @Test
    void shouldServeTheGraphModelWithTheRulesOfACorrectQuery() {
        String model = new GraphModelResource().graphModel();

        assertThat(model)
                .startsWith("# Code graph model")
                .contains("(:Method)-[:OVERRIDES]->(:Method)")
                .contains("NOT m.isBridge")
                .contains("c.kind IS NOT NULL")
                .contains("call.invocation IN ['VIRTUAL', 'INTERFACE']");
    }
}
