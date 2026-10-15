package com.jgraph.analyzer.resolver;

import com.jgraph.analyzer.model.AnalysisResult;

/**
 * Transform step of the analyzer: derives from the parsed model the facts that need the whole type hierarchy.
 *
 * <p>Order matters: calls are resolved first (ADR-0008), then overrides are computed (ADR-0006), because the bridge
 * index of ADR-0007 is built from the calls in bridge bodies, which may themselves target inherited methods.
 */
public final class ModelResolver {

    private final CallResolver callResolver = new CallResolver();
    private final OverrideResolver overrideResolver = new OverrideResolver();

    /**
     * @return a copy of {@code parsed} with calls pointing at declared methods and overrides populated
     */
    public AnalysisResult resolve(AnalysisResult parsed) {
        return overrideResolver.resolve(callResolver.resolve(parsed));
    }
}
