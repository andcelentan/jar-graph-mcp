package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.List;

public class ModelBuildingClassVisitor extends ClassVisitor {
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
        boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0;

        int lastIndex = name.lastIndexOf("/");
        String simpleName = (lastIndex > -1 && lastIndex + 1 < name.length())
                ? name.substring(lastIndex + 1)
                : name;

        this.classes.add(new JavaClass(currentClassName, simpleName, isInterface, Arrays.asList(interfaces)));
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        return new ModelBuildingMethodVisitor(
                this.methods,
                this.calls,
                this.currentClassName,
                name,
                descriptor
        );
    }
}
