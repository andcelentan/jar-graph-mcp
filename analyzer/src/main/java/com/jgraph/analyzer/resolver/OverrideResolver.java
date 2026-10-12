package com.jgraph.analyzer.resolver;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import com.jgraph.analyzer.model.MethodOverride;
import com.jgraph.analyzer.model.Visibility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives method overrides from the complete parsed model, before persistence (see ADR-0006 and ADR-0007).
 *
 * <p>Rules (JLS §8.4.8.1), applied only to types that belong to the analyzed artifact:
 * <ul>
 *     <li>same name and same descriptor;</li>
 *     <li>both are instance methods: static methods hide, they do not override; constructors ({@code <init>})
 *         and static initializers ({@code <clinit>}) never override;</li>
 *     <li>a private method is never overridden;</li>
 *     <li>a package-private method is only overridden from the same package;</li>
 *     <li>a method points to the nearest matching declaration along every supertype path (superclass and
 *         interfaces); farther declarations are reached transitively, through the nearest one;</li>
 *     <li>an override found for a bridge method is attributed to the method it forwards to, unless that would make
 *         a method override itself (visibility bridges).</li>
 * </ul>
 */
public final class OverrideResolver {

    private static final String CONSTRUCTOR = "<init>";
    private static final String STATIC_INITIALIZER = "<clinit>";

    /**
     * @return a copy of {@code result} with {@link AnalysisResult#overrides()} populated
     */
    public AnalysisResult resolve(AnalysisResult result) {
        TypeHierarchy hierarchy = TypeHierarchy.of(result);

        List<MethodOverride> overrides = new ArrayList<>();
        for (JavaMethod method : result.methods()) {
            if (canOverride(method)) {
                // Each method climbs the hierarchy on its own: a fresh visited set per method
                hierarchy.collectOverridden(method, method.ownerClassFqcn(), new HashSet<>(), overrides);
            }
        }
        return result.withOverrides(List.copyOf(overrides));
    }

    /**
     * Whether {@code method} can override anything at all.
     */
    private static boolean canOverride(JavaMethod method) {
        return !method.isStatic()
                && method.visibility() != Visibility.PRIVATE
                && !CONSTRUCTOR.equals(method.name())
                && !STATIC_INITIALIZER.equals(method.name());
    }

    /**
     * Whether {@code candidate}, declared in a supertype with the same name and descriptor, is overridden by
     * {@code method}.
     */
    private static boolean isOverriddenBy(JavaMethod candidate, JavaMethod method) {
        if (candidate.isStatic() || candidate.visibility() == Visibility.PRIVATE) {
            return false;
        }
        return candidate.visibility() != Visibility.PACKAGE_PRIVATE
                || packageOf(candidate.ownerClassFqcn()).equals(packageOf(method.ownerClassFqcn()));
    }

    private static String packageOf(String fqcn) {
        int lastSlash = fqcn.lastIndexOf('/');
        return lastSlash < 0 ? "" : fqcn.substring(0, lastSlash);
    }

    /**
     * Method name of a {@link JavaMethod#signature()}: {@code owner#name(descriptor)}.
     */
    private static String nameOf(String signature) {
        return signature.substring(signature.indexOf('#') + 1, signature.indexOf('('));
    }

    /**
     * Indexes over the parsed model, built once per {@link #resolve} call so that the resolver stays stateless.
     *
     * @param supertypesByType   adjacency list: type -> direct supertypes (superclass and interfaces)
     * @param methodsBySignature method lookup by {@link JavaMethod#signature()}
     * @param bridgedByBridge    bridge signature -> signature of the method it forwards to; only bridges whose body
     *                           calls exactly one distinct method with the same name (ADR-0007)
     */
    private record TypeHierarchy(Map<String, List<String>> supertypesByType,
                                 Map<String, JavaMethod> methodsBySignature,
                                 Map<String, String> bridgedByBridge) {

        static TypeHierarchy of(AnalysisResult result) {
            Map<String, List<String>> supertypesByType = new HashMap<>();
            for (JavaClass clazz : result.classes()) {
                List<String> supertypes = new ArrayList<>();
                if (clazz.superClass() != null) {
                    supertypes.add(clazz.superClass());
                }
                supertypes.addAll(clazz.interfaces());
                // First declaration wins if the same type appears twice in the artifact
                supertypesByType.putIfAbsent(clazz.fqcn(), supertypes);
            }

            Map<String, JavaMethod> methodsBySignature = new HashMap<>();
            for (JavaMethod method : result.methods()) {
                methodsBySignature.putIfAbsent(method.signature(), method);
            }

            return new TypeHierarchy(supertypesByType, methodsBySignature, bridgedMethods(result, methodsBySignature));
        }

        /**
         * One pass over the calls: for every bridge, the distinct same-name methods called from its body. Only
         * bridges with exactly one such target are kept; any other bridge is treated as a regular method.
         */
        private static Map<String, String> bridgedMethods(AnalysisResult result,
                                                          Map<String, JavaMethod> methodsBySignature) {
            Map<String, Set<String>> targetsByBridge = new HashMap<>();
            for (MethodCall call : result.calls()) {
                JavaMethod caller = methodsBySignature.get(call.callerSignature());
                if (caller != null && caller.isBridge() && caller.name().equals(nameOf(call.targetSignature()))) {
                    targetsByBridge.computeIfAbsent(caller.signature(), bridge -> new HashSet<>())
                            .add(call.targetSignature());
                }
            }

            Map<String, String> bridgedByBridge = new HashMap<>();
            targetsByBridge.forEach((bridge, targets) -> {
                if (targets.size() == 1) {
                    bridgedByBridge.put(bridge, targets.iterator().next());
                }
            });
            return bridgedByBridge;
        }

        /**
         * Depth-first walk up the hierarchy of {@code type}: on every path, stops at the nearest supertype that
         * declares a method overridden by {@code method}. Types outside the artifact have no known supertypes,
         * so the walk ends there.
         */
        void collectOverridden(JavaMethod method, String type, Set<String> visited, List<MethodOverride> out) {
            for (String supertype : supertypesByType.getOrDefault(type, List.of())) {
                if (!visited.add(supertype)) {
                    continue; // Already reached through another path (diamond)
                }
                JavaMethod candidate = methodsBySignature.get(supertype + "#" + method.name() + method.descriptor());
                if (candidate != null && isOverriddenBy(candidate, method)) {
                    // A bridge never owns the override: the method it forwards to does (ADR-0007)
                    String overriding = bridgedByBridge.getOrDefault(method.signature(), method.signature());
                    // A visibility bridge forwards to the very method it overrides: no self-override
                    if (!overriding.equals(candidate.signature())) {
                        out.add(new MethodOverride(overriding, candidate.signature()));
                    }
                } else {
                    collectOverridden(method, supertype, visited, out);
                }
            }
        }
    }
}
