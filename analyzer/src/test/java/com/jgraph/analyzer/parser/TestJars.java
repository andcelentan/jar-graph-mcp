package com.jgraph.analyzer.parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Builds throwaway JAR files from compiled test classes.
 */
public final class TestJars {

    private TestJars() {
    }

    public static Path createJar(Path jarPath, Class<?>... classes) throws IOException {
        try (OutputStream os = Files.newOutputStream(jarPath);
             JarOutputStream jos = new JarOutputStream(os)) {
            for (Class<?> clazz : classes) {
                String classFilePath = clazz.getName().replace('.', '/') + ".class";
                try (InputStream is = clazz.getClassLoader().getResourceAsStream(classFilePath)) {
                    if (is == null) {
                        throw new IOException("Could not find class file for " + clazz.getName());
                    }
                    jos.putNextEntry(new JarEntry(classFilePath));
                    is.transferTo(jos);
                    jos.closeEntry();
                }
            }
        }
        return jarPath;
    }
}
