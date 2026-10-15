package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.Invocation;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import com.jgraph.analyzer.model.Visibility;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.List;

public class ModelBuildingMethodVisitor extends MethodVisitor {

    private final List<JavaMethod> methods;
    private final List<MethodCall> calls;

    private final String ownerClassFqcn;
    private final int access;
    private final String methodName;
    private final String descriptor;
    private final String callerSignature;

    private boolean isTransactional = false;

    public ModelBuildingMethodVisitor(List<JavaMethod> methods,
                                      List<MethodCall> calls,
                                      String ownerClassFqcn,
                                      int access,
                                      String methodName,
                                      String descriptor) {
        super(Opcodes.ASM9);
        this.methods = methods;
        this.calls = calls;
        this.ownerClassFqcn = ownerClassFqcn;
        this.access = access;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.callerSignature = ownerClassFqcn + "#" + methodName + descriptor;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String annotDescriptor, boolean visible) {
        if (annotDescriptor.contains("Transactional")) {
            this.isTransactional = true;
        }
        return super.visitAnnotation(annotDescriptor, visible);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String targetDescriptor, boolean isInterface) {
        // As written in bytecode: owner is the receiver's static type, resolved to the declaration later (ADR-0008)
        String targetSignature = owner + "#" + name + targetDescriptor;
        this.calls.add(new MethodCall(this.callerSignature, targetSignature, invocation(opcode), owner));
    }

    private static Invocation invocation(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> Invocation.VIRTUAL;
            case Opcodes.INVOKEINTERFACE -> Invocation.INTERFACE;
            case Opcodes.INVOKESPECIAL -> Invocation.SPECIAL;
            case Opcodes.INVOKESTATIC -> Invocation.STATIC;
            default -> throw new IllegalArgumentException("Not a method invocation opcode: " + opcode);
        };
    }

    @Override
    public void visitEnd() {
        String returnType = Type.getReturnType(this.descriptor).getClassName();
        List<String> parameterTypes = Arrays.stream(Type.getArgumentTypes(this.descriptor))
                .map(Type::getClassName)
                .toList();

        this.methods.add(new JavaMethod(
                this.ownerClassFqcn,
                this.methodName,
                this.descriptor,
                returnType,
                parameterTypes,
                visibility(this.access),
                (this.access & Opcodes.ACC_STATIC) != 0,
                (this.access & Opcodes.ACC_ABSTRACT) != 0,
                (this.access & Opcodes.ACC_BRIDGE) != 0,
                this.isTransactional
        ));
    }

    private static Visibility visibility(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return Visibility.PUBLIC;
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return Visibility.PROTECTED;
        }
        if ((access & Opcodes.ACC_PRIVATE) != 0) {
            return Visibility.PRIVATE;
        }
        return Visibility.PACKAGE_PRIVATE;
    }
}