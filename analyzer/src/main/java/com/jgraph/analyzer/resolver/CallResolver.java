package com.jgraph.analyzer.resolver;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Points every call at the method it refers to in the source code (ADR-0008).
 *
 * <p>Bytecode names the receiver's static type in a call (JLS §13.1), not the class that declares the method. When
 * that type belongs to the analysis but does not declare the method, the call is pointed at the declaration the JVM
 * would resolve (JVMS §5.4.3.3), keeping invocation and receiver:
 * <ol>
 *     <li>along the superclass chain, the first class that declares the method;</li>
 *     <li>otherwise, if the chain ended at an implicit supertype (Object, Enum, Record), the nearest declaration on
 *         every superinterface path.</li>
 * </ol>
 * A call is left as written when the chain reaches an explicit superclass outside the analysis (it may declare the
 * method), when no declaration is found, or when more than one is found: no link is invented.
 */
final class CallResolver {

    /**
     * @return a copy of {@code result} whose calls point at declared methods where they can be resolved
     */
    AnalysisResult resolve(AnalysisResult result) {
        TypeHierarchy hierarchy = TypeHierarchy.of(result);
        List<MethodCall> calls = result.calls().stream()
                .map(call -> resolve(call, hierarchy))
                .toList();
        return new AnalysisResult(result.classes(), result.methods(), calls, result.overrides());
    }

    private static MethodCall resolve(MethodCall call, TypeHierarchy hierarchy) {
        String target = call.targetSignature();
        String owner = TypeHierarchy.ownerOf(target);
        if (hierarchy.method(target) != null || !hierarchy.contains(owner)) {
            return call; // Declared where the bytecode says, or named type outside the analysis
        }
        String nameAndDescriptor = TypeHierarchy.nameAndDescriptorOf(target);

        // 1. Superclass chain
        JavaClass type = hierarchy.type(owner);
        while (type.superClass() != null) {
            if (!hierarchy.contains(type.superClass())) {
                return call; // Explicit superclass outside the analysis: it may declare the method
            }
            JavaMethod declared = hierarchy.method(TypeHierarchy.signature(type.superClass(), nameAndDescriptor));
            if (declared != null) {
                return call.withTarget(declared.signature());
            }
            type = hierarchy.type(type.superClass());
        }

        // 2. Superinterfaces: the chain ended at an implicit supertype (Object, Enum, Record)
        Set<String> declarations = new LinkedHashSet<>();
        collectNearestDeclarations(owner, nameAndDescriptor, hierarchy, new HashSet<>(), declarations);
        return declarations.size() == 1 ? call.withTarget(declarations.iterator().next()) : call;
    }

    /**
     * Depth-first walk up from {@code type}: on every path, the nearest supertype declaring the method. The superclasses
     * on the way were already checked by the caller and do not declare it, so the walk only stops at interfaces.
     */
    private static void collectNearestDeclarations(String type, String nameAndDescriptor, TypeHierarchy hierarchy,
                                                   Set<String> visited, Set<String> out) {
        for (String supertype : hierarchy.supertypes(type)) {
            if (!visited.add(supertype)) {
                continue; // Already reached through another path (diamond)
            }
            JavaMethod declared = hierarchy.method(TypeHierarchy.signature(supertype, nameAndDescriptor));
            if (declared != null) {
                out.add(declared.signature());
            } else {
                collectNearestDeclarations(supertype, nameAndDescriptor, hierarchy, visited, out);
            }
        }
    }
}
