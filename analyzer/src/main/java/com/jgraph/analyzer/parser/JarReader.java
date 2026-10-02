package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JarReader {
    private final JarFile jarFile;

    public JarReader(JarFile jarFile) {
        this.jarFile = jarFile;
    }

    public AnalysisResult readAndBuildModel() throws IOException {
        List<JavaClass> classes = new ArrayList<>();
        List<JavaMethod> methods = new ArrayList<>();
        List<MethodCall> calls = new ArrayList<>();

        ModelBuildingClassVisitor visitor = new ModelBuildingClassVisitor(classes, methods, calls);

        var entries = this.jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();

            // Escludiamo directory e file speciali come module-info
            if (!entry.isDirectory() && entry.getName().endsWith(".class") && !entry.getName().endsWith("module-info.class")) {

                try (InputStream is = this.jarFile.getInputStream(entry)) {
                    ClassReader cr = new ClassReader(is);
                    cr.accept(visitor, ClassReader.SKIP_DEBUG);
                }
            }
        }

        return new AnalysisResult(classes, methods, calls);
    }
}