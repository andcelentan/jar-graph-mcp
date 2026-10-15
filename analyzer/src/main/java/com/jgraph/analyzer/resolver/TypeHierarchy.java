package com.jgraph.analyzer.resolver;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only indexes over the parsed model, built once per resolution so that resolvers stay stateless.
 * Only types included in the analysis are indexed: any other type has no known supertypes or methods.
 */
final class TypeHierarchy {

    private final Map<String, JavaClass> classesByFqcn;
    private final Map<String, List<String>> supertypesByType;
    private final Map<String, JavaMethod> methodsBySignature;

    private TypeHierarchy(Map<String, JavaClass> classesByFqcn,
                          Map<String, List<String>> supertypesByType,
                          Map<String, JavaMethod> methodsBySignature) {
        this.classesByFqcn = classesByFqcn;
        this.supertypesByType = supertypesByType;
        this.methodsBySignature = methodsBySignature;
    }

    static TypeHierarchy of(AnalysisResult result) {
        Map<String, JavaClass> classesByFqcn = new HashMap<>();
        // Adjacency list: type -> direct supertypes (superclass and interfaces)
        Map<String, List<String>> supertypesByType = new HashMap<>();
        for (JavaClass clazz : result.classes()) {
            // First declaration wins if the same type appears twice in the artifact
            if (classesByFqcn.putIfAbsent(clazz.fqcn(), clazz) == null) {
                List<String> supertypes = new ArrayList<>();
                if (clazz.superClass() != null) {
                    supertypes.add(clazz.superClass());
                }
                supertypes.addAll(clazz.interfaces());
                supertypesByType.put(clazz.fqcn(), List.copyOf(supertypes));
            }
        }

        Map<String, JavaMethod> methodsBySignature = new HashMap<>();
        for (JavaMethod method : result.methods()) {
            methodsBySignature.putIfAbsent(method.signature(), method);
        }

        return new TypeHierarchy(classesByFqcn, supertypesByType, methodsBySignature);
    }

    /**
     * @return whether {@code fqcn} is a type included in the analysis
     */
    boolean contains(String fqcn) {
        return classesByFqcn.containsKey(fqcn);
    }

    /**
     * @return the type, or {@code null} if it is not included in the analysis
     */
    JavaClass type(String fqcn) {
        return classesByFqcn.get(fqcn);
    }

    /**
     * @return the direct supertypes (superclass first, then interfaces); empty for types outside the analysis
     */
    List<String> supertypes(String fqcn) {
        return supertypesByType.getOrDefault(fqcn, List.of());
    }

    /**
     * @return the method declared with this {@link JavaMethod#signature()}, or {@code null} if no type in the
     * analysis declares it
     */
    JavaMethod method(String signature) {
        return methodsBySignature.get(signature);
    }

    /**
     * @return {@code owner#name(descriptor)}, the format of {@link JavaMethod#signature()}
     */
    static String signature(String ownerFqcn, String nameAndDescriptor) {
        return ownerFqcn + "#" + nameAndDescriptor;
    }

    static String ownerOf(String signature) {
        return signature.substring(0, signature.indexOf('#'));
    }

    /**
     * @return {@code name(descriptor)}: the part of a signature shared by a method and its overrides
     */
    static String nameAndDescriptorOf(String signature) {
        return signature.substring(signature.indexOf('#') + 1);
    }

    static String nameOf(String signature) {
        return signature.substring(signature.indexOf('#') + 1, signature.indexOf('('));
    }
}
