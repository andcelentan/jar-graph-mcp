package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.model.JavaClass;
import com.jgraph.analyzer.model.JavaMethod;
import com.jgraph.analyzer.model.MethodCall;
import com.jgraph.analyzer.source.ClassFile;
import com.jgraph.analyzer.source.ClassFileSource;
import com.jgraph.analyzer.source.PackageFilter;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the class files of a source into the model: the extract step of the pipeline. Every class file goes through
 * three steps (ADR-0012):
 * <ol>
 *   <li>its name is read from the bytecode, not from the entry path ({@code BOOT-INF/classes/com/acme/Foo.class} holds
 *       {@code com/acme/Foo});</li>
 *   <li>a class outside the {@link PackageFilter} is skipped;</li>
 *   <li>a class already read under the same name, from another archive for instance, is skipped: the first one in the
 *       order of the source wins.</li>
 * </ol>
 * The others go to one shared {@link ModelBuildingClassVisitor}.
 */
public final class BytecodeReader {

    private final PackageFilter filter;

    public BytecodeReader(PackageFilter filter) {
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    public ReadResult read(ClassFileSource source) throws IOException {
        Collector collector = new Collector(filter);
        source.forEach(collector);
        return collector.result();
    }

    /** Collects one read: the model being built, and the counts of what was left out. */
    private static final class Collector implements ClassFileSource.Consumer {

        private final PackageFilter filter;
        private final List<JavaClass> classes = new ArrayList<>();
        private final List<JavaMethod> methods = new ArrayList<>();
        private final List<MethodCall> calls = new ArrayList<>();
        private final ModelBuildingClassVisitor visitor = new ModelBuildingClassVisitor(classes, methods, calls);
        private final Set<String> classesRead = new HashSet<>();
        private int classFilesRead;
        private int excludedByFilter;
        private int duplicatesSkipped;

        Collector(PackageFilter filter) {
            this.filter = filter;
        }

        @Override
        public void accept(ClassFile classFile) throws IOException {
            classFilesRead++;
            ClassReader classReader = ClassReaders.open(classFile);
            String name = classReader.getClassName();
            if (!filter.includes(name)) {
                excludedByFilter++;
            } else if (!classesRead.add(name)) {
                duplicatesSkipped++;
            } else {
                classReader.accept(visitor, ClassReader.SKIP_DEBUG);
            }
        }

        ReadResult result() {
            return new ReadResult(new AnalysisResult(classes, methods, calls), classFilesRead, excludedByFilter,
                    duplicatesSkipped);
        }
    }
}
