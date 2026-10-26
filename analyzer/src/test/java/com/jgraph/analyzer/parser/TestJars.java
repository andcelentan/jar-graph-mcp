package com.jgraph.analyzer.parser;

import com.jgraph.analyzer.model.AnalysisResult;
import com.jgraph.analyzer.source.ArchiveClassFileSource;
import com.jgraph.analyzer.source.PackageFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/**
 * Builds throwaway archives from compiled test classes, and reads them with the real pipeline.
 */
public final class TestJars {

    private TestJars() {
    }

    /** A jar with the given classes at their usual paths, e.g. {@code com/acme/Foo.class}. */
    public static Path createJar(Path jarPath, Class<?>... classes) throws IOException {
        return archive().classes(classes).writeTo(jarPath);
    }

    /** Reads every class of the archive, as {@code AppAnalyzer} does with {@code --include-all}. */
    public static AnalysisResult parse(Path archive) throws IOException {
        return new BytecodeReader(PackageFilter.everything()).read(new ArchiveClassFileSource(archive)).model();
    }

    public static Archive archive() {
        return new Archive();
    }

    /** The bytes of the compiled class, as javac wrote them. */
    public static byte[] bytecodeOf(Class<?> clazz) throws IOException {
        String path = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Could not find class file for " + clazz.getName());
            }
            return is.readAllBytes();
        }
    }

    /** An archive to build entry by entry, in order: classes in any folder, other files, nested archives. */
    public static final class Archive {

        private final List<Entry> entries = new ArrayList<>();

        private Archive() {
        }

        /** The classes at their usual paths, e.g. {@code com/acme/Foo.class}. */
        public Archive classes(Class<?>... classes) throws IOException {
            return classesIn("", classes);
        }

        /** The classes under a folder, e.g. {@code BOOT-INF/classes/} for a Spring Boot fat jar. */
        public Archive classesIn(String folder, Class<?>... classes) throws IOException {
            for (Class<?> clazz : classes) {
                file(folder + clazz.getName().replace('.', '/') + ".class", bytecodeOf(clazz));
            }
            return this;
        }

        public Archive file(String name, byte[] content) {
            entries.add(new Entry(name, content, false));
            return this;
        }

        /** A nested archive, compressed like the entries of a WAR or EAR. */
        public Archive nested(String name, Archive archive) throws IOException {
            entries.add(new Entry(name, archive.toBytes(), false));
            return this;
        }

        /** A nested archive stored uncompressed, as Spring Boot stores the jars of {@code BOOT-INF/lib}. */
        public Archive nestedStored(String name, Archive archive) throws IOException {
            entries.add(new Entry(name, archive.toBytes(), true));
            return this;
        }

        public Path writeTo(Path path) throws IOException {
            Files.write(path, toBytes());
            return path;
        }

        byte[] toBytes() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (JarOutputStream jar = new JarOutputStream(bytes)) {
                for (Entry entry : entries) {
                    jar.putNextEntry(entry.toJarEntry());
                    jar.write(entry.content());
                    jar.closeEntry();
                }
            }
            return bytes.toByteArray();
        }

        private record Entry(String name, byte[] content, boolean stored) {

            JarEntry toJarEntry() {
                JarEntry entry = new JarEntry(name);
                if (stored) {
                    // A stored entry must declare its size and checksum up front
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(content.length);
                    entry.setCompressedSize(content.length);
                    entry.setCrc(crc.getValue());
                }
                return entry;
            }
        }
    }
}
