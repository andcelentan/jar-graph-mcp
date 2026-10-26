package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.source.ClassFile;
import org.objectweb.asm.ClassReader;

import java.io.IOException;

/** Opens class files with ASM, turning its unchecked errors on a malformed file into an {@link IOException}. */
final class ClassReaders {

    private ClassReaders() {
    }

    static ClassReader open(ClassFile classFile) throws IOException {
        try {
            return new ClassReader(classFile.bytes());
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            // Also thrown for a class compiled for a Java version newer than the ASM in use
            throw new IOException("Cannot read the class file " + classFile.origin() + ": " + e.getMessage(), e);
        }
    }
}
