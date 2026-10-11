package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.ClassKind;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class ModelBuildingClassVisitor extends ClassVisitor {
    private static final String OBJECT = "java/lang/Object";
    private static final String RECORD = "java/lang/Record";
    private static final String ENUM = "java/lang/Enum";
    private static final String ANNOTATION = "java/lang/annotation/Annotation";

    /**
     * Supertypes added by the compiler to every class, record or enum, never written by the developer.
     * Not modelled as relationships: they would only add supernodes to the graph; {@link ClassKind} carries the
     * information instead (ADR-0006).
     */
    private static final Set<String> IMPLICIT_SUPERCLASSES = Set.of(OBJECT, RECORD, ENUM);

    private final List<JavaClass> classes;
    private final List<JavaMethod> methods;
    private final List<MethodCall> calls;

    private String currentClassName;

    public ModelBuildingClassVisitor(List<JavaClass> classes, List<JavaMethod> methods, List<MethodCall> calls) {
        super(Opcodes.ASM9);
        this.classes = classes;
        this.methods = methods;
        this.calls = calls;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.currentClassName = name;
        ClassKind kind = kindOf(access, superName);
        boolean isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0;

        // Interfaces declare java/lang/Object as superName: their supertypes are the superinterfaces
        String superClass = (kind.isInterface() || superName == null || IMPLICIT_SUPERCLASSES.contains(superName))
                ? null
                : superName;

        // Every annotation type implicitly extends java/lang/annotation/Annotation
        List<String> supertypeInterfaces = Arrays.stream(interfaces)
                .filter(iface -> kind != ClassKind.ANNOTATION || !ANNOTATION.equals(iface))
                .toList();

        int lastIndex = name.lastIndexOf("/");
        String simpleName = (lastIndex > -1 && lastIndex + 1 < name.length())
                ? name.substring(lastIndex + 1)
                : name;

        this.classes.add(new JavaClass(
                currentClassName, simpleName, kind, isAbstract, superClass, supertypeInterfaces));
    }

    private static ClassKind kindOf(int access, String superName) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) {
            return ClassKind.ANNOTATION; // Also flagged ACC_INTERFACE: check first
        }
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return ClassKind.INTERFACE;
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            return ClassKind.ENUM;
        }
        if (RECORD.equals(superName)) {
            return ClassKind.RECORD;
        }
        return ClassKind.CLASS;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        return new ModelBuildingMethodVisitor(
                this.methods,
                this.calls,
                this.currentClassName,
                access,
                name,
                descriptor
        );
    }
}
